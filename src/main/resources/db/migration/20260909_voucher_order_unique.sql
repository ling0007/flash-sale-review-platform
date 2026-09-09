-- 已有数据库的增量迁移；不要导入会重建表的 hmdp.sql。
-- 先检查历史重复；有结果时停止迁移并人工核对订单，禁止自动删除数据。
SELECT user_id, voucher_id, COUNT(*) AS duplicate_count
FROM tb_voucher_order
GROUP BY user_id, voucher_id
HAVING COUNT(*) > 1;

-- 部署时先停止旧消费者，再执行一次。若有重复，ALTER 本身也会失败，不会删除订单。
ALTER TABLE tb_voucher_order
    ADD UNIQUE KEY uk_voucher_order_user_voucher (user_id, voucher_id);
