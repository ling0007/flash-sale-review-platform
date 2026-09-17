package com.hmdp.config;

import com.hmdp.dto.Result;
import com.hmdp.ratelimit.exception.RateLimitExceededException;
import com.hmdp.ratelimit.exception.RateLimitInfrastructureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    /**
     * 正常触发限流返回 429 Too Many Requests。
     *
     * <p>限流命中是预期的流量治理结果，不是程序故障，因此只记录简短 WARN，不能为每次刷接口
     * 打印完整 ERROR 堆栈，否则攻击流量反而可能把日志系统打满。</p>
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Result> handleRateLimitExceeded(RateLimitExceededException e) {
        log.warn("请求触发限流：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Result.fail("请求过于频繁，请稍后再试"));
    }

    /**
     * Redis、Lua 或限流状态不可判定时返回 503 Service Unavailable。
     * 秒杀和验证码都采用 fail-closed：异常不能被吞掉后继续进入业务。
     */
    @ExceptionHandler(RateLimitInfrastructureException.class)
    public ResponseEntity<Result> handleRateLimitInfrastructure(RateLimitInfrastructureException e) {
        // 基础设施异常需要保留堆栈，方便排查 Redis 连接、脚本执行或异常返回值。
        log.error("限流基础设施异常：{}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Result.fail("限流服务暂时不可用，请稍后重试"));
    }

    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException e) {
        log.error(e.toString(), e);
        return Result.fail("服务器异常");
    }
}
