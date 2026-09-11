package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class VoucherOrderPersistenceService {
    private final VoucherOrderMapper voucherOrderMapper;
    private final ISeckillVoucherService seckillVoucherService;

    @Transactional
    public void createVoucherOrder(VoucherOrder order) {
        // 快速校验：用户是否已买过该券，提前拦截重复消息
        // 普通查询无锁，并发时会失效，最终靠数据库唯一索引保证一人一单
        if (orderExists(order.getUserId(), order.getVoucherId())) {
            log.info("Order already exists for user={}, voucher={}", order.getUserId(), order.getVoucherId());
            return;
        }
        // 条件更新防超卖。插单失败必须抛出事务边界，让这次扣库存一起回滚。
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1")
                .eq("voucher_id", order.getVoucherId()).gt("stock", 0).update();
        if (!success) {
            throw new IllegalStateException("Database seckill stock insufficient: " + order.getVoucherId());
        }
        if (voucherOrderMapper.insert(order) != 1) {
            throw new IllegalStateException("Failed to insert voucher order: " + order.getId());
        }
    }

    public boolean orderExists(Long userId, Long voucherId) {
        return voucherOrderMapper.selectCount(new QueryWrapper<VoucherOrder>()
                .eq("user_id", userId).eq("voucher_id", voucherId)) > 0;
    }
}
