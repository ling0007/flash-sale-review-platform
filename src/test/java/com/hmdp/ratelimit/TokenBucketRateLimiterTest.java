package com.hmdp.ratelimit;

import com.hmdp.ratelimit.core.TokenBucketRateLimiter;
import com.hmdp.ratelimit.exception.RateLimitInfrastructureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TokenBucketRateLimiterTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private TokenBucketRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new TokenBucketRateLimiter(redis);
    }

    @SuppressWarnings("unchecked")
    private void luaReturns(String key, long capacity, long refillRate, Long result) {
        when(redis.execute(
                any(RedisScript.class),
                eq(Collections.singletonList(key)),
                eq(Long.toString(capacity)),
                eq(Long.toString(refillRate))
        )).thenReturn(result);
    }

    @Test
    void returnCodeOneMeansTokenWasDeducted() {
        luaReturns("rate_limit:test:global", 10, 5, 1L);

        assertTrue(rateLimiter.tryAcquire("rate_limit:test:global", 10, 5));
    }

    @Test
    void returnCodeZeroMeansBucketIsNormallyExhausted() {
        luaReturns("rate_limit:test:global", 10, 5, 0L);

        assertFalse(rateLimiter.tryAcquire("rate_limit:test:global", 10, 5));
    }

    @Test
    void redisFailureIsWrappedAndMustNotFailOpen() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString()))
                .thenThrow(new RedisConnectionFailureException("test redis unavailable"));

        RateLimitInfrastructureException failure = assertThrows(
                RateLimitInfrastructureException.class,
                () -> rateLimiter.tryAcquire("rate_limit:test:global", 10, 5)
        );
        assertTrue(failure.getCause() instanceof RedisConnectionFailureException);
    }

    @Test
    void nullOrUnknownLuaResultIsInfrastructureFailure() {
        luaReturns("rate_limit:test:null", 10, 5, null);
        assertThrows(RateLimitInfrastructureException.class,
                () -> rateLimiter.tryAcquire("rate_limit:test:null", 10, 5));

        luaReturns("rate_limit:test:unknown", 10, 5, 2L);
        assertThrows(RateLimitInfrastructureException.class,
                () -> rateLimiter.tryAcquire("rate_limit:test:unknown", 10, 5));
    }

    @Test
    void invalidArgumentsAreRejectedBeforeCallingRedis() {
        assertThrows(IllegalArgumentException.class, () -> rateLimiter.tryAcquire(" ", 10, 5));
        assertThrows(IllegalArgumentException.class, () -> rateLimiter.tryAcquire("key", 0, 5));
        assertThrows(IllegalArgumentException.class, () -> rateLimiter.tryAcquire("key", 10, 0));
        assertThrows(IllegalArgumentException.class,
                () -> rateLimiter.tryAcquire("key", 9_007_199_254_741L, 5));
        assertThrows(IllegalArgumentException.class,
                () -> rateLimiter.tryAcquire("key", 10, 9_007_199_254_740_992L));

        verifyZeroInteractions(redis);
    }
}
