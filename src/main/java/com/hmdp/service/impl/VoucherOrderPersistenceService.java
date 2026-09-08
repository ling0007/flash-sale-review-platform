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
        Integer count = voucherOrderMapper.selectCount(new QueryWrapper<VoucherOrder>()
                .eq("user_id", order.getUserId()).eq("voucher_id", order.getVoucherId()));
        if (count > 0) {
            log.info("Order already exists for user={}, voucher={}", order.getUserId(), order.getVoucherId());
            return;
        }
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1")
                .eq("voucher_id", order.getVoucherId()).gt("stock", 0).update();
        if (!success) {
            throw new IllegalStateException("Database seckill stock insufficient: " + order.getVoucherId());
        }
        if (voucherOrderMapper.insert(order) != 1) {
            throw new IllegalStateException("Failed to insert voucher order: " + order.getId());
        }
    }
}
