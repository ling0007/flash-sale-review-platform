package com.hmdp.ratelimit;

import com.hmdp.dto.UserDTO;
import com.hmdp.ratelimit.annotation.RateLimit;
import com.hmdp.ratelimit.core.RateLimitKeyResolver;
import com.hmdp.ratelimit.enums.RateLimitAlgorithm;
import com.hmdp.ratelimit.enums.RateLimitDimension;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RateLimitKeyResolverTest {

    private final RateLimitKeyResolver resolver = new RateLimitKeyResolver();

    @AfterEach
    void clearUser() {
        UserHolder.removeUser();
    }

    @Test
    void userKeyContainsUserAndSelectedBusinessArgument() throws Exception {
        UserDTO user = new UserDTO();
        user.setId(42L);
        UserHolder.saveUser(user);

        assertEquals(
                "rate_limit:sliding_window_counter:voucher-order:seckill:user:42:arg:7",
                resolver.resolve(rule("userRule"), new Object[]{7L}, null)
        );
    }

    @Test
    void globalKeyDoesNotContainVoucherOrUser() throws Exception {
        UserDTO user = new UserDTO();
        user.setId(42L);
        UserHolder.saveUser(user);

        assertEquals(
                "rate_limit:token_bucket:voucher-order:seckill:global",
                resolver.resolve(rule("globalRule"), new Object[]{7L}, null)
        );
    }

    @Test
    void ipUsesConnectionPeerAndIgnoresUntrustedForwardedHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.8");
        request.addHeader("X-Real-IP", "203.0.113.10");
        request.addHeader("X-Forwarded-For", "198.51.100.2");

        assertEquals(
                "rate_limit:sliding_window_counter:shop:list:ip:10.0.0.8",
                resolver.resolve(rule("ipRule"), new Object[0], request)
        );
    }

    @Test
    void userRuleFailsInsteadOfFallingBackToSharedAnonymousKey() throws Exception {
        assertThrows(IllegalStateException.class,
                () -> resolver.resolve(rule("userRule"), new Object[]{7L}, null));
    }

    private RateLimit rule(String methodName) throws Exception {
        Method method = Fixture.class.getDeclaredMethod(methodName, methodName.equals("ipRule")
                ? new Class<?>[0] : new Class<?>[]{Long.class});
        return method.getAnnotation(RateLimit.class);
    }

    private static class Fixture {
        @RateLimit(dimension = RateLimitDimension.USER,
                algorithm = RateLimitAlgorithm.SLIDING_WINDOW_COUNTER,
                resourceKey = "voucher-order:seckill", keyArgumentIndex = 0,
                limit = 5, windowMillis = 1000)
        void userRule(Long voucherId) {
        }

        @RateLimit(dimension = RateLimitDimension.GLOBAL,
                algorithm = RateLimitAlgorithm.TOKEN_BUCKET,
                resourceKey = "voucher-order:seckill", capacity = 1500, refillRatePerSecond = 1000)
        void globalRule(Long voucherId) {
        }

        @RateLimit(dimension = RateLimitDimension.IP,
                algorithm = RateLimitAlgorithm.SLIDING_WINDOW_COUNTER,
                resourceKey = "shop:list", limit = 100, windowMillis = 10_000)
        void ipRule() {
        }
    }
}
