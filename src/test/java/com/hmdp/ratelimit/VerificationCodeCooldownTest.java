package com.hmdp.ratelimit;

import com.hmdp.dto.Result;
import com.hmdp.ratelimit.exception.RateLimitExceededException;
import com.hmdp.ratelimit.exception.RateLimitInfrastructureException;
import com.hmdp.service.impl.UserServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VerificationCodeCooldownTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final UserServiceImpl userService = new UserServiceImpl();

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        ReflectionTestUtils.setField(userService, "stringRedisTemplate", redis);
    }

    @Test
    void validPhoneFirstRequestAcquiresCooldownBeforeSavingCode() {
        String phone = "13800138000";
        when(values.setIfAbsent(RedisConstants.LOGIN_CODE_COOLDOWN_KEY + phone,
                "1", 60L, TimeUnit.SECONDS)).thenReturn(true);

        Result result = userService.sendCode(phone, null);

        assertTrue(result.getSuccess());
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        InOrder order = inOrder(values);
        order.verify(values).setIfAbsent(RedisConstants.LOGIN_CODE_COOLDOWN_KEY + phone,
                "1", 60L, TimeUnit.SECONDS);
        order.verify(values).set(eq(RedisConstants.LOGIN_CODE_KEY + phone), code.capture(),
                eq(RedisConstants.LOGIN_CODE_TTL), eq(TimeUnit.MINUTES));
        assertTrue(code.getValue().matches("\\d{6}"), "应在取得 cooldown 后生成六位数字验证码");
    }

    @Test
    void repeatedRequestWithinSixtySecondsIsNormallyRateLimited() {
        String phone = "13800138000";
        when(values.setIfAbsent(anyString(), eq("1"), eq(60L), eq(TimeUnit.SECONDS)))
                .thenReturn(false);

        assertThrows(RateLimitExceededException.class,
                () -> userService.sendCode(phone, null));

        // 未取得 cooldown 时不能生成后续可用状态，也不能覆盖原验证码。
        verify(values, never()).set(eq(RedisConstants.LOGIN_CODE_KEY + phone),
                anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void differentPhonesUseIndependentCooldownKeys() {
        when(values.setIfAbsent(anyString(), eq("1"), eq(60L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);

        assertTrue(userService.sendCode("13800138000", null).getSuccess());
        assertTrue(userService.sendCode("13900139000", null).getSuccess());

        verify(values).setIfAbsent(RedisConstants.LOGIN_CODE_COOLDOWN_KEY + "13800138000",
                "1", 60L, TimeUnit.SECONDS);
        verify(values).setIfAbsent(RedisConstants.LOGIN_CODE_COOLDOWN_KEY + "13900139000",
                "1", 60L, TimeUnit.SECONDS);
    }

    @Test
    void invalidPhoneDoesNotCreateCooldownKey() {
        Result result = userService.sendCode("not-a-phone", null);

        assertFalse(result.getSuccess());
        verifyZeroInteractions(redis);
        verifyZeroInteractions(values);
    }

    @Test
    void cooldownRedisFailureOrNullResultFailsClosed() {
        when(values.setIfAbsent(anyString(), eq("1"), eq(60L), eq(TimeUnit.SECONDS)))
                .thenThrow(new RedisConnectionFailureException("test redis unavailable"));
        assertThrows(RateLimitInfrastructureException.class,
                () -> userService.sendCode("13800138000", null));

        reset(values);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), eq("1"), eq(60L), eq(TimeUnit.SECONDS)))
                .thenReturn(null);
        assertThrows(RateLimitInfrastructureException.class,
                () -> userService.sendCode("13800138000", null));
    }

    @Test
    void codeStorageFailureIsInfrastructureFailureAndDoesNotSendSuccess() {
        String phone = "13800138000";
        when(values.setIfAbsent(anyString(), eq("1"), eq(60L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        doThrow(new RedisConnectionFailureException("test code storage unavailable"))
                .when(values).set(eq(RedisConstants.LOGIN_CODE_KEY + phone),
                        anyString(), eq(RedisConstants.LOGIN_CODE_TTL), eq(TimeUnit.MINUTES));

        assertThrows(RateLimitInfrastructureException.class,
                () -> userService.sendCode(phone, null));
    }
}
