package com.hmdp.ratelimit;

import com.hmdp.ratelimit.core.SlidingWindowCounterRateLimiter;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实执行 Redis Lua 的集成测试。显式设置 RUN_RATE_LIMIT_INTEGRATION=true 后运行，
 * 默认连接 127.0.0.1:6380，也可通过 REDIS_HOST、REDIS_PORT 指定测试 Redis。
 */
@EnabledIfEnvironmentVariable(named = "RUN_RATE_LIMIT_INTEGRATION", matches = "true")
class SlidingWindowCounterRateLimiterIntegrationTest {

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private SlidingWindowCounterRateLimiter rateLimiter;
    private final Set<String> testKeys = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void setUp() {
        String host = System.getenv().getOrDefault("REDIS_HOST", "127.0.0.1");
        int port = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6380"));
        connectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
        connectionFactory.afterPropertiesSet();

        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        rateLimiter = new SlidingWindowCounterRateLimiter(redis);
    }

    @AfterEach
    void tearDown() {
        if (redis != null && !testKeys.isEmpty()) {
            redis.delete(testKeys);
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void currentWindowCountsAllowedRequestsAndSetsTtl() {
        String key = newKey("current");

        assertTrue(rateLimiter.tryAcquire(key, 2, 5000));
        assertTrue(rateLimiter.tryAcquire(key, 2, 5000));
        assertFalse(rateLimiter.tryAcquire(key, 2, 5000));

        assertEquals("2", redis.<String, String>opsForHash().get(key, "current_count"));
        assertEquals("0", redis.<String, String>opsForHash().get(key, "previous_count"));
        Long ttl = redis.getExpire(key, TimeUnit.MILLISECONDS);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 10_000, "TTL 应处于 0 到两倍窗口长度之间");
    }

    @Test
    void previousWindowWeightDecaysAfterWindowSwitch() throws Exception {
        long windowMillis = 2000;
        String key = newKey("weighted");

        // 等到窗口刚开始，构造“上一窗口放行 4 次、当前窗口为 0”的状态。
        long now = waitUntilWindowPhase(windowMillis, 0, 250);
        long currentWindowStart = now - now % windowMillis;
        redis.opsForHash().put(key, "current_window_start_ms", Long.toString(currentWindowStart - windowMillis));
        redis.opsForHash().put(key, "previous_count", "0");
        redis.opsForHash().put(key, "current_count", "4");
        redis.expire(key, Duration.ofSeconds(10));

        // 窗口刚切换时上一窗口权重接近 1，4 * 权重 + 0 + 本次请求会超过 limit=3。
        assertFalse(rateLimiter.tryAcquire(key, 3, windowMillis));
        assertEquals("4", redis.<String, String>opsForHash().get(key, "previous_count"));
        assertEquals("0", redis.<String, String>opsForHash().get(key, "current_count"));

        // 当前窗口过去 60% 后，上一窗口只剩约 40% 权重：4 * 0.4 + 0 + 1 <= 3。
        waitUntilWindowPhase(windowMillis, 1200, 1600);
        assertTrue(rateLimiter.tryAcquire(key, 3, windowMillis));
        assertEquals("1", redis.<String, String>opsForHash().get(key, "current_count"));
    }

    @Test
    void usersAndVouchersUseIndependentKeys() {
        String user42Voucher7 = newKey("voucher:7:user:42");
        String user42Voucher8 = newKey("voucher:8:user:42");
        String user43Voucher7 = newKey("voucher:7:user:43");

        assertTrue(rateLimiter.tryAcquire(user42Voucher7, 1, 5000));
        assertFalse(rateLimiter.tryAcquire(user42Voucher7, 1, 5000));
        assertTrue(rateLimiter.tryAcquire(user42Voucher8, 1, 5000));
        assertTrue(rateLimiter.tryAcquire(user43Voucher7, 1, 5000));
    }

    @Test
    void inactiveKeyExpiresAfterTwoWindows() {
        String key = newKey("expiry");

        assertTrue(rateLimiter.tryAcquire(key, 1, 150));
        assertTrue(Boolean.TRUE.equals(redis.hasKey(key)));

        Awaitility.await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertFalse(Boolean.TRUE.equals(redis.hasKey(key))));
    }

    @Test
    void concurrentRequestsAreCountedAtomically() throws Exception {
        String key = newKey("concurrent");
        int requests = 100;
        int limit = 20;
        ExecutorService pool = Executors.newFixedThreadPool(20);
        AtomicInteger allowed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < requests; i++) {
                futures.add(pool.submit(() -> {
                    if (rateLimiter.tryAcquire(key, limit, 60_000)) {
                        allowed.incrementAndGet();
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }

        assertEquals(limit, allowed.get());
        assertEquals(Integer.toString(limit),
                redis.<String, String>opsForHash().get(key, "current_count"));
    }

    private String newKey(String suffix) {
        String key = "rate_limit:test:sliding_window:" + suffix + ":" + UUID.randomUUID();
        testKeys.add(key);
        return key;
    }

    private long redisTimeMillis() {
        Long value = redis.execute((RedisCallback<Long>) connection -> connection.time());
        if (value == null) {
            throw new IllegalStateException("Redis TIME returned null during integration test");
        }
        return value;
    }

    private long waitUntilWindowPhase(long windowMillis, long minimumPhase, long maximumPhase)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            long now = redisTimeMillis();
            long phase = now % windowMillis;
            if (phase >= minimumPhase && phase <= maximumPhase) {
                return now;
            }
            Thread.sleep(10);
        }
        fail("未能在等待时间内进入指定窗口区间");
        return 0;
    }
}
