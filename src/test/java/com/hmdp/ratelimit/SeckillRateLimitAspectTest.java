package com.hmdp.ratelimit;

import com.hmdp.controller.VoucherOrderController;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.ratelimit.aspect.RateLimitAspect;
import com.hmdp.ratelimit.core.RateLimitKeyResolver;
import com.hmdp.ratelimit.core.RateLimitRuleExecutor;
import com.hmdp.ratelimit.core.SlidingWindowCounterRateLimiter;
import com.hmdp.ratelimit.core.TokenBucketRateLimiter;
import com.hmdp.ratelimit.exception.RateLimitExceededException;
import com.hmdp.ratelimit.exception.RateLimitInfrastructureException;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class SeckillRateLimitAspectTest {

    private final SlidingWindowCounterRateLimiter sliding = mock(SlidingWindowCounterRateLimiter.class);
    private final TokenBucketRateLimiter tokenBucket = mock(TokenBucketRateLimiter.class);
    private final IVoucherOrderService orderService = mock(IVoucherOrderService.class);
    private VoucherOrderController controllerProxy;

    @BeforeEach
    void setUp() {
        UserDTO user = new UserDTO();
        user.setId(42L);
        UserHolder.saveUser(user);

        VoucherOrderController target = new VoucherOrderController();
        ReflectionTestUtils.setField(target, "voucherOrderService", orderService);

        /*
         * 测试中不启动整个 Spring Boot，而是手工创建一个与 Spring 运行原理相同的 AOP 代理：
         * target 是原始 Controller，controllerProxy 是外部真正调用的代理对象。
         * 把 Aspect 加入代理后，调用 controllerProxy.seckillVoucher() 会先进入切面；
         * 如果直接调用 target.seckillVoucher()，就会绕过切面，这正是理解代理式 AOP 的关键。
         */
        RateLimitRuleExecutor executor = new RateLimitRuleExecutor(
                new RateLimitKeyResolver(), sliding, tokenBucket);
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
        proxyFactory.addAspect(new RateLimitAspect(executor));
        controllerProxy = proxyFactory.getProxy();
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void allRulesPassInGlobalThenUserOrderBeforeControllerBusiness() {
        Result expected = Result.ok(99L);
        when(tokenBucket.tryAcquire(
                "rate_limit:token_bucket:voucher-order:seckill:global", 1500, 1000)).thenReturn(true);
        when(sliding.tryAcquire(
                "rate_limit:sliding_window_counter:voucher-order:seckill:user:42:arg:7", 5, 1000))
                .thenReturn(true);
        when(orderService.seckillVoucher(7L)).thenReturn(expected);

        assertSame(expected, controllerProxy.seckillVoucher(7L));

        // 顺序验证同时证明重复注解的两条规则都被解析，且业务方法最后才执行。
        org.mockito.InOrder invocationOrder = inOrder(tokenBucket, sliding, orderService);
        invocationOrder.verify(tokenBucket)
                .tryAcquire("rate_limit:token_bucket:voucher-order:seckill:global", 1500, 1000);
        invocationOrder.verify(sliding)
                .tryAcquire("rate_limit:sliding_window_counter:voucher-order:seckill:user:42:arg:7", 5, 1000);
        invocationOrder.verify(orderService).seckillVoucher(7L);
    }

    @Test
    void globalRejectionStopsBeforeUserAndEntireSeckillServiceChain() {
        when(tokenBucket.tryAcquire(anyString(), eq(1500L), eq(1000L))).thenReturn(false);

        assertThrows(RateLimitExceededException.class, () -> controllerProxy.seckillVoucher(7L));

        verifyZeroInteractions(sliding);
        // Service 没有被调用，因此其中的订单 ID、seckill.lua 和 RabbitMQ 发送都不可能发生。
        verifyZeroInteractions(orderService);
    }

    @Test
    void userRejectionConsumesGlobalTokenButStopsEntireSeckillServiceChain() {
        when(tokenBucket.tryAcquire(anyString(), eq(1500L), eq(1000L))).thenReturn(true);
        when(sliding.tryAcquire(anyString(), eq(5L), eq(1000L))).thenReturn(false);

        assertThrows(RateLimitExceededException.class, () -> controllerProxy.seckillVoucher(7L));

        verify(tokenBucket).tryAcquire(anyString(), eq(1500L), eq(1000L));
        verify(sliding).tryAcquire(anyString(), eq(5L), eq(1000L));
        verifyZeroInteractions(orderService);
    }

    @Test
    void rateLimitInfrastructureFailureIsFailClosedBeforeSeckillService() {
        when(tokenBucket.tryAcquire(anyString(), eq(1500L), eq(1000L)))
                .thenThrow(new RateLimitInfrastructureException("test redis unavailable"));

        assertThrows(RateLimitInfrastructureException.class, () -> controllerProxy.seckillVoucher(7L));

        verifyZeroInteractions(sliding);
        verifyZeroInteractions(orderService);
    }
}
