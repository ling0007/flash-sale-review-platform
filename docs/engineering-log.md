# 工程演进日志

记录本项目从“黑马点评原版”到当前魔改版本的真实工程演进。每次有意义的功能、重构、重要 Bug 修复或技术方案调整完成后追加记录，保留历史内容。

记录结构及维护规则见 [AGENTS.md](../AGENTS.md)。面试材料单独维护于 [interview-notes.md](interview-notes.md)，README 用于最终项目展示。

## 记录起点

2026-09-06 建立持续记录约定。本次仅初始化文档，没有修改业务代码；不将文档初始化包装为功能改造，也不补写未经核实的历史开发过程。后续记录依据实际完成的改动和验证结果追加。

## [2026-09-06] 修复 Redis 版本与订单消费者问题并启动后端

### 1. 背景 / 问题

- 原项目已经使用 Redis Stream 异步处理秒杀订单，读取 `stream.orders`，但确认消息错误地使用 `s1`，没有自动初始化消费组，失败后会无间隔重试。
- 本机 Redis 3.0.504 不支持 `XREADGROUP`，实际启动后不断报 `ERR unknown command ''XREADGROUP''`。最初打包成功不代表业务运行正常。

### 2. 方案选择

- 使用官方 Redis 7.2 Alpine 镜像，通过 Docker Compose 在 127.0.0.1:6380 启动项目独立实例并持久化数据。保留原有 6379 服务，避免干扰其现有数据。
- 延续项目原有 Stream 和 Redisson 架构，没有引入新的消息队列。选择支持现有命令的 Redis 版本，是为了同时支持 Stream 和附近商铺 GEOSEARCH。

### 3. 核心实现

- RedissonConfig 读取 Spring RedisProperties，统一地址、端口、数据库、密码和 SSL 配置。
- VoucherOrderServiceImpl.init 使用 createGroup 从 0 初始化消息流和消费组；仅忽略 BUSYGROUP，其他错误明确阻止启动。
- 消费读取和确认使用相同的队列、组常量。异常后等待 1 秒，线程支持中断，应用关闭时 shutdownNow。
- 添加 compose.yaml、start-backend.ps1 和运行说明；用原有 loadShopData 测试导入 14 家商铺的地理索引。

### 4. 遇到的坑

- 持续报未知命令 → Redis 服务端是 3.0.504 → 查询 INFO server 并复现 XINFO/XREADGROUP 错误 → 启动 Redis 7.2 并统一两个客户端的配置。
- 日志高速增长 → pending-list 异常循环没有等待 → 从堆栈定位 handlePendingList → 修复重试间隔，保留原始错误样本并清理本次复现产生的大日志。
- Docker 最初连接失败 → Desktop 未运行 → 启动现有 Docker Desktop 后拉取官方镜像。
- 首次后台启动的相对日志路径解析到工作区根目录，目录不存在 → 改为绝对日志路径后成功启动。
- 新 Redis 中附近查询为空 → 尚未导入 GEO 索引 → 执行 loadShopData 后接口返回 5 家商铺，浏览器显示距离。
- 读取运行中日志遇到文件共享限制 → 改为允许共享读写的有界读取。首次 MySQL 客户端参数未加引号被 PowerShell 错误解析 → 给主机参数加引号后成功确认数据库。

### 5. 为什么这样改

- 消除了已复现的 Redis 命令错误和忙循环；避免消费组缺失和错误 ACK 队列；配置只维护一处。
- 验证事实：打包成功；NormalTest、RedissonTest、loadShopData 共 3 个测试通过；商铺详情、分类、热门笔记、分页、附近查询及 Nginx 代理通过；浏览器首页和商铺列表正常；运行日志检查无 ERROR，消费组存在且 pending 为 0。
- 使用独立 Redis 数据卷需要初始化地理索引；电脑重启后需先运行 Docker Desktop。未做性能压测，也未完整验证秒杀下单、上传、关注。
- 当前消费者仍固定使用 c1；尚未实现多实例消费者身份管理或跨消费者 pending 接管，不能声称提供完整的可靠消息交付。

### 6. 面试复盘

1. 为什么编译通过仍然不断报错？客户端 API 可编译不代表 Redis 服务端支持命令，需核对服务端版本和真实接口。
2. 为什么 ACK 必须使用读取的 Stream 和消费组？待确认记录属于该 Stream 的该组，确认其他 key 不会消除原记录。
3. 如何幂等初始化消费组？使用带 MKSTREAM 的创建操作，只对 BUSYGROUP 表示的已存在情况放行。
4. 为什么加重试间隔和线程中断？避免依赖故障时忙循环，并使线程能够在应用关闭时退出；固定间隔仍不能解决毒消息和跨消费者接管。

