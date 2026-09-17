package com.hmdp.ratelimit.exception;

/**
 * 限流依赖的 Redis 或 Lua 无法正常工作时抛出的异常。
 *
 * <p>它和“请求数量确实超过阈值”是两种完全不同的结果：超限属于正常流量治理，
 * 基础设施异常则表示当前无法可靠判断请求是否应当放行。秒杀场景采用 fail-closed，
 * 所以调用方不能在捕获该异常后继续执行后续业务。</p>
 */
public class RateLimitInfrastructureException extends RuntimeException {

    public RateLimitInfrastructureException(String message) {
        super(message);
    }

    public RateLimitInfrastructureException(String message, Throwable cause) {
        super(message, cause);
    }
}
