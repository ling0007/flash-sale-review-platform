# 黑马点评本地运行

## 启动

需要 Java、Maven、Docker Desktop 和本机已有 MySQL `hmdp` 数据库；不会自动重建数据库。项目仍以 Java 8 为编译目标，本机使用 JDK 21、Maven 3.9.11。

秒杀现在依赖 RabbitMQ。先在当前 PowerShell 设置连接环境变量（用户名和密码由自己配置，不提交到仓库）：

```powershell
$env:RABBITMQ_HOST = '127.0.0.1'
$env:RABBITMQ_PORT = '5672'
$env:RABBITMQ_USERNAME = Read-Host 'RabbitMQ username'
$mqCredential = Get-Credential -UserName $env:RABBITMQ_USERNAME -Message 'RabbitMQ password'
$env:RABBITMQ_PASSWORD = $mqCredential.GetNetworkCredential().Password
$env:RABBITMQ_VIRTUAL_HOST = '/'
```

使用本机已有 `mq` 容器时：

```powershell
docker start mq
.\start-backend.ps1
```

脚本默认只通过 Compose 启动项目 Redis，然后打包并运行后端；RabbitMQ 使用上面指定的现有实例。日志显示在当前终端，Ctrl+C 停止后端。打包前须停止旧后端，避免 Windows 锁定 jar 文件，也避免旧 Stream 消费者继续运行。

如需新建 Compose RabbitMQ，先确保 5672/15672 无占用，或设置 `RABBITMQ_PORT` / `RABBITMQ_MANAGEMENT_PORT` 为未占用端口，再运行：

```powershell
.\start-backend.ps1 -StartRabbitMq
```

Compose 中 RabbitMQ 仅在 `local-rabbitmq` profile 启用，使用独立持久卷；初始账号/vhost 环境变量只在空数据卷上生效。Compose `.env` 不会自动成为 Java 环境变量，因此启动脚本要求当前终端已设置账号和密码。

后端：[商铺详情](http://localhost:8081/shop/1)。前端：在 `E:\dianping\nginx-1.18.0` 启动 `nginx.exe`，访问 [首页](http://localhost:8080/)，`/api/` 代理到 8081。

## 秒杀架构与范围

`POST /voucher-order/seckill/{id}` → Redis Lua 检查库存/一人一单并预扣 → RabbitTemplate 发送 JSON `VoucherOrderMessage` → 返回 orderId → RabbitMQ Listener → Service 获取用户锁 → 独立事务 Service 扣 MySQL 库存、创建 `tb_voucher_order` → 事务完成后释放锁 → 容器自动 ACK。

- DirectExchange：`hmdp.seckill.direct`，Queue：`hmdp.seckill.order.queue`，RoutingKey：`seckill.order.create`。常量统一管理，代码声明持久交换机、持久队列及绑定；绑定随两端持久拓扑恢复。
- RabbitMQ 启动连接/声明错误终止启动；发送异常交给现有异常处理器记录日志并返回失败。
- 消费端使用 AUTO ACK：事务提交后才成功返回。stateless 本地重试最多 3 次（含首次），间隔 1 秒、2 秒；异常耗尽由 RepublishMessageRecoverer 转发至 `hmdp.error.direct` → `hmdp.seckill.error.queue`，routing key 为 `seckill.order`，保留原消息及异常上下文。禁止无限重新入队。
- `tb_voucher_order(user_id, voucher_id)` UNIQUE 是一人一单的最终约束；Lua 是前置快速拦截。扣库存和插单在同一事务，唯一键冲突先回滚，再在事务外核实业务键并作为幂等成功返回。
- 订单和错误消息均为 persistent，业务/错误交换机和队列均 durable。仍不启用 Confirm/Return、RabbitTemplate 发送重试或补偿；错误转发也没有发布确认，发送返回不等于 broker 已可靠落盘。
- Redis 与 MQ 之间不再具有原 Lua 内预扣和 XADD 的原子性；发送失败/进程崩溃可能留下 Redis 预扣和资格记录。此阶段明确接受该缺口，不能用于宣称可靠交付。
- 已移除 Stream 初始化、线程、XREADGROUP/XACK 和 pending 恢复。升级前应停旧实例、核对并处理旧 `stream.orders` 中未完成记录；新应用不会自动迁移或删除旧 Stream 数据。
- Redis 继续使用 7.2、默认端口 6380；Redisson 与 Spring Redis 共用配置。缓存、GEO、登录等 Redis 功能继续保留。

详细审计与验收见 [秒杀迁移说明](docs/任务总结/seckill-rabbitmq-migration.md)。

## 现有数据库升级

升级本阶段代码前先停止旧后端/消费者，检查 `tb_voucher_order` 中重复的 `(user_id, voucher_id)`，再执行一次 [唯一索引增量 SQL](src/main/resources/db/migration/20260909_voucher_order_unique.sql)。有历史重复时先核对，不自动删除订单。脚本不是 Flyway 自动迁移，应用不会自动执行它；本机 `hmdp` 已执行该迁移。

新建数据库的 `hmdp.sql` 已包含该索引，但会重建表，不能拿它升级已有数据库。消费可靠性设计、操作与风险见 [消费端可靠性说明](docs/任务总结/seckill-consumer-reliability.md)。

## 验证

无需中间件的回归：

```powershell
mvn -B '-Dtest=SeckillMigrationTest,VoucherOrderTransactionTest,NormalTest' test
```

真实集成验收必须使用空的专用 RabbitMQ virtual-host，并确保账号具有该 vhost 权限，MySQL 和 Redis 可连接：

```powershell
$env:RABBITMQ_VIRTUAL_HOST = 'hmdp-seckill-verification'
$env:RUN_SECKILL_INTEGRATION = 'true'
mvn -B '-Dtest=SeckillMigrationTest,VoucherOrderTransactionTest,SeckillRabbitIntegrationTest,SeckillConsumerReliabilityTest,NormalTest,RedissonTest' '-DreuseForks=false' package
Remove-Item Env:RUN_SECKILL_INTEGRATION
```

集成测试创建独立 ID 的临时数据并清理，覆盖真实 Controller/Lua/MQ/MySQL、重复抢购、无库存、插单失败回滚、并发幂等、有限重试、错误队列和消费者连接重建，以及无旧消费线程、商铺/分类/热门笔记接口。普通测试默认跳过这些集成用例；需要先完成唯一索引迁移，并使用无其他消费者、业务队列和错误队列均为空的专用 vhost。

不要直接运行所有教学测试：原有 `HmDianPingApplicationTests` 包含大量 ID/HyperLogLog 写入和改变商铺缓存格式的用例。首次使用新 Redis 卷，可单独运行 `mvn -B '-Dtest=HmDianPingApplicationTests#loadShopData' test` 导入 GEO 索引。

## 性能验证

项目已对真实 MySQL 执行计划、商铺详情 Redis 缓存状态，以及秒杀 HTTP→限流→Lua→RabbitMQ→MySQL 链路完成本地实验。SQL 当前数据量过小，暂未新增索引；商铺热缓存稳态隔离了该接口的 MySQL 查询；秒杀独立账号实验完成订单、库存与消息对账，并在本机短批次 200～300 线程之间首次观察到 HTTP 连接拒绝。以上结果是指定本地条件下的验证，**不是生产持续容量或本轮新增缓存/索引带来的提速**。

复现入口见 [压测脚本与步骤](scripts/load-test/README.md)，原始条件、结果及工程取舍见 [工程演进日志](docs/engineering-log.md)，面试表述见 [项目面试复盘](docs/interview-notes.md)。

