package com.hmdp.ai.memory;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 多轮对话的上下文，存在 Redis 的 List 里。
 * <p>
 * {@code key = chat:memory:{userId}:{净化后的 sessionId}}
 * <p>
 * 三条纪律：
 * <ol>
 *   <li><b>userId 必须作为 key 前缀</b>，且由服务端从 {@code UserHolder} 取。
 *       如果直接把客户端传的 sessionId 当 key，用户 A 猜到用户 B 的 sessionId
 *       就能读到 B 的对话——这是一个水平越权漏洞</li>
 *   <li><b>sessionId 必须净化</b>。它是「用户可控且要拼进 Redis key」的输入，
 *       含 {@code :} 会污染 key 空间，超长会放大 key 内存</li>
 *   <li><b>只持久化 user 和最终 assistant 文本</b>，绝不持久化 {@code tool_calls}
 *       和 {@code role:"tool"} 消息。原因见 {@link #appendTurn}</li>
 * </ol>
 */
@Slf4j
@Component
public class ChatMemory {

    /** 空回答的兜底话术。ChatService 也复用它，保证"写进历史的内容"和"返回给用户的内容"一致 */
    public static final String FALLBACK_ANSWER = "抱歉，我暂时没能查到需要的信息，您可以换个说法，或联系人工客服。";

    private static final Pattern UNSAFE_CHARS = Pattern.compile("[^A-Za-z0-9_-]");
    private static final String DEFAULT_SESSION = "default";
    private static final int MAX_SESSION_LEN = 32;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private AiProperties props;

    /**
     * 读出最近若干轮历史消息（只有 user/assistant 两种角色）。
     * <p>
     * ⚠️ 这里 <b>fail-open</b>：Redis 不可用时返回空列表而不是抛异常。
     * 有会话记忆更好，没有也能聊——让"客服整个不可用"来换"历史丢失"是不划算的。
     * 这和 {@link com.hmdp.utils.RateLimitInterceptor} 里 fail-open 的理由是同一个。
     */
    public List<JSONObject> load(Long userId, String sessionId) {
        try {
            String key = key(userId, sessionId);
            List<String> raw = stringRedisTemplate.opsForList().range(key, -props.getMaxHistory(), -1);
            if (raw == null || raw.isEmpty()) {
                return Collections.emptyList();
            }
            List<JSONObject> messages = new ArrayList<>(raw.size());
            for (String item : raw) {
                if (StrUtil.isBlank(item)) {
                    continue;
                }
                try {
                    JSONObject message = JSONUtil.parseObj(item);
                    // 空 content 的历史重放上去，部分兼容层会报 content must not be empty
                    if (StrUtil.isNotBlank(message.getStr("content"))) {
                        messages.add(message);
                    }
                } catch (Exception ignored) {
                    // 单条坏数据跳过即可，不该让整轮对话失败
                }
            }
            return messages;
        } catch (Exception e) {
            log.warn("[AI] 读取会话历史失败，按「无历史」继续 userId={}", userId, e);
            return Collections.emptyList();
        }
    }

    /**
     * 追加一轮对话。
     * <p>
     * ⚠️ 只存 user 和最终 assistant 的<b>文本</b>，不存中间的 tool_calls / tool 结果：
     * <ol>
     *   <li>协议上 {@code role:"tool"} 必须与前面 {@code assistant.tool_calls} 的
     *       {@code tool_call_id} 严格配对，历史重放极易配不上 → 上游 400</li>
     *   <li>上一轮的订单状态是「当时」的，重放会让模型用旧数据回答新问题——
     *       这比不查工具更糟</li>
     *   <li>工具结果 JSON 通常比用户那句话大一个数量级，全存下来几轮就撑爆上下文</li>
     * </ol>
     */
    public void appendTurn(Long userId, String sessionId, String userMessage, String assistantMessage) {
        try {
            String key = key(userId, sessionId);
            String answer = StrUtil.isBlank(assistantMessage) ? FALLBACK_ANSWER : assistantMessage;
            stringRedisTemplate.opsForList().rightPush(key, messageJson("user", userMessage));
            stringRedisTemplate.opsForList().rightPush(key, messageJson("assistant", answer));
            // 物理截断，只保留最近 maxHistory 条
            stringRedisTemplate.opsForList().trim(key, -props.getMaxHistory(), -1);
            stringRedisTemplate.expire(key, RedisConstants.CHAT_MEMORY_TTL, TimeUnit.MINUTES);
        } catch (Exception e) {
            // 写不进去只是丢一次记忆，不该让这次已经成功的回答变成失败
            log.warn("[AI] 写入会话历史失败 userId={}", userId, e);
        }
    }

    private String key(Long userId, String sessionId) {
        return RedisConstants.CHAT_MEMORY_KEY + userId + ":" + sanitize(sessionId);
    }

    private String sanitize(String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            return DEFAULT_SESSION;
        }
        String safe = UNSAFE_CHARS.matcher(sessionId.trim()).replaceAll("");
        if (safe.length() > MAX_SESSION_LEN) {
            safe = safe.substring(0, MAX_SESSION_LEN);
        }
        return safe.isEmpty() ? DEFAULT_SESSION : safe;
    }

    private String messageJson(String role, String content) {
        return JSONUtil.createObj().set("role", role).set("content", content).toString();
    }
}
