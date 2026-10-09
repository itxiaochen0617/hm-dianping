package com.hmdp.utils;

/**
 * 限流算法
 */
public enum RateLimitAlgorithm {

    /**
     * 固定窗口：每个时间窗口一个计数器，INCR 超过阈值就拒绝。
     * 实现简单，但有临界问题——窗口边界处可能通过阈值的 2 倍。
     * 适合普通接口。
     */
    FIXED_WINDOW,

    /**
     * 令牌桶：按固定速率生成令牌，桶容量决定允许的突发量。
     * 允许瞬时突发，同时限制长期平均速率。
     * 适合秒杀这类"平时没请求、开抢瞬间爆发"的场景。
     */
    TOKEN_BUCKET
}
