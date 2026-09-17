package com.hmdp.ratelimit.core;

import com.hmdp.ratelimit.exception.RateLimitInfrastructureException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * 基于 Redis + Lua 的令牌桶限流器。
 *
 * <p>桶容量 capacity 控制最多允许积累多少突发额度，refillRatePerSecond 控制长期平均
 * 每秒补充多少令牌。Redis 中使用 milli-token（千分之一令牌）保存余额，使不足一个完整
 * 令牌的补充值不会因为频繁请求而丢失，同时避免直接持久化浮点数。</p>
 */
@Component
public class TokenBucketRateLimiter {

    /** Lua 5.1 使用 double 表示数字，超过 2^53 - 1 后不能保证整数精确。 */
    private static final long LUA_MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final long TOKEN_SCALE = 1000L;

    /** Lua 返回 1 表示成功扣除一个令牌，返回 0 表示当前令牌不足。 */
    private static final long ALLOWED = 1L;
    private static final long REJECTED = 0L;

    private static final DefaultRedisScript<Long> TOKEN_BUCKET_SCRIPT;

    static {
        TOKEN_BUCKET_SCRIPT = new DefaultRedisScript<>();
        TOKEN_BUCKET_SCRIPT.setLocation(new ClassPathResource("token_bucket.lua"));
        TOKEN_BUCKET_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate stringRedisTemplate;

    public TokenBucketRateLimiter(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 尝试从指定令牌桶中扣除一个令牌。
     *
     * @param key                 完整 Redis key；GLOBAL 限流应始终传同一个、不含 voucherId 的 key
     * @param capacity            桶最多可以保存的完整令牌数，决定允许的瞬时 burst
     * @param refillRatePerSecond 每秒补充的完整令牌数，决定长期平均进入速率
     * @return true 表示已扣除一个令牌并放行，false 表示正常触发限流
     * @throws IllegalArgumentException 参数不合法时抛出
     * @throws RateLimitInfrastructureException Redis、Lua 或脚本返回值异常时抛出；调用方应 fail-closed
     */
    public boolean tryAcquire(String key, long capacity, long refillRatePerSecond) {
        validateArguments(key, capacity, refillRatePerSecond);

        final Long result;
        try {
            // Lua 内部使用 Redis TIME，并原子完成补充、capacity 封顶、判断、扣减和 TTL 设置。
            result = stringRedisTemplate.execute(
                    TOKEN_BUCKET_SCRIPT,
                    Collections.singletonList(key),
                    Long.toString(capacity),
                    Long.toString(refillRatePerSecond)
            );
        } catch (RuntimeException redisFailure) {
            // 令牌桶不可用时不能把异常误判成“还有令牌”。秒杀 GLOBAL 限流按约束 fail-closed。
            throw new RateLimitInfrastructureException(
                    "Token bucket could not access Redis for key: " + key,
                    redisFailure
            );
        }

        if (result == null) {
            throw new RateLimitInfrastructureException("Token bucket Lua returned no result for key: " + key);
        }
        if (result == ALLOWED) {
            return true;
        }
        if (result == REJECTED) {
            return false;
        }
        throw new RateLimitInfrastructureException(
                "Token bucket Lua returned unexpected code " + result + " for key: " + key
        );
    }

    private void validateArguments(String key, long capacity, long refillRatePerSecond) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("Rate limit key must not be blank");
        }
        // Lua 会执行 capacity * 1000。这里提前约束，确保 milli-token 仍是精确整数。
        if (capacity <= 0 || capacity > LUA_MAX_SAFE_INTEGER / TOKEN_SCALE) {
            throw new IllegalArgumentException(
                    "Token bucket capacity must be between 1 and " + LUA_MAX_SAFE_INTEGER / TOKEN_SCALE
            );
        }
        if (refillRatePerSecond <= 0 || refillRatePerSecond > LUA_MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException(
                    "Token bucket refill rate must be between 1 and " + LUA_MAX_SAFE_INTEGER
            );
        }
    }
}
