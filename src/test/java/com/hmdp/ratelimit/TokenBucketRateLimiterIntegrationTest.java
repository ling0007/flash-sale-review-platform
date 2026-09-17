package com.hmdp.ratelimit;

import com.hmdp.ratelimit.core.TokenBucketRateLimiter;
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
 * 真实执行 Redis Token Bucket Lua 的集成测试。
 * 设置 RUN_RATE_LIMIT_INTEGRATION=true 后运行，默认连接 127.0.0.1:6380。
 */
@EnabledIfEnvironmentVariable(named = "RUN_RATE_LIMIT_INTEGRATION", matches = "true")
class TokenBucketRateLimiterIntegrationTest {

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private TokenBucketRateLimiter rateLimiter;
    private final Set<String> testKeys = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void setUp() {
        String host = System.getenv().getOrDefault("REDIS_HOST", "127.0.0.1");
        int port = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6380"));
        connectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
        connectionFactory.afterPropertiesSet();

        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        rateLimiter = new TokenBucketRateLimiter(redis);
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
    void newBucketStartsFullThenDeductsUntilExhausted() {
        String key = newKey("initial-capacity");

        assertTrue(rateLimiter.tryAcquire(key, 3, 1));
        assertTrue(rateLimiter.tryAcquire(key, 3, 1));
        assertTrue(rateLimiter.tryAcquire(key, 3, 1));
        assertFalse(rateLimiter.tryAcquire(key, 3, 1));

        long remainingMilliTokens = Long.parseLong(
                redis.<String, String>opsForHash().get(key, "tokens_milli")
        );
        // 连续请求之间仍会经过几毫秒并产生少量补充；只要不足 1000，就不能再放行完整请求。
        assertTrue(remainingMilliTokens >= 0 && remainingMilliTokens < 1000);
        Long ttl = redis.getExpire(key, TimeUnit.MILLISECONDS);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 3000, "TTL 不应超过空桶恢复到满桶的时间");
    }

    @Test
    void tokensRefillWithMillisecondPrecisionAndNeverExceedCapacity() throws Exception {
        String key = newKey("refill");

        assertTrue(rateLimiter.tryAcquire(key, 2, 2));
        assertTrue(rateLimiter.tryAcquire(key, 2, 2));
        assertFalse(rateLimiter.tryAcquire(key, 2, 2));

        // refillRate=2 token/s，约 500ms 产生一个完整令牌；保留更宽余量降低调度抖动影响。
        Thread.sleep(650);
        assertTrue(rateLimiter.tryAcquire(key, 2, 2));
        assertFalse(rateLimiter.tryAcquire(key, 2, 2));

        // 手工构造长 elapsed，脚本必须先把余额封顶到 capacity，再扣除本次的一个令牌。
        long now = redisTimeMillis();
        redis.opsForHash().put(key, "tokens_milli", "0");
        redis.opsForHash().put(key, "last_refill_ms", Long.toString(now - 60_000));
        redis.expire(key, Duration.ofSeconds(10));
        assertTrue(rateLimiter.tryAcquire(key, 2, 2));
        assertEquals("1000", redis.<String, String>opsForHash().get(key, "tokens_milli"));
    }

    @Test
    void inactiveBucketExpiresAndNextRequestStartsFromFullCapacity() {
        String key = newKey("idle-recovery");

        assertTrue(rateLimiter.tryAcquire(key, 2, 10));
        assertTrue(rateLimiter.tryAcquire(key, 2, 10));
        assertFalse(rateLimiter.tryAcquire(key, 2, 10));

        // TTL 至少为 1 秒；过期时桶在逻辑上早已补满，删除状态不会额外放大 burst。
        Awaitility.await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertFalse(Boolean.TRUE.equals(redis.hasKey(key))));

        assertTrue(rateLimiter.tryAcquire(key, 2, 10));
        assertEquals("1000", redis.<String, String>opsForHash().get(key, "tokens_milli"));
    }

    @Test
    void concurrentRequestsCannotOverdrawBucket() throws Exception {
        String key = newKey("concurrent");
        int requests = 100;
        int capacity = 20;
        int refillRate = 1;
        ExecutorService pool = Executors.newFixedThreadPool(20);
        AtomicInteger allowed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        long startedAt = redisTimeMillis();

        try {
            for (int i = 0; i < requests; i++) {
                futures.add(pool.submit(() -> {
                    if (rateLimiter.tryAcquire(key, capacity, refillRate)) {
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

        long elapsedMillis = redisTimeMillis() - startedAt;
        long maximumPossible = capacity + elapsedMillis * refillRate / 1000;
        assertTrue(allowed.get() >= capacity, "初始满桶至少应允许 capacity 个请求");
        assertTrue(allowed.get() <= maximumPossible,
                "并发放行数不能超过初始容量加测试期间实际补充出的完整令牌");
    }

    @Test
    void oneGlobalKeyIsSharedByAllVoucherIds() {
        String globalKey = newKey("seckill-global");

        // 三次调用可理解为来自三个不同 voucherId；GLOBAL 规则故意不把 voucherId 拼入 key。
        assertTrue(acquireForVoucher(globalKey, 7L));
        assertTrue(acquireForVoucher(globalKey, 8L));
        assertFalse(acquireForVoucher(globalKey, 9L));
    }

    private boolean acquireForVoucher(String globalKey, long ignoredVoucherId) {
        return rateLimiter.tryAcquire(globalKey, 2, 1);
    }

    private String newKey(String suffix) {
        String key = "rate_limit:test:token_bucket:" + suffix + ":" + UUID.randomUUID();
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
}
