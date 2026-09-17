package com.hmdp.ratelimit.aspect;

import com.hmdp.ratelimit.annotation.RateLimit;
import com.hmdp.ratelimit.annotation.RateLimits;
import com.hmdp.ratelimit.core.RateLimitRuleExecutor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 在 Controller 业务方法执行前依次检查该方法声明的全部限流规则。
 *
 * <p><b>这里对应的几个 AOP 基本概念：</b></p>
 * <ul>
 *     <li>Aspect（切面）：当前类，把“接口限流”这项横切关注点集中起来。</li>
 *     <li>Pointcut（切点）：下面 {@code @Around} 中的表达式，决定拦截哪些方法。</li>
 *     <li>JoinPoint（连接点）：本次实际被拦截的方法调用，里面有目标对象、方法和实参。</li>
 *     <li>Advice（通知）：{@link #enforce(ProceedingJoinPoint)}，定义拦截后具体做什么。</li>
 * </ul>
 *
 * <p>Spring 会为符合条件的 Controller 创建代理对象。HTTP 请求调用的其实是代理：代理先进入
 * enforce，全部规则通过后才通过 {@code joinPoint.proceed()} 调用真正的 Controller 方法。</p>
 */
// 告诉 Spring：这不是普通组件，而是包含切点和通知的切面类。
@Aspect
// 仍然需要注册为 Spring Bean，否则 Spring 不会发现并创建这个切面。
@Component
public class RateLimitAspect {

    private final RateLimitRuleExecutor ruleExecutor;

    public RateLimitAspect(RateLimitRuleExecutor ruleExecutor) {
        this.ruleExecutor = ruleExecutor;
    }

    /*
     * @Around 是环绕通知：既能在目标方法前执行，也能决定是否继续调用目标方法。
     * 表达式中的 || 表示：方法上有单个 RateLimit，或者有重复注解生成的 RateLimits 容器，都要拦截。
     * 限流必须能在拒绝时阻止业务执行，所以这里不能只使用无法控制后续调用的普通辅助方法。
     */
    @Around("@annotation(com.hmdp.ratelimit.annotation.RateLimit) || "
            + "@annotation(com.hmdp.ratelimit.annotation.RateLimits)")
    public Object enforce(ProceedingJoinPoint joinPoint) throws Throwable {
        // MethodSignature 描述“当前正在调用哪个方法”；joinPoint.getArgs() 则保存这次调用的真实参数。
        Method signatureMethod = ((MethodSignature) joinPoint.getSignature()).getMethod();

        /*
         * Spring 代理有接口代理和类代理两种形式。签名中的 Method 有时来自接口，注解却写在实现类上。
         * getMostSpecificMethod 会定位到目标类最具体的方法，避免因为从错误的方法对象读取而漏掉注解。
         */
        Method method = AopUtils.getMostSpecificMethod(signatureMethod, joinPoint.getTarget().getClass());

        // 同时展开单个注解和重复注解容器，得到这个方法的全部规则。
        Set<RateLimit> discovered = AnnotatedElementUtils.getMergedRepeatableAnnotations(
                method, RateLimit.class, RateLimits.class);
        List<RateLimit> rules = new ArrayList<>(discovered);

        // 不依赖反射返回注解的偶然顺序；由注解中的 order 明确决定 GLOBAL 和 USER 的先后。
        rules.sort(Comparator.comparingInt(RateLimit::order));

        // IP 维度需要当前 HttpServletRequest；USER/GLOBAL 虽然不用，仍共用同一执行入口。
        HttpServletRequest request = currentRequest();
        for (RateLimit rule : rules) {
            // 任意一条规则拒绝都会立即抛出异常，Controller 和后续业务链路都不会执行。
            ruleExecutor.check(rule, joinPoint.getArgs(), request);
        }

        /*
         * proceed() 是“继续调用原方法”的开关。只有循环中所有规则都没有抛异常，才会走到这里。
         * 如果漏掉 proceed()，即使请求通过限流，Controller 也永远不会真正执行。
         */
        return joinPoint.proceed();
    }

    private HttpServletRequest currentRequest() {
        // RequestContextHolder 使用线程上下文保存当前 Web 请求；非 HTTP 调用时可能取不到，因此返回 null。
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes)) {
            return null;
        }
        return ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
    }
}
