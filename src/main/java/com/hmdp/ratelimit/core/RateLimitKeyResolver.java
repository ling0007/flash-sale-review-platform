package com.hmdp.ratelimit.core;

import com.hmdp.dto.UserDTO;
import com.hmdp.ratelimit.annotation.RateLimit;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;
import java.util.Locale;

/**
 * 只负责把注解、当前请求和方法参数转换成一个稳定的 Redis key。
 *
 * <p>把 key 解析从 Aspect 拆出来后，切面不需要理解 USER/IP/GLOBAL 的业务差异；同时可以直接测试
 * “相同用户不同 voucherId 是否隔离”“GLOBAL 是否错误带上 voucherId”等关键规则。</p>
 */
@Component
public class RateLimitKeyResolver {

    private static final String KEY_PREFIX = "rate_limit:";

    public String resolve(RateLimit rule, Object[] arguments, HttpServletRequest request) {
        String resourceKey = rule.resourceKey() == null ? "" : rule.resourceKey().trim();
        if (resourceKey.isEmpty()) {
            throw new IllegalArgumentException("Rate limit resourceKey must not be blank");
        }

        StringBuilder key = new StringBuilder(KEY_PREFIX)
                // 使用 Locale.ROOT，避免服务器默认语言影响枚举转小写后的 Redis key。
                .append(rule.algorithm().name().toLowerCase(Locale.ROOT))
                .append(':').append(resourceKey)
                .append(':').append(rule.dimension().name().toLowerCase(Locale.ROOT));

        switch (rule.dimension()) {
            case USER:
                UserDTO user = UserHolder.getUser();
                if (user == null || user.getId() == null) {
                    // USER 规则依赖登录拦截器先写入 UserHolder。缺失时不能退化成共享 key。
                    throw new IllegalStateException("USER rate limit requires an authenticated user");
                }
                key.append(':').append(user.getId());
                break;
            case IP:
                if (request == null || request.getRemoteAddr() == null) {
                    throw new IllegalStateException("IP rate limit requires an HTTP request");
                }
                // 当前项目没有可信代理网段配置，因此只使用连接对端地址，绝不直接信任客户端传入的 forwarded header。
                key.append(':').append(request.getRemoteAddr());
                break;
            case GLOBAL:
                // GLOBAL 故意不追加用户、IP 或 voucherId，整个资源共享同一个桶。
                break;
            default:
                throw new IllegalArgumentException("Unsupported rate limit dimension: " + rule.dimension());
        }

        int argumentIndex = rule.keyArgumentIndex();
        if (argumentIndex >= 0) {
            if (arguments == null || argumentIndex >= arguments.length || arguments[argumentIndex] == null) {
                throw new IllegalArgumentException("Rate limit key argument is missing at index " + argumentIndex);
            }
            // Aspect 通过 joinPoint.getArgs() 取得真实实参；秒杀中 arguments[0] 就是 voucherId。
            key.append(":arg:").append(arguments[argumentIndex]);
        }
        return key.toString();
    }
}
