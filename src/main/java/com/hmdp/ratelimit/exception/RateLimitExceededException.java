package com.hmdp.ratelimit.exception;

/**
 * 算法正常工作但当前额度不足。
 *
 * <p>这是正常的流量治理结果，不是系统故障。全局异常处理会把它映射为 HTTP 429，
 * 并且只记录简短 WARN，不打印完整 ERROR 堆栈。</p>
 */
public class RateLimitExceededException extends RuntimeException {

    public RateLimitExceededException(String message) {
        super(message);
    }
}
