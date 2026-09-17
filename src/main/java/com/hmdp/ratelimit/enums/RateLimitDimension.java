package com.hmdp.ratelimit.enums;

/** 限流对象维度：当前用户、请求 IP 或整个资源。 */
public enum RateLimitDimension {
    USER,
    IP,
    GLOBAL
}
