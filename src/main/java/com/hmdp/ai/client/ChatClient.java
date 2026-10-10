package com.hmdp.ai.client;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.ai.config.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 模块里<b>唯一</b>的 HTTP 出口：调 OpenAI 兼容的 {@code /chat/completions}。
 * <p>
 * 用 Hutool 的 HttpUtil 而不是 RestTemplate/OkHttp——Hutool 已经在 pom 里，
 * 而且它的 JSON 与 HTTP 是一套，完全绕开 Jackson，不会和 Spring Boot 2.3.12
 * 管理的 Jackson 2.11.4 产生任何版本牵扯。
 * <p>
 * ⚠️ 这里刻意<b>不在构造函数里发任何网络请求</b>。所有失败推迟到真正调用时，
 * 由 ChatService 降级成业务失败，而不是让应用启动不起来。
 */
@Slf4j
@Component
public class ChatClient {

    private final AiProperties props;

    public ChatClient(AiProperties props) {
        this.props = props;
    }

    /**
     * 调一次 /chat/completions。
     *
     * @param messages 完整的 messages 数组（system + 历史 + 本轮 user + 中间的工具往返）
     * @param tools    工具定义；传 null 或空表示这一轮不给工具（用于强制模型用文字收尾）
     * @return {@code choices[0]}，里面同时有 {@code message} 和 {@code finish_reason} 两个子对象。
     * 之所以返回整个 choice 而不是只返回 message，是因为调用方需要 finish_reason 打日志
     * @throws AiUpstreamException 网络失败、超时、非 2xx、返回结构不符预期
     */
    public JSONObject complete(List<JSONObject> messages, JSONArray tools) {
        JSONObject body = JSONUtil.createObj()
                .set("model", props.getModel())
                .set("messages", messages)
                .set("temperature", props.getTemperature())
                .set("max_tokens", props.getMaxTokens())
                .set("stream", false);
        if (tools != null && !tools.isEmpty()) {
            body.set("tools", tools);
            body.set("tool_choice", "auto");
        }
        return parseChoice(post(body.toString()));
    }

    private String post(String bodyJson) {
        HttpRequest request = HttpUtil.createPost(props.chatCompletionsUrl())
                .header("Authorization", "Bearer " + props.getApiKey())
                .header("Content-Type", "application/json")
                .body(bodyJson);
        // ⚠️ 必须显式设超时。Hutool 的默认超时是 -1（无限等待），
        //    上游 hang 住会把 Tomcat 线程永久占住
        request.setConnectionTimeout(props.getConnectTimeoutMs());
        request.setReadTimeout(props.getReadTimeoutMs());

        try (HttpResponse response = request.execute()) {
            if (!response.isOk()) {
                // body 可以记（api_key 在 header 里，不在 body 里），但要截断
                log.warn("[AI] upstream status={} body={}", response.getStatus(),
                        StrUtil.maxLength(response.body(), 500));
                throw new AiUpstreamException("上游返回 HTTP " + response.getStatus());
            }
            return response.body();
        } catch (AiUpstreamException e) {
            throw e;
        } catch (Exception e) {
            throw new AiUpstreamException("上游调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * ⚠️ 这里刻意做显式判空而不是靠 NPE——上游返回结构不符预期时要给出
     * 一条能定位问题日志，而不是一个没有上下文的空指针
     */
    private JSONObject parseChoice(String raw) {
        JSONObject root;
        try {
            root = JSONUtil.parseObj(raw);
        } catch (Exception e) {
            throw new AiUpstreamException("上游返回不是合法 JSON", e);
        }
        JSONArray choices = root.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new AiUpstreamException("上游返回缺少 choices");
        }
        JSONObject choice = choices.getJSONObject(0);
        if (choice == null || choice.getJSONObject("message") == null) {
            throw new AiUpstreamException("上游返回缺少 message");
        }
        return choice;
    }
}
