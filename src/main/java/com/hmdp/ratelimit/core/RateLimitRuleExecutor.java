package com.hmdp.ratelimit.core;

import com.hmdp.ratelimit.annotation.RateLimit;
import com.hmdp.ratelimit.exception.RateLimitExceededException;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;

/**
 * 一条注解规则的执行器。
 *
 * <p>Aspect 只负责找到并排序规则；key 解析和算法选择集中在这里，避免切面逐渐堆积业务逻辑。
 * 这种拆分也让核心执行器可以脱离 AOP 单独进行单元测试。</p>
 */
@Component
public class RateLimitRuleExecutor {

    private final RateLimitKeyResolver keyResolver;
    private final SlidingWindowCounterRateLimiter slidingWindowCounterRateLimiter;
    private final TokenBucketRateLimiter tokenBucketRateLimiter;

    public RateLimitRuleExecutor(RateLimitKeyResolver keyResolver,
                                 SlidingWindowCounterRateLimiter slidingWindowCounterRateLimiter,
                                 TokenBucketRateLimiter tokenBucketRateLimiter) {
        this.keyResolver = keyResolver;
        this.slidingWindowCounterRateLimiter = slidingWindowCounterRateLimiter;
        this.tokenBucketRateLimiter = tokenBucketRateLimiter;
    }

    public void check(RateLimit rule, Object[] arguments, HttpServletRequest request) {
        // 注解是静态配置，用户、IP、voucherId 是运行时数据；Resolver 负责把两部分组合成 Redis key。
        String key = keyResolver.resolve(rule, arguments, request);
        final boolean allowed;

        // AOP 不关心 Redis 和 Lua 细节，只根据注解声明把规则交给对应算法执行器。
        switch (rule.algorithm()) {
            case SLIDING_WINDOW_COUNTER:
                allowed = slidingWindowCounterRateLimiter.tryAcquire(
                        key, rule.limit(), rule.windowMillis());
                break;
            case TOKEN_BUCKET:
                allowed = tokenBucketRateLimiter.tryAcquire(
                        key, rule.capacity(), rule.refillRatePerSecond());
                break;
            default:
                throw new IllegalArgumentException("Unsupported rate limit algorithm: " + rule.algorithm());
        }

        if (!allowed) {
            /*
             * 这里不能直接返回 Result，因为当前方法不是 Controller，也不知道目标方法原本的返回类型。
             * 抛出专用异常可以立即中断 AOP 调用链，并由全局异常处理统一转换为 HTTP 429（Phase 7）。
             */
            throw new RateLimitExceededException("Rate limit exceeded for resource: " + rule.resourceKey());
        }
    }
}
