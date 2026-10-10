package com.hmdp.ai.client;

/**
 * 上游大模型调用失败（网络不通、超时、上游 4xx/5xx、返回结构不符预期）。
 * <p>
 * 单独定义这个异常类型，是为了让 {@link com.hmdp.ai.service.ChatService}
 * 能把「客服暂时不可用」和「程序内部出错」区分成两种不同的用户话术——
 * 如果直接抛 RuntimeException，会被
 * {@link com.hmdp.config.WebExceptionAdvice} 一并吞成含糊的"服务器异常"。
 */
public class AiUpstreamException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AiUpstreamException(String message) {
        super(message);
    }

    public AiUpstreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
