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
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;

@Slf4j
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
        // JSON 转换器保留 MessageProperties 默认的 PERSISTENT；发送异常直接向上传递，不做生产者重试。
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
            try {
                // 跨 Bean 调用事务代理：提交/回滚结束后才返回，外层没有数据库事务。
                persistenceService.createVoucherOrder(order);
            } catch (DuplicateKeyException duplicate) {
                // 异常已穿过事务边界，此次库存扣减已经回滚；再用新查询核实业务唯一键。
                // 不能把任意主键碰撞都当作已处理，否则 orderId 冲突等真实错误会被误 ACK。
                if (!persistenceService.orderExists(message.getUserId(), message.getVoucherId())) {
                    throw duplicate;
                }
                log.info("重复订单已回滚并确认为幂等成功，userId={}, voucherId={}, orderId={}",
                        message.getUserId(), message.getVoucherId(), message.getOrderId());
            }
        } finally {
            lock.unlock();
        }
    }
}
