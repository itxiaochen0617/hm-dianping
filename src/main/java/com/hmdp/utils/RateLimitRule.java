package com.hmdp.utils;

import lombok.Getter;

/**
 * 一条限流规则。
 * <p>
 * 用静态工厂方法构造，避免一长串 long 参数分不清哪个是哪个。
 */
@Getter
public class RateLimitRule {

    /**
     * 限流维度
     */
    public enum Dimension {
        /** 按当前登录用户。未登录时自动降级为按 IP */
        USER,
        /** 按客户端 IP */
        IP
    }

    /**
     * 规则名。参与限流 key 的拼接，保证不同规则各自计数、互不干扰
     */
    private final String name;

    /**
     * Ant 风格的路径模式，如 /voucher-order/seckill/**
     */
    private final String pathPattern;

    private final RateLimitAlgorithm algorithm;

    private final Dimension dimension;

    // ===== 固定窗口用 =====
    private final long limit;
    private final long windowSec;

    // ===== 令牌桶用 =====
    private final long capacity;
    private final long ratePerSec;
    private final long requestedTokens;

    private RateLimitRule(String name, String pathPattern, RateLimitAlgorithm algorithm, Dimension dimension,
                          long limit, long windowSec, long capacity, long ratePerSec, long requestedTokens) {
        this.name = name;
        this.pathPattern = pathPattern;
        this.algorithm = algorithm;
        this.dimension = dimension;
        this.limit = limit;
        this.windowSec = windowSec;
        this.capacity = capacity;
        this.ratePerSec = ratePerSec;
        this.requestedTokens = requestedTokens;
    }

    /**
     * 固定窗口规则：windowSec 秒内最多放行 limit 次
     */
    public static RateLimitRule fixedWindow(String name, String pathPattern, Dimension dimension,
                                            long limit, long windowSec) {
        return new RateLimitRule(name, pathPattern, RateLimitAlgorithm.FIXED_WINDOW, dimension,
                limit, windowSec, 0, 0, 0);
    }

    /**
     * 令牌桶规则：桶容量 capacity，每秒生成 ratePerSec 个令牌，每次请求消耗 1 个
     */
    public static RateLimitRule tokenBucket(String name, String pathPattern, Dimension dimension,
                                            long capacity, long ratePerSec) {
        return new RateLimitRule(name, pathPattern, RateLimitAlgorithm.TOKEN_BUCKET, dimension,
                0, 0, capacity, ratePerSec, 1);
    }
}
