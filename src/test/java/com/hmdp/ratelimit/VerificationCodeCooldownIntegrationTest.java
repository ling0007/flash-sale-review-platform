package com.hmdp.ratelimit;

import com.hmdp.ratelimit.exception.RateLimitExceededException;
import com.hmdp.service.impl.UserServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 设置 RUN_RATE_LIMIT_INTEGRATION=true 后，使用 127.0.0.1:6380 验证真实 Redis NX 与 TTL。 */
@EnabledIfEnvironmentVariable(named = "RUN_RATE_LIMIT_INTEGRATION", matches = "true")
class VerificationCodeCooldownIntegrationTest {

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private UserServiceImpl userService;
    private final Set<String> testKeys = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void setUp() {
        String host = System.getenv().getOrDefault("REDIS_HOST", "127.0.0.1");
        int port = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6380"));
        connectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();

        userService = new UserServiceImpl();
        ReflectionTestUtils.setField(userService, "stringRedisTemplate", redis);
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
    void firstRequestCreatesCooldownAndCodeWithExpectedTtl() {
        String phone = newPhone();

        assertTrue(userService.sendCode(phone, null).getSuccess());

        String cooldownKey = cooldownKey(phone);
        String codeKey = codeKey(phone);
        assertEquals("1", redis.opsForValue().get(cooldownKey));
        String storedCode = redis.opsForValue().get(codeKey);
        assertNotNull(storedCode);
        assertTrue(storedCode.matches("\\d{6}"));
        Long cooldownTtl = redis.getExpire(cooldownKey, TimeUnit.SECONDS);
        assertNotNull(cooldownTtl);
        assertTrue(cooldownTtl > 0 && cooldownTtl <= 60);
    }

    @Test
    void secondRequestCannotReplaceCodeDuringCooldown() {
        String phone = newPhone();
        assertTrue(userService.sendCode(phone, null).getSuccess());
        String firstCode = redis.opsForValue().get(codeKey(phone));

        assertThrows(RateLimitExceededException.class,
                () -> userService.sendCode(phone, null));

        assertEquals(firstCode, redis.opsForValue().get(codeKey(phone)));
    }

    @Test
    void differentPhonesHaveIndependentCooldownState() {
        String firstPhone = newPhone();
        String secondPhone = newPhone();

        assertTrue(userService.sendCode(firstPhone, null).getSuccess());
        assertTrue(userService.sendCode(secondPhone, null).getSuccess());
        assertTrue(Boolean.TRUE.equals(redis.hasKey(cooldownKey(firstPhone))));
        assertTrue(Boolean.TRUE.equals(redis.hasKey(cooldownKey(secondPhone))));
    }

    @Test
    void invalidPhoneDoesNotCreateCooldownOrCodeKey() {
        String invalidPhone = "invalid-" + ThreadLocalRandom.current().nextInt(100_000);

        assertFalse(userService.sendCode(invalidPhone, null).getSuccess());

        String cooldownKey = RedisConstants.LOGIN_CODE_COOLDOWN_KEY + invalidPhone;
        String codeKey = RedisConstants.LOGIN_CODE_KEY + invalidPhone;
        testKeys.add(cooldownKey);
        testKeys.add(codeKey);
        assertFalse(Boolean.TRUE.equals(redis.hasKey(cooldownKey)));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(codeKey)));
    }

    private String newPhone() {
        // 生成符合项目手机号正则的随机测试号码，避免碰撞真实开发数据。
        String phone = "139" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
        testKeys.add(RedisConstants.LOGIN_CODE_COOLDOWN_KEY + phone);
        testKeys.add(RedisConstants.LOGIN_CODE_KEY + phone);
        return phone;
    }

    private String cooldownKey(String phone) {
        String key = RedisConstants.LOGIN_CODE_COOLDOWN_KEY + phone;
        testKeys.add(key);
        return key;
    }

    private String codeKey(String phone) {
        String key = RedisConstants.LOGIN_CODE_KEY + phone;
        testKeys.add(key);
        return key;
    }
}
