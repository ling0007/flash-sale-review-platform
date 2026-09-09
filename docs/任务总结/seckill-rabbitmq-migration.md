# 秒杀 RabbitMQ 最小迁移审计与验收

> 本文记录 2026-09-08 第一阶段的实现和验收。当前已补充消费重试、错误队列及数据库唯一索引，最新行为见 [消费端可靠性说明](seckill-consumer-reliability.md)；下文历史限制保留供演进对照。

## 迁移前审计（基于本次开始时工作区代码）

| 环节 | 实际实现 |
|---|---|
| 入口 | VoucherOrderController，POST /voucher-order/seckill/{id} → IVoucherOrderService.seckillVoucher |
| ID | VoucherOrderServiceImpl.seckillVoucher 在 Lua 前调用 RedisIdWorker.nextId("order")，时间戳与 Redis 日序列组合 |
| Lua | seckill.lua：stock key 为 seckill:stock:{voucherId}；资格 set 为 seckill:order:{voucherId}；检查库存、一人一单、INCRBY -1、SADD，然后 XADD |
| Stream | stream.orders，消费组 g1，消费者 c1；启动时从 0 创建组，允许 BUSYGROUP |
| 消费 | 单线程 ExecutorService + VoucherOrderHandler；XREADGROUP 每次一条、阻塞 2 秒，解析实体后创建订单，再 XACK |
| 恢复 | 异常休眠 1 秒后 handlePendingList，以偏移 0 读取同消费者 pending，成功后 ACK |
| 落库 | private createVoucherOrder(VoucherOrder)：Redisson 用户锁 → 查重 → stock > 0 条件扣库存 → save |
| 事务 | 实际运行方法 private 且无 @Transactional；内部类直接调用，不经过 Spring 代理。文件中带事务的历史实现全在注释内，不能视为现有事务保障 |

## 新链路与设计边界

Lua 仅负责 Redis 内原子判断、预扣、资格记录；Java 保持原 orderId 生成位置，用同一 ID 构造独立 VoucherOrderMessage，经 RabbitTemplate 发送后返回。失败 Lua 不发送 MQ。无 Confirm 时“发送调用返回”不能解释为 broker 已可靠接收。

VoucherOrderListener 只日志及委托。VoucherOrderServiceImpl 验证消息字段、取得原有用户锁、映射实体，并调用另一个 Spring Bean VoucherOrderPersistenceService 的 public @Transactional 方法；MySQL 查重、条件扣库存与插单共用事务，插单异常或返回 0 均回滚。事务代理返回后才释放锁，避免在提交前暴露竞争窗口。

拓扑常量集中在 SeckillMqConstants，DirectExchange 和 Queue 均 durable、非 auto-delete；Binding 绑定两端持久对象（AMQP Binding 没有独立 durable 开关）。SeckillRabbitConfig 配置 Jackson JSON converter，并在 Listener 启动前主动声明拓扑，连接/认证/声明异常不吞掉。配置来自环境变量；没有提交用户提供的凭据。

容器 AUTO ACK 表示业务方法成功返回后由框架确认，不是 RabbitMQ 的 no-ack。retry.enabled=false 加 default-requeue-rejected=false，避免业务异常无限重新入队；没有 DLQ，失败消息可能丢弃。这是本阶段有意保留的限制。

旧 Stream 线程、初始化、XADD/XREADGROUP/XACK、pending 恢复全部移除。只改秒杀路径，没有清理其他 Redis 功能，也没有自动删除或转发历史 Stream 数据。

