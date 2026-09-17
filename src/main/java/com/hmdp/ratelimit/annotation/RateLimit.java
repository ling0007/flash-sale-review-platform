package com.hmdp.ratelimit.annotation;

import com.hmdp.ratelimit.enums.RateLimitAlgorithm;
import com.hmdp.ratelimit.enums.RateLimitDimension;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明一条接口限流规则，也就是给方法附加一份“限流配置元数据”。
 *
 * <p>同一个 Controller 方法可以重复声明多条规则。order 数字越小越先执行，秒杀入口据此固定为
 * GLOBAL 在前、USER 在后。keyArgumentIndex 用于把某个业务参数追加到 key；例如秒杀 USER 规则
 * 追加 voucherId 后，才能做到同一用户不同券互不影响。</p>
 *
 * <p><b>AOP 初学提示：</b>注解自己不会执行限流。它只负责保存参数；真正读取注解并执行逻辑的是
 * {@code RateLimitAspect}。可以把注解理解成贴在方法上的配置标签，把 Aspect 理解成看到标签后
 * 自动执行的拦截器。</p>
 */
// 这个注解只能写在方法上，不能直接写到字段或参数上。
@Target(ElementType.METHOD)
// RUNTIME 表示注解在程序运行时仍然存在，Aspect 才能通过反射读取到它。
@Retention(RetentionPolicy.RUNTIME)
// 允许同一个方法连续写多个 @RateLimit；Java 编译器会用 RateLimits 容器保存它们。
@Repeatable(RateLimits.class)
public @interface RateLimit {

    /** 按谁分桶：当前用户、请求 IP，或者整个接口共享一个 GLOBAL 桶。 */
    RateLimitDimension dimension();

    /** 选择已经实现的 Sliding Window Counter 或 Token Bucket。 */
    RateLimitAlgorithm algorithm();

    /** 业务资源名。它是 Redis key 的稳定组成部分，不应包含当前用户等动态数据。 */
    String resourceKey();

    /** 多条规则的执行顺序；数字越小越先执行。 */
    int order() default 0;

    /**
     * -1 表示不追加方法参数；非负数表示追加对应下标的参数。
     * 秒杀方法的第 0 个参数是 voucherId，因此 USER 规则设置为 0。
     */
    int keyArgumentIndex() default -1;

    /** Sliding Window Counter 的窗口内最大请求数。 */
    long limit() default 0;

    /** Sliding Window Counter 的窗口长度，统一使用毫秒。 */
    long windowMillis() default 0;

    /** Token Bucket 的最大令牌容量，决定允许多大的瞬时 burst。 */
    long capacity() default 0;

    /** Token Bucket 每秒补充的令牌数，决定长期平均放行速率。 */
    long refillRatePerSecond() default 0;
}
