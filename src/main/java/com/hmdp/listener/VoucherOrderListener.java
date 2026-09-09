package com.hmdp.listener;

import com.hmdp.dto.VoucherOrderMessage;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.SeckillMqConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class VoucherOrderListener {
    private final IVoucherOrderService voucherOrderService;

    @RabbitListener(queues = SeckillMqConstants.QUEUE)
    public void onMessage(VoucherOrderMessage message) {
        log.info("Received seckill order: {}", message);
        // Service 事务提交后才返回；异常交给 Consumer Retry，禁止 catch 后仅打印日志导致误 ACK。
        voucherOrderService.createVoucherOrder(message);
    }
}
