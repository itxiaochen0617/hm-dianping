package com.hmdp.ai.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.ai.client.AiUpstreamException;
import com.hmdp.ai.client.ChatClient;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.memory.ChatMemory;
import com.hmdp.ai.tool.ToolRegistry;
import com.hmdp.dto.ChatFormDTO;
import com.hmdp.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * 客服对话的编排：跑 tool-calling 循环 + 读写会话历史 + 分层降级。
 * <p>
 * ⚠️ <b>整个流程是同步的，跑在请求线程里</b>。因为 {@code UserHolder} 是 ThreadLocal，
 * 一旦这里改成 {@code @Async} 或提交线程池，工具在子线程里就拿不到 userId 了。
 * 所以 userId 是作为普通参数一路显式传下去的，没有一处依赖 ThreadLocal。
 */
@Slf4j
@Service
public class ChatService {

    @Resource
    private AiProperties props;

    @Resource
    private ChatClient chatClient;

    @Resource
    private ToolRegistry toolRegistry;

    @Resource
    private ChatMemory chatMemory;

    /**
     * 处理一次用户提问。
     *
     * @param userId 当前登录用户 id，由 controller 从 UserHolder 取出后传入
     */
    public Result chat(Long userId, ChatFormDTO form) {
        // 没配置 key 时不是 500，而是一条明确的业务失败
        if (!props.isUsable()) {
            return Result.fail("智能客服暂未开通，请稍后再试");
        }
        if (form == null || StrUtil.isBlank(form.getMessage())) {
            return Result.fail("请输入您的问题");
        }
        // 超长输入截断而不是报错——报错体验差，截断后模型通常仍能回答
        String question = StrUtil.maxLength(form.getMessage().trim(), props.getMaxUserMessageLength());

        long start = System.currentTimeMillis();
        // 不记消息原文：既是用户隐私，也可能含手机号
        log.info("[AI] chat start userId={} msgLen={}", userId, question.length());

        try {
            List<JSONObject> messages = new ArrayList<>();
            messages.add(message("system", props.getSystemPrompt()));
            messages.addAll(chatMemory.load(userId, form.getSessionId()));
            messages.add(message("user", question));

            JSONArray tools = toolRegistry.toolDefinitions();
            String answer = null;
            int usedRounds = 0;

            for (int round = 1; round <= props.getMaxRounds(); round++) {
                usedRounds = round;
                JSONObject choice = chatClient.complete(messages, tools);
                JSONObject reply = choice.getJSONObject("message");
                JSONArray toolCalls = reply.getJSONArray("tool_calls");

                log.debug("[AI] round={} finish={} toolCallCount={} contentLen={}", round,
                        choice.getStr("finish_reason"), toolCalls == null ? 0 : toolCalls.size(),
                        StrUtil.length(reply.getStr("content")));

                // ★ 判定依据是「有没有 tool_calls 数组」，不是 finish_reason——
                //   各厂商 finish_reason 的取值并不一致，以结构为准
                if (toolCalls == null || toolCalls.isEmpty()) {
                    answer = reply.getStr("content");
                    break;
                }

                // 1) 把 assistant 消息回灌进上下文（必须带上 tool_calls，否则下一轮协议校验不过）
                messages.add(toAssistantMessage(reply));

                // 2) 逐个执行工具，把结果以 role:"tool" 回灌。tool_call_id 必须一一对应
                for (int i = 0; i < toolCalls.size(); i++) {
                    JSONObject toolCall = toolCalls.getJSONObject(i);
                    JSONObject function = toolCall.getJSONObject("function");
                    String result = toolRegistry.execute(userId,
                            function.getStr("name"), function.getStr("arguments"));
                    messages.add(JSONUtil.createObj()
                            .set("role", "tool")
                            .set("tool_call_id", toolCall.getStr("id"))
                            .set("content", result));
                }
            }

            // 到轮数上限还在调工具 → 摘掉 tools 再问一次，逼它用文字收尾。
            // 直接把带 tool_calls 的上下文丢回去，用户会看到一片空白
            if (StrUtil.isBlank(answer)) {
                answer = chatClient.complete(messages, null).getJSONObject("message").getStr("content");
            }
            if (StrUtil.isBlank(answer)) {
                answer = ChatMemory.FALLBACK_ANSWER;
            }

            chatMemory.appendTurn(userId, form.getSessionId(), question, answer);
            log.info("[AI] chat done userId={} rounds={} answerLen={} totalCost={}ms",
                    userId, usedRounds, answer.length(), System.currentTimeMillis() - start);
            return Result.ok(answer);

        } catch (AiUpstreamException e) {
            // 网络不通 / 超时 / 上游 4xx-5xx / 返回结构不符预期
            log.warn("[AI] upstream 不可用 userId={} cause={}", userId, e.getMessage());
            return Result.fail("客服暂时不可用，请稍后再试");
        } catch (Exception e) {
            // 兜底。项目里 WebExceptionAdvice 会把一切 RuntimeException 变成含糊的
            // "服务器异常"，这里分层捕获就是为了给用户区分出「没开通 / 上游挂了 / 内部出错」
            log.error("[AI] chat 异常 userId={}", userId, e);
            return Result.fail("客服处理失败，请稍后再试");
        }
    }

    /**
     * 把响应里的 message 重建成一个<b>最小</b>的 assistant 对象。
     * <p>
     * ⚠️ 不要把整个 message 原样回灌：响应里可能带 {@code reasoning_content}、
     * {@code refusal}、{@code annotations} 等厂商私有字段，严格实现的兼容层
     * 会因为未知字段直接 400
     */
    private JSONObject toAssistantMessage(JSONObject reply) {
        JSONObject assistant = JSONUtil.createObj().set("role", "assistant");
        String content = reply.getStr("content");
        // hutool 的 set 会丢弃 null 值，而协议要求这个字段存在，用空串代替
        assistant.set("content", content == null ? "" : content);

        JSONArray rebuilt = new JSONArray();
        JSONArray toolCalls = reply.getJSONArray("tool_calls");
        for (int i = 0; i < toolCalls.size(); i++) {
            JSONObject toolCall = toolCalls.getJSONObject(i);
            JSONObject function = toolCall.getJSONObject("function");
            rebuilt.add(JSONUtil.createObj()
                    .set("id", toolCall.getStr("id"))
                    .set("type", "function")
                    .set("function", JSONUtil.createObj()
                            .set("name", function.getStr("name"))
                            .set("arguments", function.getStr("arguments"))));
        }
        assistant.set("tool_calls", rebuilt);
        return assistant;
    }

    private JSONObject message(String role, String content) {
        return JSONUtil.createObj().set("role", role).set("content", content);
    }
}
