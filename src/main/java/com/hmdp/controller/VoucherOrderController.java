package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.ratelimit.annotation.RateLimit;
import com.hmdp.ratelimit.enums.RateLimitAlgorithm;
import com.hmdp.ratelimit.enums.RateLimitDimension;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @PostMapping("seckill/{id}")
    /*
     * AOP 应用方式：在需要保护的 public Controller 方法上声明规则即可，不需要在方法体中手工调用限流器。
     * 请求经过 MVC 拦截器完成 token 刷新和登录校验后，会从 Spring 代理对象“外部”进入本方法，
     * 因此 RateLimitAspect 能够拦截。若在同一个类中写 this.seckillVoucher(...)，该内部调用不会经过代理，
     * 这是面试中常问的“Spring AOP 同类自调用失效”问题。
     */
    // GLOBAL 先执行：系统整体过载时尽早拒绝，避免继续创建大量 USER 限流状态。
    // 1500/1000 是设计文档中的学习性初值，必须在 Phase 8 压测后才能确定生产参数。
    @RateLimit(dimension = RateLimitDimension.GLOBAL,
            algorithm = RateLimitAlgorithm.TOKEN_BUCKET,
            resourceKey = "voucher-order:seckill",
            capacity = 1500,
            refillRatePerSecond = 1000,
            order = 0)
    // USER key 追加第 0 个方法参数 voucherId，实现 voucherId + userId 分桶。
    @RateLimit(dimension = RateLimitDimension.USER,
            algorithm = RateLimitAlgorithm.SLIDING_WINDOW_COUNTER,
            resourceKey = "voucher-order:seckill",
            keyArgumentIndex = 0,
            limit = 5,
            windowMillis = 1000,
            order = 1)
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        // 只有上面两条规则都通过，Aspect 才会 proceed 到这里，进而调用原秒杀 Service。
        return voucherOrderService.seckillVoucher(voucherId);
    }
}
