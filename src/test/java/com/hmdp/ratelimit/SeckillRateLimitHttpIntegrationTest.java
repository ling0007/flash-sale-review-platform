package com.hmdp.ratelimit;

import com.hmdp.config.WebExceptionAdvice;
import com.hmdp.controller.VoucherOrderController;
import com.hmdp.dto.Result;
import com.hmdp.ratelimit.aspect.RateLimitAspect;
import com.hmdp.ratelimit.core.RateLimitKeyResolver;
import com.hmdp.ratelimit.core.RateLimitRuleExecutor;
import com.hmdp.ratelimit.core.SlidingWindowCounterRateLimiter;
import com.hmdp.ratelimit.core.TokenBucketRateLimiter;
import com.hmdp.ratelimit.exception.RateLimitInfrastructureException;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RefreshTokenInterceptor;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证限流各层组合后的真实 HTTP 行为。
 *
 * <p>算法公式、Redis Lua、Aspect 内部顺序和 Advice 单独映射已经分别测试过；这里不重复那些断言，
 * 只覆盖此前缺少的组合边界：请求依次经过登录信息刷新拦截器、登录校验拦截器、
 * DispatcherServlet 和 AOP 代理，异常再由全局 Advice 转换成 HTTP 响应。</p>
 */
class SeckillRateLimitHttpIntegrationTest {

    private final SlidingWindowCounterRateLimiter sliding = mock(SlidingWindowCounterRateLimiter.class);
    private final TokenBucketRateLimiter tokenBucket = mock(TokenBucketRateLimiter.class);
    private final IVoucherOrderService orderService = mock(IVoucherOrderService.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final HashOperations<String, Object, Object> loginHashes = mock(HashOperations.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // 使用真实 RefreshToken/Login 拦截器建立 UserHolder，不在测试里手工伪造登录上下文。
        Map<Object, Object> userMap = new HashMap<>();
        userMap.put("id", "42");
        userMap.put("nickName", "phase8-test-user");
        when(redis.opsForHash()).thenReturn(loginHashes);
        when(loginHashes.entries(RedisConstants.LOGIN_USER_KEY + "test-token")).thenReturn(userMap);
        when(redis.expire(RedisConstants.LOGIN_USER_KEY + "test-token",
                RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES)).thenReturn(true);

        VoucherOrderController target = new VoucherOrderController();
        ReflectionTestUtils.setField(target, "voucherOrderService", orderService);

        RateLimitRuleExecutor executor = new RateLimitRuleExecutor(
                new RateLimitKeyResolver(), sliding, tokenBucket);
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
        proxyFactory.addAspect(new RateLimitAspect(executor));
        VoucherOrderController proxy = proxyFactory.getProxy();

        // standaloneSetup 仍会真实经过 Spring MVC 参数绑定、代理调用、异常解析和 JSON 序列化，
        // 但不会启动 MySQL、RabbitMQ 或 Redis，适合只验证 Web/AOP 组合边界。
        mvc = MockMvcBuilders.standaloneSetup(proxy)
                .setControllerAdvice(new WebExceptionAdvice())
                // 注册顺序与 MvcConfig 一致：先刷新并写入 UserHolder，再执行登录校验。
                .addInterceptors(new RefreshTokenInterceptor(redis), new LoginInterceptor())
                .build();
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void userLimitBecomesHttp429AndNeverEntersSeckillService() throws Exception {
        when(tokenBucket.tryAcquire(anyString(), eq(1500L), eq(1000L))).thenReturn(true);
        when(sliding.tryAcquire(anyString(), eq(5L), eq(1000L))).thenReturn(false);

        mvc.perform(post("/voucher-order/seckill/7").header("authorization", "test-token"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("请求过于频繁，请稍后再试"));

        // Service 零调用意味着其中的订单 ID、seckill.lua 和 RabbitMQ 都没有执行。
        verifyZeroInteractions(orderService);
        verify(sliding).tryAcquire(
                "rate_limit:sliding_window_counter:voucher-order:seckill:user:42:arg:7", 5, 1000);
    }

    @Test
    void infrastructureFailureBecomesHttp503AndNeverEntersSeckillService() throws Exception {
        when(tokenBucket.tryAcquire(anyString(), eq(1500L), eq(1000L)))
                .thenThrow(new RateLimitInfrastructureException("test redis unavailable"));

        mvc.perform(post("/voucher-order/seckill/7").header("authorization", "test-token"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("限流服务暂时不可用，请稍后重试"));

        verifyZeroInteractions(orderService);
    }

    @Test
    void allRulesPassingReachesControllerAndReturnsOriginalBusinessResult() throws Exception {
        when(tokenBucket.tryAcquire(anyString(), eq(1500L), eq(1000L))).thenReturn(true);
        when(sliding.tryAcquire(anyString(), eq(5L), eq(1000L))).thenReturn(true);
        when(orderService.seckillVoucher(7L)).thenReturn(Result.ok(99L));

        mvc.perform(post("/voucher-order/seckill/7").header("authorization", "test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(99));

        verify(orderService).seckillVoucher(7L);
    }

    @Test
    void missingTokenIsRejectedByLoginInterceptorBeforeRateLimitAspect() throws Exception {
        mvc.perform(post("/voucher-order/seckill/7"))
                .andExpect(status().isUnauthorized());

        verifyZeroInteractions(tokenBucket);
        verifyZeroInteractions(sliding);
        verifyZeroInteractions(orderService);
    }
}
