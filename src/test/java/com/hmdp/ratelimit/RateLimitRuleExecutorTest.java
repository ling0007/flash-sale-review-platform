package com.hmdp.ratelimit;

import com.hmdp.ratelimit.annotation.RateLimit;
import com.hmdp.ratelimit.core.RateLimitKeyResolver;
import com.hmdp.ratelimit.core.RateLimitRuleExecutor;
import com.hmdp.ratelimit.core.SlidingWindowCounterRateLimiter;
import com.hmdp.ratelimit.core.TokenBucketRateLimiter;
import com.hmdp.ratelimit.enums.RateLimitAlgorithm;
import com.hmdp.ratelimit.enums.RateLimitDimension;
import com.hmdp.ratelimit.exception.RateLimitExceededException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class RateLimitRuleExecutorTest {

    private final RateLimitKeyResolver keyResolver = mock(RateLimitKeyResolver.class);
    private final SlidingWindowCounterRateLimiter sliding = mock(SlidingWindowCounterRateLimiter.class);
    private final TokenBucketRateLimiter tokenBucket = mock(TokenBucketRateLimiter.class);
    private final RateLimitRuleExecutor executor = new RateLimitRuleExecutor(keyResolver, sliding, tokenBucket);

    @Test
    void slidingWindowRuleUsesLimitAndWindow() throws Exception {
        RateLimit rule = rule("sliding");
        when(keyResolver.resolve(eq(rule), any(), isNull())).thenReturn("sliding-key");
        when(sliding.tryAcquire("sliding-key", 5, 1000)).thenReturn(true);

        executor.check(rule, new Object[]{7L}, null);

        verify(sliding).tryAcquire("sliding-key", 5, 1000);
        verifyZeroInteractions(tokenBucket);
    }

    @Test
    void tokenBucketRuleUsesCapacityAndRefillRate() throws Exception {
        RateLimit rule = rule("tokenBucket");
        when(keyResolver.resolve(eq(rule), any(), isNull())).thenReturn("bucket-key");
        when(tokenBucket.tryAcquire("bucket-key", 1500, 1000)).thenReturn(true);

        executor.check(rule, new Object[]{7L}, null);

        verify(tokenBucket).tryAcquire("bucket-key", 1500, 1000);
        verifyZeroInteractions(sliding);
    }

    @Test
    void normalExhaustionBecomesDedicatedRateLimitException() throws Exception {
        RateLimit rule = rule("tokenBucket");
        when(keyResolver.resolve(eq(rule), any(), isNull())).thenReturn("bucket-key");
        when(tokenBucket.tryAcquire("bucket-key", 1500, 1000)).thenReturn(false);

        assertThrows(RateLimitExceededException.class,
                () -> executor.check(rule, new Object[]{7L}, null));
    }

    private RateLimit rule(String methodName) throws Exception {
        return Fixture.class.getDeclaredMethod(methodName, Long.class).getAnnotation(RateLimit.class);
    }

    private static class Fixture {
        @RateLimit(dimension = RateLimitDimension.USER,
                algorithm = RateLimitAlgorithm.SLIDING_WINDOW_COUNTER,
                resourceKey = "test", limit = 5, windowMillis = 1000)
        void sliding(Long voucherId) {
        }

        @RateLimit(dimension = RateLimitDimension.GLOBAL,
                algorithm = RateLimitAlgorithm.TOKEN_BUCKET,
                resourceKey = "test", capacity = 1500, refillRatePerSecond = 1000)
        void tokenBucket(Long voucherId) {
        }
    }
}
