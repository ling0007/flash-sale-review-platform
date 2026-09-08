# 项目面试复盘

来源：[Redis 启动修复历史](engineering-log.md)、[2026-09-08 秒杀 RabbitMQ 迁移审计](seckill-rabbitmq-migration.md)。以当前代码为准，旧 Stream 修复作为演进历史保留。

## 当前项目亮点

Redis Lua 原子抢购资格与预扣库存，RabbitMQ JSON 消息异步下单，Listener 委托业务 Service，Redisson 用户锁覆盖完整的数据库事务提交。移除手写 Stream 消费和 pending 恢复；修复真实运行路径缺少事务的问题。

15 个筛选测试与 Maven package 通过；真实 Redis/RabbitMQ/MySQL 验证正常订单、无库存、重复抢购、INSERT 失败库存回滚；启动 broker 不可达时失败退出。没有性能压测，不声称吞吐提升或可靠交付已完成。

## 技术选型与取舍

- Redis 保留 Lua、缓存、登录、GEO；仍使用 7.2，并统一 Redisson/Spring Redis 连接配置。先前 Stream 版本兼容、ACK 修复已在历史日志中保留，当前代码不再有该消费链路。
- RabbitMQ DirectExchange 精确匹配订单创建路由，持久拓扑由代码声明。独立 VoucherOrderMessage 防止把数据库实体当消息协议。
- 最小迁移暂不启用 Confirm/Return/重试/DLQ/补偿。原 Lua 内预扣和 XADD 原子提交变成 Redis/MQ 两步调用，接受中间失败窗口；后续可靠性设计应围绕这一缺口展开。

## 高频面试题

1. 返回 orderId 是下单成功吗？它表示 Lua 已接受且发送调用返回，异步数据库事务可能尚未完成；当前无 broker Confirm。
2. 为什么 public @Transactional 放独立 Bean？外部调用通过 Spring Proxy 执行事务，避免 private/self-invocation 无效；代理返回后才释放用户锁。
3. 如何验证事务？真实 MySQL 中使用冲突订单主键，让扣库存后 INSERT 失败，断言库存不变；单元测试还验证提交/回滚发生在解锁之前。
4. AUTO ACK 与 no-ack 有何区别？Spring AUTO 在 Listener 成功返回后确认；事务错误向上抛出导致拒绝消息，此阶段关闭重新入队且无 DLQ。
5. 为什么仅关 Consumer Retry 不够？broker requeue 与 Spring Retry 不同，默认重新入队仍可能形成消费循环。
6. 为什么用独立测试 JVM？不同 Spring 测试上下文可能缓存并同时消费同一队列，抢走待观测的消息；专用 vhost 和 JVM 隔离才能准确验收。

## 重点代码与动手练习

阅读 seckill.lua、VoucherOrderServiceImpl.seckillVoucher/createVoucherOrder、VoucherOrderListener.onMessage、VoucherOrderPersistenceService.createVoucherOrder、SeckillRabbitConfig。

在专用 vhost 运行 SeckillRabbitIntegrationTest，观察暂停 Listener 后队列 1 条消息、恢复后 MySQL 订单生成，再解释为何插单失败时库存没有减少。可给测试加一条“DB 库存为 0 时不插单”的断言练习；不要在练习中启用本阶段排除的重试能力。

## 当前不足

- Redis/MQ 不原子、无生产者确认或失败补偿；消费错误不重试且无错误队列，仍可能丢单。
- 数据库缺少 user_id/voucher_id 联合唯一约束，仅依赖 Lua、用户锁和业务查重，不能宣称任意入口下严格恰好一次。
- Lua 未补充券不存在和活动起止时间校验；历史 Stream 积压不自动迁移。
- 原有教学测试包含大量写入，不能直接视为普通全量回归；本次未重验上传、关注等所有功能。
