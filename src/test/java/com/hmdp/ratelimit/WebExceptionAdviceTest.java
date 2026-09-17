package com.hmdp.ratelimit;

import com.hmdp.config.WebExceptionAdvice;
import com.hmdp.ratelimit.exception.RateLimitExceededException;
import com.hmdp.ratelimit.exception.RateLimitInfrastructureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebExceptionAdviceTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new FailureController())
                .setControllerAdvice(new WebExceptionAdvice())
                .build();
    }

    @Test
    void normalRateLimitReturnsHttp429() throws Exception {
        mvc.perform(get("/test/rate-limited"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("请求过于频繁，请稍后再试"));
    }

    @Test
    void infrastructureFailureReturnsHttp503() throws Exception {
        mvc.perform(get("/test/rate-limit-infrastructure"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("限流服务暂时不可用，请稍后重试"));
    }

    @RestController
    private static class FailureController {

        @GetMapping("/test/rate-limited")
        public void rateLimited() {
            throw new RateLimitExceededException("test quota exhausted");
        }

        @GetMapping("/test/rate-limit-infrastructure")
        public void rateLimitInfrastructure() {
            throw new RateLimitInfrastructureException("test redis unavailable");
        }
    }
}
