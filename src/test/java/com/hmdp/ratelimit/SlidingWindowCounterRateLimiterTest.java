package com.hmdp.ratelimit;

import com.hmdp.ratelimit.core.SlidingWindowCounterRateLimiter;
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

class SlidingWindowCounterRateLimiterTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private SlidingWindowCounterRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new SlidingWindowCounterRateLimiter(redis);
    }

    @SuppressWarnings("unchecked")
    private void luaReturns(String key, long limit, long windowMillis, Long result) {
        when(redis.execute(
                any(RedisScript.class),
                eq(Collections.singletonList(key)),
                eq(Long.toString(limit)),
                eq(Long.toString(windowMillis))
        )).thenReturn(result);
    }

    @Test
    void returnCodeOneMeansAllowed() {
        luaReturns("rate_limit:test", 5, 1000, 1L);

        assertTrue(rateLimiter.tryAcquire("rate_limit:test", 5, 1000));
    }

    @Test
    void returnCodeZeroMeansNormallyRejected() {
        luaReturns("rate_limit:test", 5, 1000, 0L);

        assertFalse(rateLimiter.tryAcquire("rate_limit:test", 5, 1000));
    }

    @Test
    void redisFailureIsWrappedAndMustNotFailOpen() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString()))
                .thenThrow(new RedisConnectionFailureException("test redis unavailable"));

        RateLimitInfrastructureException failure = assertThrows(
                RateLimitInfrastructureException.class,
                () -> rateLimiter.tryAcquire("rate_limit:test", 5, 1000)
        );
        assertTrue(failure.getCause() instanceof RedisConnectionFailureException);
    }

    @Test
    void nullOrUnknownLuaResultIsInfrastructureFailure() {
        luaReturns("rate_limit:null", 5, 1000, null);
        assertThrows(RateLimitInfrastructureException.class,
                () -> rateLimiter.tryAcquire("rate_limit:null", 5, 1000));

        luaReturns("rate_limit:unknown", 5, 1000, 2L);
        assertThrows(RateLimitInfrastructureException.class,
                () -> rateLimiter.tryAcquire("rate_limit:unknown", 5, 1000));
    }

    @Test
    void invalidArgumentsAreRejectedBeforeCallingRedis() {
        assertThrows(IllegalArgumentException.class, () -> rateLimiter.tryAcquire(" ", 5, 1000));
        assertThrows(IllegalArgumentException.class, () -> rateLimiter.tryAcquire("key", 0, 1000));
        assertThrows(IllegalArgumentException.class, () -> rateLimiter.tryAcquire("key", 5, 0));
        assertThrows(IllegalArgumentException.class,
                () -> rateLimiter.tryAcquire("key", 5, 9_007_199_254_740_991L));

        verifyZeroInteractions(redis);
    }
}
