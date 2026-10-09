package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 接口限流拦截器。
 * <p>
 * 注册在 {@link com.hmdp.config.MvcConfig} 的 order(2)，也就是
 * RefreshTokenInterceptor(order 0) 和 LoginInterceptor(order 1) 之后：
 * <ul>
 *     <li>order(0) 已经把当前用户放进 {@link UserHolder}，所以这里能拿到 userId 做「按用户限流」</li>
 *     <li>没通过登录校验的请求在 order(1) 就被拦掉了，不消耗限流配额</li>
 * </ul>
 * 注意本拦截器拦截 <b>所有</b>路径（不像 LoginInterceptor 有白名单）——
 * 公开接口（/shop/**、/user/code）同样是攻击面。
 */
@Slf4j
public class RateLimitInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate stringRedisTemplate;

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    private static final DefaultRedisScript<Long> FIXED_WINDOW_SCRIPT;
    private static final DefaultRedisScript<Long> TOKEN_BUCKET_SCRIPT;

    static {
        FIXED_WINDOW_SCRIPT = new DefaultRedisScript<>();
        FIXED_WINDOW_SCRIPT.setLocation(new ClassPathResource("rate_limit_fixed_window.lua"));
        FIXED_WINDOW_SCRIPT.setResultType(Long.class);

        TOKEN_BUCKET_SCRIPT = new DefaultRedisScript<>();
        TOKEN_BUCKET_SCRIPT.setLocation(new ClassPathResource("rate_limit_token_bucket.lua"));
        TOKEN_BUCKET_SCRIPT.setResultType(Long.class);
    }

    /**
     * 规则表。
     * <p>
     * 按顺序<b>全部</b>评估，而不是首个匹配就停——这样才能给同一路径叠加
     * 「IP 粗限流 + 用户细限流」两层。任意一条拒绝就整体拒绝。
     * <p>
     * ⚠️ 下面的阈值都是<b>占位值，未经压测</b>。生产环境必须按机器的实际
     * QPS 承载能力调整，拍脑袋设的阈值要么挡不住、要么误伤正常流量。
     */
    private static final List<RateLimitRule> RULES = Arrays.asList(
            // 秒杀：令牌桶。容量 5 允许小突发，长期速率 1 次/秒，按用户
            RateLimitRule.tokenBucket("seckill",
                    "/voucher-order/seckill/**", RateLimitRule.Dimension.USER, 5, 1),
            // 短信验证码：固定窗口 5 次/分钟，按 IP（防短信轰炸）
            RateLimitRule.fixedWindow("code",
                    "/user/code", RateLimitRule.Dimension.IP, 5, 60),
            // 全局兜底：固定窗口 100 次/秒，按 IP
            RateLimitRule.fixedWindow("api",
                    "/**", RateLimitRule.Dimension.IP, 100, 1)
    );

    public RateLimitInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        try {
            for (RateLimitRule rule : RULES) {
                if (!pathMatcher.match(rule.getPathPattern(), path)) {
                    continue;
                }
                if (!allow(rule, buildKey(rule, request))) {
                    reject(response, rule, path);
                    return false;
                }
            }
        } catch (Exception e) {
            // ⚠️ 这里是【有意为之】的 fail-open，不是漏写 try-catch。
            //    限流检查跑在每一个请求上，如果 Redis 不可用时让它抛出去，
            //    整个应用会全部 500 —— 那比"暂时没限流"严重得多。
            //    所以：放行本次请求，只记一条警告。可用性优先于保护性。
            //    （如果业务是"宁可拒绝也不能放行"，比如支付，才应该反过来 fail-close）
            log.warn("限流检查失败，放行本次请求：{}", path, e);
            return true;
        }
        return true;
    }

    /**
     * 构造限流 key：rate:{规则名}:{用户id 或 IP}。
     * <p>
     * 规则名参与拼接是必须的——否则同一维度下的不同规则会共用一个计数器，
     * 比如秒杀的令牌桶和全局的固定窗口都以 IP 为维度时会互相干扰。
     */
    private String buildKey(RateLimitRule rule, HttpServletRequest request) {
        if (rule.getDimension() == RateLimitRule.Dimension.USER) {
            UserDTO user = UserHolder.getUser();
            if (user != null) {
                return RedisConstants.RATE_LIMIT_KEY + rule.getName() + ":" + user.getId();
            }
            // 声明按用户但当前未登录 → 降级按 IP。
            // 不降级的话所有未登录用户会共用一个 key，互相挤占配额。
        }
        return RedisConstants.RATE_LIMIT_KEY + rule.getName() + ":" + getClientIp(request);
    }

    /**
     * 执行一次限流判断。返回 true 表示放行。
     */
    private boolean allow(RateLimitRule rule, String key) {
        Long result;
        if (rule.getAlgorithm() == RateLimitAlgorithm.FIXED_WINDOW) {
            result = stringRedisTemplate.execute(
                    FIXED_WINDOW_SCRIPT,
                    Collections.singletonList(key),
                    String.valueOf(rule.getLimit()),
                    String.valueOf(rule.getWindowSec()));
        } else {
            result = stringRedisTemplate.execute(
                    TOKEN_BUCKET_SCRIPT,
                    Collections.singletonList(key),
                    String.valueOf(rule.getCapacity()),
                    String.valueOf(rule.getRatePerSec()),
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(rule.getRequestedTokens()));
        }
        // 脚本返回 1 = 放行，0 = 被限流；返回 null 说明脚本没执行成功，按放行处理
        return result == null || result == 1L;
    }

    /**
     * 被限流时中断请求：返回 429 + JSON 响应体。
     * <p>
     * 参考 LoginInterceptor 的做法（它只用 response.setStatus(401) 不写 body）。
     * 这里多写一个 body，让前端/调用方拿到明确的失败原因。
     */
    private void reject(HttpServletResponse response, RateLimitRule rule, String path) throws IOException {
        log.warn("触发限流：rule={}, path={}", rule.getName(), path);
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JSONUtil.toJsonStr(Result.fail("请求过于频繁，请稍后再试")));
    }

    /**
     * 取客户端 IP。
     * <p>
     * ⚠️ 已知弱点：X-Forwarded-For 是客户端可以自己伪造的请求头。
     *    经过 nginx 时用它可以拿到真实 IP（否则 remoteAddr 拿到的只是 nginx 的 IP），
     *    但也意味着攻击者可以随便填一个假 IP 来绕过按 IP 的限流。
     *    生产环境应该只信任来自可信代理的 XFF（比如校验来源 IP 是否在网关网段内），
     *    或者干脆用 nginx 的 X-Real-IP 并做来源校验。这里作为学习用途先这么写。
     */
    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (StrUtil.isNotBlank(xForwardedFor)) {
            // XFF 可能是 "客户端IP, 代理1IP, 代理2IP"，第一个才是原始客户端
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
