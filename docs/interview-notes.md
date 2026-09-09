# 项目面试复盘

来源：[工程演进日志](engineering-log.md)、[2026-09-08 秒杀 RabbitMQ 迁移审计](任务总结/seckill-rabbitmq-migration.md)、[2026-09-09 消费端可靠性与幂等](任务总结/seckill-consumer-reliability.md)。以当前代码为准，旧方案保留为演进历史。

初学者按 [秒杀 RabbitMQ 面试导读](interview-study-guide/seckill-interview-study-guide.md) 阅读源码、理解知识点、练习成段回答；本文保留考前速记。

## 当前项目亮点

Redis Lua 原子抢购资格与预扣库存，RabbitMQ JSON 消息异步下单，Listener 委托业务 Service，Redisson 用户锁覆盖完整的数据库事务提交。移除手写 Stream 消费和 pending 恢复；修复真实运行路径缺少事务的问题。

消费端增加三次 stateless 本地尝试和错误消息转发；数据库 UNIQUE(user_id, voucher_id) 作为一人一单最终约束。唯一键冲突必须先完成事务回滚，再在外层核实业务键，避免库存已扣但重复订单插入失败仍提交。

本阶段 23 个筛选测试与 Maven package 通过；包含上一阶段主链路回归、真实并发 UNIQUE 冲突、有限重试、错误消息上下文和消费者连接重建。临时 INSERT 故障两次后第三次成功，三个独立事务且库存仅减一次。没有性能压测，不声称吞吐提升或端到端可靠交付已完成。

## 技术选型与取舍

- Redis 保留 Lua、缓存、登录、GEO；仍使用 7.2，并统一 Redisson/Spring Redis 连接配置。先前 Stream 版本兼容、ACK 修复已在历史日志中保留，当前代码不再有该消费链路。
- RabbitMQ DirectExchange 精确匹配订单创建路由，持久拓扑由代码声明。独立 VoucherOrderMessage 防止把数据库实体当消息协议。
- 消费重试使用 stateless：事务在 retry callback 内部开启，每次异常先回滚，下一次尝试开启新事务。只因“业务有事务”就选择 stateful 是不准确的，还要看重试与事务的嵌套关系。
- RepublishMessageRecoverer 在三次尝试耗尽后保留原 JSON、异常栈和原路由，发送到持久错误队列；没有自动回放。仍不启用 Confirm/Return、生产者发送重试或补偿，Redis/MQ 两步调用和错误转发仍有失败窗口。

## 高频面试题

1. 返回 orderId 是下单成功吗？它表示 Lua 已接受且发送调用返回，异步数据库事务可能尚未完成；当前无 broker Confirm。
2. 为什么 public @Transactional 放独立 Bean？外部调用通过 Spring Proxy 执行事务，避免 private/self-invocation 无效；代理返回后才释放用户锁。
3. 如何验证事务？真实 MySQL 中使用冲突订单主键，让扣库存后 INSERT 失败，断言库存不变；单元测试还验证提交/回滚发生在解锁之前。
4. AUTO ACK 与 no-ack 有何区别？Spring AUTO 在 Listener 成功返回后确认；事务错误进入有限本地重试，耗尽后错误转发正常返回才 ACK 原消息。数据库提交一定发生在业务成功返回之前。
5. 为什么仅关 Consumer Retry 不够？broker requeue 与 Spring Retry 不同，默认重新入队仍可能形成消费循环。
6. 为什么用独立测试 JVM？不同 Spring 测试上下文可能缓存并同时消费同一队列，抢走待观测的消息；专用 vhost 和 JVM 隔离才能准确验收。
7. 为什么不能在事务方法内部 catch DuplicateKey 后返回？此前的库存 UPDATE 可能被正常提交。应先让异常穿过事务代理回滚，再在外层核实同一 user/voucher 订单是否存在；无关主键碰撞仍需失败。
8. 错误队列是否保证绝不丢消息？没有 Confirm/Return，转发和原消息 ACK 不是原子操作；持久化不能消除发送失败或崩溃窗口。
9. Lua、查重和 UNIQUE 各做什么？Lua 前置快速拦截，DB 查重优化重复投递，UNIQUE 防止并发查重同时未命中后的重复插单。

## 重点代码与动手练习

阅读 seckill.lua、VoucherOrderServiceImpl.seckillVoucher/createVoucherOrder、VoucherOrderListener.onMessage、VoucherOrderPersistenceService.createVoucherOrder、SeckillRabbitConfig。

在专用 vhost 运行 SeckillConsumerReliabilityTest，重点阅读 temporaryInsertFailuresRetryInFreshTransactions 和 databaseUniqueRaceRollsBackLoserEvenWithoutRedisLock：解释每次重试的事务边界，以及为什么唯一键失败没有留下额外扣库存。练习将临时故障次数从 2 改为 1，并相应断言两次尝试；仍不要开启生产者重试。

## 当前不足

- Redis/MQ 不原子、无生产者确认或失败补偿，仍可能丢单；错误消息转发也没有发布确认。
- MySQL 提交与 ACK 不原子，可能发生重复投递；UNIQUE 与事务保证业务键不重复插单/扣库存，不能据此宣称端到端 exactly-once。
- 错误队列需要人工核对，没有自动重放、Redis 回补或定时对账。长时间锁冲突或数据库故障仍会耗尽本地重试。
- Lua 未补充券不存在和活动起止时间校验；历史 Stream 积压不自动迁移。
- 原有教学测试包含大量写入，不能直接视为普通全量回归；本次未重验上传、关注等所有功能。