配置语义参考：[Spring AMQP 2.2 官方参考](https://docs.spring.io/spring-amqp/docs/2.2.7.RELEASE/reference/html/)。

## 本次修改文件

- pom.xml：AMQP starter、测试用 Awaitility。
- src/main/resources/application.yaml、seckill.lua。
- src/main/java/com/hmdp/config/SeckillRabbitConfig.java。
- src/main/java/com/hmdp/utils/SeckillMqConstants.java。
- src/main/java/com/hmdp/dto/VoucherOrderMessage.java。
- src/main/java/com/hmdp/listener/VoucherOrderListener.java。
- src/main/java/com/hmdp/service/IVoucherOrderService.java。
- src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java、VoucherOrderPersistenceService.java。
- src/test/java/com/hmdp/SeckillMigrationTest.java、VoucherOrderTransactionTest.java、SeckillRabbitIntegrationTest.java。
- compose.yaml、start-backend.ps1、README.md。
- docs/engineering-log.md、docs/interview-notes.md、本文。

工作区开始时已有未提交改动；RedissonConfig、数据库 SQL 以及 AGENTS.md 等原有改动保持原样，不作为本次实现成果。

## 实际验收（2026-09-08）

环境：本机 JDK 21 / Maven 3.9.11、已有 MySQL hmdp、Compose Redis 7.2:6380、用户已有 mq 容器 RabbitMQ 3.8.19。创建专用 hmdp-seckill-verification vhost 验收，保留空拓扑以便控制台复核；测试数据已清理。

执行（凭据使用环境变量）：

```powershell
$env:RUN_SECKILL_INTEGRATION = 'true'
$env:RABBITMQ_VIRTUAL_HOST = 'hmdp-seckill-verification'
mvn -B '-Dtest=SeckillMigrationTest,VoucherOrderTransactionTest,SeckillRabbitIntegrationTest,NormalTest,RedissonTest' '-DreuseForks=false' package
```

结果：15 tests，0 failures，0 errors，0 skipped；BUILD SUCCESS。必须隔离测试 JVM，否则原有 RedissonTest 缓存的 Spring 上下文会提前消费集成测试队列。

- 真实 MockMvc 请求经过 Controller 和登录拦截器：Lua 成功，暂停 Listener 时观察到 MQ 1 条消息；开启 Listener 后 MySQL 生成对应 orderId/userId/voucherId 的订单，库存从 2 变 1。
- 同用户重复请求及另一张库存为 0 的券均失败；队列仍只有原来的 1 条消息。
- 独立事务 Bean 确认为 Spring Proxy；另一用户使用冲突订单主键触发真实 MySQL INSERT 失败，前面的扣库存成功回滚。
- 运行时线程堆栈无 VoucherOrderHandler；src/main 搜索旧 Stream 运行标识无匹配。
- 既有 NormalTest、RedissonTest 通过；真实商铺详情、分类、热门笔记接口返回 success。
- 浏览器登录 RabbitMQ 管理控制台，确认 hmdp.seckill.direct 为 direct/durable，绑定 hmdp.seckill.order.queue，routing key 为 seckill.order.create。
- 用打包 jar 指向不可连接的 RabbitMQ 端口 1，启动明确失败、记录连接异常、退出码 1；没有静默启动。

日志：target/migration-integration.log、target/surefire-reports/、target/migration-unreachable.log。未运行含批量写入的全部教学测试；未重新验证上传、关注等全部业务。

## 保留问题

1. Redis 预扣与 MQ 发送跨系统不原子；无 Confirm/Return/补偿，存在预扣成功却无订单的窗口。
2. 消费失败不重试且无错误队列；锁冲突、DB 故障可能丢弃消息，需要后续可靠性阶段处理。
3. 现有 tb_voucher_order 只有订单主键，没有 user_id/voucher_id 联合唯一约束；当前依赖 Lua、锁和业务查重，不宣称任意写入路径下严格恰好一次。
4. Lua 原有缺失库存 key 报错、未检查活动起止时间的行为未扩展；本阶段不处理这些历史业务边界。
5. 旧 Stream 中残留记录不自动迁移；上线切换必须先核对未完成订单。未引入任何重试、DLQ、延迟订单或其它本阶段禁止能力。
