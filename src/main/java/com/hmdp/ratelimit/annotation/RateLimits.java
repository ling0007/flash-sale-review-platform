package com.hmdp.ratelimit.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Java 重复注解所需的容器，业务代码通常不需要直接使用它。
 *
 * <p>当一个方法写了两次 {@code @RateLimit} 时，编译器会在底层把它们包装成一个
 * {@code @RateLimits({规则1, 规则2})}。Aspect 必须同时识别单个注解和这个容器，
 * 否则同一方法的多条限流规则可能无法全部执行。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimits {
    /** 容器内保存的全部限流规则。value 是 Java 容器注解约定使用的属性名。 */
    RateLimit[] value();
}