## [2026-09-08] 秒杀异步订单最小迁移至 RabbitMQ

### 1. 背景 / 问题

- 原实现由 Lua 在预扣后 XADD，再由自建 Stream 线程消费并恢复 pending。迁移目标是把订单投递与消费交给专用消息中间件及 Spring Listener，减少业务 Service 自行管理消费循环的职责，不是基于未经测试的性能优劣做选择。
- 实际落库方法是 private、没有事务；带 @Transactional 的旧方案仅存在于注释中，库存与订单无法保证同一事务。

### 2. 方案选择

- RabbitMQ DirectExchange + 持久队列 + 精确路由；独立 JSON DTO 隔离消息契约与表实体。Redis 保留资格判定、库存预扣、登录缓存和 GEO 等职责。
- 本阶段只验证最短端到端链路，按范围不启用 Confirm/Return、发送重试、消费重试、DLQ、补偿或本地消息表。代价是放弃原 Lua 内预扣与 XADD 的原子提交，存在 Redis 成功、MQ 未成功的窗口。

### 3. 核心实现

- Lua 成功后 RabbitTemplate 发送原 orderId/userId/voucherId，失败 Lua 不发消息。Listener 仅记录日志及委托 Service。
- 原 Service 获取用户锁后调用独立 VoucherOrderPersistenceService 的 public @Transactional 方法；MySQL 条件扣库存和插单共用事务，事务完成后才解锁，避免 self-invocation 和提交前释放锁。
- 启动显式声明持久拓扑，连接/认证/声明错误失败退出。业务消费异常不重新入队，避免默认 requeue 形成隐式无限重试。删除旧 Stream 所有运行代码，不自动删除旧数据。

### 4. 遇到的坑

- Docker 被手动暂停，无法验收 → 用户恢复 Docker 后复用现有 mq 容器，在专用 vhost 验证，未更改其账号密码。
- Windows 打包无法重命名 jar → 检查旧 Java 进程工作目录，确认它属于本项目 → 停止旧后端，再成功打包并启动新版，防止旧 Stream 消费者同时运行。
- 首版事务测试的 MyBatis 链式 deep mock 返回 null → 改用真实 UpdateChainWrapper 和 mock mapper；集成测试缺少 Awaitility → 补测试依赖。均为本次测试实现问题，修复后通过。
- 集成测试等待队列 1 条超时，日志却显示订单已被消费 → 原有 RedissonTest 的缓存 Spring 上下文也在监听同一队列 → 验收使用 -DreuseForks=false 隔离 JVM，避免跨测试消费者干扰。

### 5. 为什么这样改

- Listener 生命周期交给 Spring AMQP，订单核心仍在 Service。独立事务 Bean 修复了实际存在的数据库原子性缺口；JSON DTO 避免数据库字段变化隐式改变消息体。
- 实测 15 个测试通过且 package 成功，覆盖真实 Controller/Lua/MQ/MySQL、无库存、重复用户、插单冲突回滚、无旧线程，以及商铺/分类/热门笔记与原有简单/Redisson 测试。控制台已核对 direct/durable 和绑定；不可达 broker 启动退出码 1。
- 不宣称可靠交付或性能提升。发送窗口无补偿，消费失败无重试/DLQ，历史 Stream 未完成记录需要切换时核对；完整上传、关注流程未在此阶段回归。
- 详见 [审计与验收](seckill-rabbitmq-migration.md)，其中区分真实集成验证与 mock 测试。

### 6. 面试复盘

1. 为什么换 RabbitMQ？将专用消息路由及消费生命周期交给中间件和框架，移除手写 Stream 消费循环；不是证明 Stream 性能差。
2. 为什么事务方法放到另一个 Bean？private/内部调用不经过事务代理，跨 Bean public 调用才能让本次 @Transactional 生效，并在提交后解锁。
3. 为什么现在不开 Confirm？阶段范围先验证链路；因此返回 orderId 不代表 broker 已确认，更不代表 MySQL 已落库。
4. durable 能保证不丢消息吗？不能覆盖发送前崩溃、跨 Redis/MQ 原子性、消费失败丢弃等问题。
5. 为什么关 retry 还要关 requeue？它们是不同机制；只关 Spring Retry 仍可能被容器默认重新入队。
