package com.hmdp.ratelimit.enums;

/**
 * 当前项目真正支持的两种限流算法。
 *
 * <p>枚举只保留已经实现并有测试覆盖的能力，避免为了“以后可能会用”引入空壳算法。</p>
 */
public enum RateLimitAlgorithm {
    SLIDING_WINDOW_COUNTER,
    TOKEN_BUCKET
}
