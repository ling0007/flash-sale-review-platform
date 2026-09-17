package com.hmdp.ratelimit.core;

import com.hmdp.ratelimit.exception.RateLimitInfrastructureException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * 基于 Redis + Lua 的滑动窗口计数器。
 *
 * <p>每个限流 key 只保存上一窗口计数、当前窗口计数和当前窗口起点，不保存逐请求时间戳。
 * Lua 会使用 Redis 服务器时间完成窗口切换、加权估算、阈值判断和计数，因此多个应用实例
 * 共享同一时间来源，并且单个限流 key 的状态变更具有原子性。</p>
 */
@Component
public class SlidingWindowCounterRateLimiter {

    /** Lua 5.1 使用 double 表示数字，超过 2^53 - 1 后不能保证整数精确。 */
    private static final long LUA_MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    /**
     * Lua 返回 1 表示本次请求已被计入当前窗口并允许继续执行。
     * Lua 返回 0 表示加入本次请求后的加权估算值会超过 limit，因此拒绝且不计数。
     */
    private static final long ALLOWED = 1L;
    private static final long REJECTED = 0L;

    private static final DefaultRedisScript<Long> SLIDING_WINDOW_COUNTER_SCRIPT;

    static {
        SLIDING_WINDOW_COUNTER_SCRIPT = new DefaultRedisScript<>();
        SLIDING_WINDOW_COUNTER_SCRIPT.setLocation(new ClassPathResource("sliding_window_counter.lua"));
        SLIDING_WINDOW_COUNTER_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate stringRedisTemplate;

    public SlidingWindowCounterRateLimiter(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 尝试为一个请求获取滑动窗口额度。
     *
     * @param key          已包含业务资源和限流身份的完整 Redis key
     * @param limit        一个窗口内允许的加权请求上限
     * @param windowMillis 窗口长度，统一使用毫秒
     * @return true 表示放行，false 表示正常触发限流
     * @throws IllegalArgumentException 参数不合法时抛出
     * @throws RateLimitInfrastructureException Redis、Lua 或脚本返回值异常时抛出；调用方应 fail-closed
     */
    public boolean tryAcquire(String key, long limit, long windowMillis) {
        validateArguments(key, limit, windowMillis);

        final Long result;
        try {
            // 只传一个 KEYS，脚本内的读取、判断、计数和 TTL 设置都作用于同一个 Hash。
            result = stringRedisTemplate.execute(
                    SLIDING_WINDOW_COUNTER_SCRIPT,
                    Collections.singletonList(key),
                    Long.toString(limit),
                    Long.toString(windowMillis)
            );
        } catch (RuntimeException redisFailure) {
            // 这里不降级为放行。秒杀后续同样依赖 Redis，盲目放行只会扩大故障期间的流量。
            throw new RateLimitInfrastructureException(
                    "Sliding window counter could not access Redis for key: " + key,
                    redisFailure
            );
        }

        if (result == null) {
            throw new RateLimitInfrastructureException(
                    "Sliding window counter Lua returned no result for key: " + key
            );
        }
        if (result == ALLOWED) {
            return true;
        }
        if (result == REJECTED) {
            return false;
        }
        throw new RateLimitInfrastructureException(
                "Sliding window counter Lua returned unexpected code " + result + " for key: " + key
        );
    }

    private void validateArguments(String key, long limit, long windowMillis) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("Rate limit key must not be blank");
        }
        if (limit <= 0 || limit > LUA_MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException("Rate limit must be between 1 and " + LUA_MAX_SAFE_INTEGER);
        }
        // Lua 中 TTL = windowMillis * 2，提前限制范围可避免乘法越过安全整数边界。
        if (windowMillis <= 0 || windowMillis > LUA_MAX_SAFE_INTEGER / 2) {
            throw new IllegalArgumentException(
                    "Window milliseconds must be between 1 and " + LUA_MAX_SAFE_INTEGER / 2
            );
        }
    }
}
