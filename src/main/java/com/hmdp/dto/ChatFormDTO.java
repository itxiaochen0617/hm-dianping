package com.hmdp.dto;

import lombok.Data;

/**
 * 客服对话的请求体。与 {@link LoginFormDTO} 同风格。
 */
@Data
public class ChatFormDTO {

    /** 用户这一句话 */
    private String message;

    /**
     * 会话 id，可选。不传则退化成「每个用户一条滚动会话」。
     * <p>
     * ⚠️ 这是用户可控且要拼进 Redis key 的输入，服务端会做字符净化，
     * 并且强制以 userId 作为 key 前缀——见 {@link com.hmdp.ai.memory.ChatMemory}
     */
    private String sessionId;
}
