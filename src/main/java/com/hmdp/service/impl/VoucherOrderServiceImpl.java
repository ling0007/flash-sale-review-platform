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
        // 1. 参数校验
        if (message == null || message.getOrderId() == null || message.getUserId() == null
                || message.getVoucherId() == null) {
            throw new IllegalArgumentException("Incomplete voucher order message");
        }
        // 2. 获取Redisson分布式锁：锁粒度是【用户id】，同一个用户抢同一把锁
        RLock lock = redissonClient.getLock("lock:order:" + message.getUserId());
        if (!lock.tryLock()) {
            throw new IllegalStateException("Order lock unavailable for user: " + message.getUserId());
        }
        // 构建订单对象
        try {
            VoucherOrder order = new VoucherOrder().setId(message.getOrderId())
                    .setUserId(message.getUserId()).setVoucherId(message.getVoucherId());
            try {// 3. 调用带事务的Service，插入订单 + 扣库存
                // 注释重点：persistenceService是Spring事务代理对象，方法内部开启事务
                // 事务在persistenceService方法内部提交/回滚；外层当前方法**没有数据库事务**

                persistenceService.createVoucherOrder(order);
            } catch (DuplicateKeyException duplicate) {
                // 4. 捕获数据库唯一索引冲突异常（唯一键：userId + voucherId，限制一人一单）
                // 说明：内部事务已经因为唯一键冲突回滚，库存扣减也回滚了
                // ⚠️ 这里不能直接捕获异常就认为是重复下单，必须二次查询校验
                // 查询发现：数据库里并没有该用户+券的订单，说明不是一人一单冲突，是别的唯一键冲突（比如orderId主键冲突），需要向上抛出异常。
                if (!persistenceService.orderExists(message.getUserId(), message.getVoucherId())) {
                    throw duplicate;
                }
                log.info("重复订单已回滚并确认为幂等成功，userId={}, voucherId={}, orderId={}",
                        message.getUserId(), message.getVoucherId(), message.getOrderId());
            }
        } finally {
            lock.unlock();
        }
        //MQ 消费优惠券下单消息，先用 Redisson 用户级分布式锁防并发；调用事务方法扣库存建订单；捕获数据库唯一冲突异常，**二次查询区分是【用户重复下单】还是【其他主键冲突】**，只对用户重复下单做幂等放行，其余异常抛出交给 MQ 重试。
    }
}
