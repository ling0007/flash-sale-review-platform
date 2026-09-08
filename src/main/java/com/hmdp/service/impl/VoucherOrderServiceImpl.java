package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.VoucherOrderMessage;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SeckillMqConstants;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;

@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private VoucherOrderPersistenceService persistenceService;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(),
                voucherId.toString(), userId.toString());
        if (result == null) {
            throw new IllegalStateException("Seckill Lua returned no result");
        }
        if (result != 0L) {
            return Result.fail(result == 1L ? "库存不足" : "不能重复下单");
        }
        // Deliberately no retry or compensation in this migration phase. Send errors propagate.
        rabbitTemplate.convertAndSend(SeckillMqConstants.EXCHANGE, SeckillMqConstants.ROUTING_KEY,
                new VoucherOrderMessage(orderId, userId, voucherId));
        return Result.ok(orderId);
    }

    @Override
    public void createVoucherOrder(VoucherOrderMessage message) {
        if (message == null || message.getOrderId() == null || message.getUserId() == null
                || message.getVoucherId() == null) {
            throw new IllegalArgumentException("Incomplete voucher order message");
        }
        RLock lock = redissonClient.getLock("lock:order:" + message.getUserId());
        if (!lock.tryLock()) {
            throw new IllegalStateException("Order lock unavailable for user: " + message.getUserId());
        }
        try {
            VoucherOrder order = new VoucherOrder().setId(message.getOrderId())
                    .setUserId(message.getUserId()).setVoucherId(message.getVoucherId());
            // External Spring proxy call: transaction commits/rolls back before unlocking.
            persistenceService.createVoucherOrder(order);
        } finally {
            lock.unlock();
        }
    }
}
