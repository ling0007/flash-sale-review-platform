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
- 详见 [审计与验收](任务总结/seckill-rabbitmq-migration.md)，其中区分真实集成验证与 mock 测试。

### 6. 面试复盘

1. 为什么换 RabbitMQ？将专用消息路由及消费生命周期交给中间件和框架，移除手写 Stream 消费循环；不是证明 Stream 性能差。
2. 为什么事务方法放到另一个 Bean？private/内部调用不经过事务代理，跨 Bean public 调用才能让本次 @Transactional 生效，并在提交后解锁。
3. 为什么现在不开 Confirm？阶段范围先验证链路；因此返回 orderId 不代表 broker 已确认，更不代表 MySQL 已落库。
4. durable 能保证不丢消息吗？不能覆盖发送前崩溃、跨 Redis/MQ 原子性、消费失败丢弃等问题。
5. 为什么关 retry 还要关 requeue？它们是不同机制；只关 Spring Retry 仍可能被容器默认重新入队。

## [2026-09-09] RabbitMQ 消费端有限重试、错误队列与订单幂等

### 1. 背景 / 问题

- 上阶段迁移已能真实下单，但消费错误直接拒绝且不重新入队，缺少失败消息保存；数据库仅有订单主键，没有用户/优惠券联合唯一约束。重复投递和并发查重需要最终数据库保障。
- 本阶段复跑原真实主链路测试通过，确认 DTO/拓扑常量统一、Stream 运行代码不存在；迁移前数据库重复业务键组合数为 0。

### 2. 方案选择

- 使用 Spring AUTO ACK 和 stateless Consumer Retry，最多三次尝试，等待 1 秒、2 秒。核对 Spring AMQP 2.2.18 和 Boot 2.3.12：数据库事务在每次 retry callback 内开启并结束，不存在包住所有尝试的外层事务，因而选择 stateless。
- 耗尽后使用框架 RepublishMessageRecoverer 向持久错误拓扑转发原消息、异常栈和原路由；禁止无限 requeue，不引入手写 ACK 或第二套重试循环。
- 保留 Lua 前置拦截和用户锁，数据库 UNIQUE(user_id, voucher_id) 兜底。Confirm/Return、发送重试、补偿、延迟订单和对账继续排除在范围外。

### 3. 核心实现

- 在业务事务方法外捕获 DuplicateKeyException，确认事务已回滚后再查业务键，已有相应订单才作为幂等成功。主键碰撞但无同业务订单仍失败，避免宽泛吞掉数据完整性异常。
- 库存使用 stock > 0 条件更新，与 INSERT 同一事务；并发 UNIQUE 失败会回滚本次库存修改。新增一次性增量 SQL 并更新新建库 SQL，已在本机数据库执行索引迁移，没有删除历史记录。
- 错误交换机 hmdp.error.direct、队列 hmdp.seckill.error.queue 和路由 seckill.order 均使用常量和代码声明。正常消息验证默认 PERSISTENT，Recoverer 显式持久化；Listener 继续只委托 Service，不 catch 吞异常。

### 4. 遇到的坑

- 首版真实故障注入使用 @SpyBean Mapper，测试上下文启动失败 → 当前 Mockito 无法 spy JDK final 代理 → 从堆栈定位 Mapper 代理 → 改用仅测试生效的 Spring BeanPostProcessor/MethodInterceptor 包装真实 Mapper。保留所有异常注入、数据库访问及回滚断言，没有移除失败场景。
- 仅用 Redis 锁下的并发消息不足以证明 UNIQUE 兜底 → 增加测试限定的“锁不互斥”模拟和双查重屏障，强制触发一次真实 MySQL 唯一键异常，再断言两个 Service 调用都成功且库存只扣一次。

### 5. 为什么这样改

- 瞬时失败可在有限次数内恢复；持续失败在错误队列保留诊断上下文。数据库唯一约束与正确事务边界保护重复投递下的业务效果，不依赖查重请求恰好串行。
- 实测完整筛选套件 23 tests、0 failures、0 errors、0 skipped，Maven package 成功；覆盖正常消费、顺序重投、并发重复、真实唯一冲突回滚、临时故障退避与独立事务、持续故障错误转发、消费者连接重建，以及原非秒杀回归。
- 数据库提交后 ACK 丢失仍会重投；Redis/MQ 预扣发送窗口、错误转发没有 Confirm 的窗口仍存在。错误队列没有自动修复或 Redis 补偿，不能宣称消息绝不丢失或端到端 exactly-once。
- 除自动测试的消费者停启/连接重建外，实际停止并重启打包应用进程，未消费消息保留；重启后样例三次尝试耗尽转入错误队列。浏览器核实持久消息、原 JSON 和异常上下文；样例有独立验收标记，未产生数据库订单。不据此声称完成 broker 宿主机断电恢复验证。详细操作与验收见 [消费端可靠性说明](任务总结/seckill-consumer-reliability.md)。

### 6. 面试复盘

1. 有事务为什么仍选 stateless？看嵌套关系；本项目事务在每次尝试内部开启，回滚完成后重试，无需跨重投递维护事务状态。
2. 唯一键异常为什么必须穿过事务边界？需要同时回滚之前的库存 UPDATE；事务内 catch 后正常返回可能错误提交扣库存。
3. 重试三次具体是什么？包括首次，总共三次调用，中间等待 1 秒、2 秒，不是首次加三次重试。
4. 错误队列与 DLX 是否相同？这里由应用 Recoverer 主动转发并加上下文，成功返回后原消息 ACK，没有配置 broker DLX。
5. 持久化是否代表发送可靠？不是；durable/persistent 保护已接收的拓扑和消息，但没有发布确认时不能覆盖发送/错误转发窗口。

## [2026-09-12] 接口限流 Phase 2：Redis Lua 滑动窗口计数器

### 1. 背景 / 问题

- 秒杀入口尚无限流能力，单用户重复请求会在被库存或一人一单逻辑拒绝前生成订单 ID并执行原有秒杀 Lua。当前阶段只建设 Sliding Window Counter 核心能力，不接入 Controller，也不改变原有 `seckill.lua`、RabbitMQ 消费和 MySQL 事务职责。
- 固定窗口存在边界突刺；Redis ZSET Sliding Window Log 会为每个请求保存 member。USER/IP 防刷只需要近似统计最近窗口请求量，没有保存逐请求时间戳的必要。

### 2. 方案选择

- 每个限流对象使用一个 Redis Hash，保存当前窗口起点、上一窗口计数和当前窗口计数，状态量固定。使用上一窗口剩余权重与当前计数估算最近窗口流量。
- Lua 内调用 Redis `TIME` 并统一换算为毫秒，避免多应用实例使用各自主机时间。判断 `estimated + 1 <= limit` 后才计入本次请求，防止估算值已接近 limit 时再放行导致明显越界。
- 脚本返回 `1` 表示放行并计数、`0` 表示正常限流。Redis、Lua、空返回或未知返回码统一包装为基础设施异常，保留秒杀 fail-closed 语义；HTTP 503 映射留到后续异常处理阶段。

### 3. 核心实现

- `SlidingWindowCounterRateLimiter` 负责参数校验、加载独立 Lua、解释返回值和包装 Redis 异常；没有引入通用算法接口、注解或 AOP。
- `sliding_window_counter.lua` 原子完成 Redis 取时、窗口对齐、窗口切换、权重估算、阈值判断、计数和 `PEXPIRE`。只跨一个窗口时将原当前计数旋转为上一计数；跨两个及以上窗口时清空旧计数。
- Redis Hash 字段为 `current_window_start_ms`、`previous_count`、`current_count`；TTL 为两倍窗口长度。Redis 时间回拨到更早窗口或 Hash 状态残缺时返回脚本错误，不猜测并放行。
- 新增 5 个 Java 契约测试和 5 个真实 Redis 集成测试，覆盖正常计数、上一窗口衰减、用户/券 key 隔离、TTL 自动过期及 100 请求并发下只放行 limit=20。另回归 8 个秒杀迁移测试和 6 个订单事务测试。

### 4. 遇到的坑

- 首次编译集成测试时找不到 `RedisCallback` → 错误地从 `org.springframework.data.redis.connection` 导入 → 根据编译器错误核对 Spring Data Redis API → 改为 `org.springframework.data.redis.core.RedisCallback`，随后单元、真实 Redis 和相关秒杀回归测试全部通过。
- 最终执行 Spring Boot `package` 时无法把 JAR 重命名为 `.original` → 只读检查发现 PID 6412 正以该目标 JAR 运行后端 → 为避免打断用户正在运行的服务，没有擅自停止进程。该次命令的编译和普通 JAR 生成阶段已完成，但 Spring Boot 可执行 JAR 重打包未完成；测试结果不受影响，停止旧后端后需重新执行 package。

### 5. 为什么这样改

- 相比固定窗口减轻窗口边界突刺；相比 ZSET 日志不保存逐请求记录，每个逻辑 key 的字段数量固定，更适合大量 USER/IP key。
- 它是近似流量治理算法，假设上一窗口内请求均匀分布，因此权重估算存在算法误差；库存和一人一单正确性仍应由原有秒杀 Lua、消息消费和数据库约束负责。
- 当前只是可独立测试的算法模块，尚未接入 HTTP 入口，不能声称秒杀已经具备限流、429/503 已完成或系统吞吐得到提升。

### 6. 面试复盘

1. Counter 和 Log 有何区别？Counter 只保存相邻窗口计数、空间固定但为近似值；Log 保存窗口内每次请求，精确但空间随请求数增长。
2. 上一窗口权重如何计算？`(windowMillis - elapsedInCurrentWindow) / windowMillis`，越靠近当前窗口末尾，上一窗口影响越小。
3. 为什么判断 `estimated + 1`？限流决策应考虑本次待加入请求，否则估算值接近上限时仍会额外放行。
4. 为什么使用 Redis TIME？多实例共享一个时间来源，避免应用节点时钟偏差造成窗口边界不一致。
5. 为什么 TTL 是两倍窗口？上一窗口最多影响紧邻的当前窗口，两倍窗口覆盖所需状态并在停止访问后回收 key。
6. Lua 原子性解决什么？避免并发请求在读取相同计数后都判断可放行，再分别写回造成额度超发；它不消除滑动窗口计数器本身的估算误差。

## [2026-09-16] 接口限流 Phase 3：Redis Lua 令牌桶

### 1. 背景 / 问题

- Phase 2 已完成 USER/IP 所需的 Sliding Window Counter，但秒杀 GLOBAL 的目标不是近似统计最近窗口请求数，而是限制长期平均进入速率并允许系统承受范围内的瞬时 burst。
- 当前阶段只建设 Token Bucket 核心与测试，不接入注解、AOP 或秒杀 Controller，也不修改原有秒杀 Lua、RabbitMQ 消费和数据库事务。

### 2. 方案选择

- 每个桶使用一个 Redis Hash，只保存 `tokens_milli` 和 `last_refill_ms`。GLOBAL 调用方后续必须让所有 voucherId 共用同一个、不含券 ID 的 key。
- 使用整数 milli-token：`1000 milli-token = 1 token`。它能保留不足一个完整令牌的补充值，同时避免在 Redis 中直接持久化浮点数。当前核心接收正整数 token/秒，补充精度为 1 毫秒。
- Lua 内使用 Redis `TIME`，按 `min(capacity, oldTokens + elapsed × refillRate)` 补充并封顶；读取、补充、判断、扣减、更新时间和 TTL 设置在一次脚本中原子完成。
- TTL 取 `max(1000ms, 空桶恢复满桶所需时间)`。无请求直到 key 过期时，桶在逻辑上已经恢复为满桶；下次按满桶初始化与保留满桶状态语义一致。

### 3. 核心实现

- 新增 `TokenBucketRateLimiter`，负责 capacity/refillRate 安全范围校验、独立 Lua 加载、`1/0` 返回值解释和 Redis 异常 fail-closed 包装，复用 Phase 2 的 `RateLimitInfrastructureException`。
- `token_bucket.lua` 对新 key 以满桶初始化，每次请求消耗 1000 milli-token。拒绝请求仍保存累计出的不足一个完整令牌的余额和当前时间，避免高频请求导致小数补充值丢失。
- 脚本会将余额限制在 capacity；长 elapsed 直接恢复满桶，避免无意义的大数乘法。Redis 时间回拨、Hash 字段残缺或非数字状态均返回脚本错误。
- 新增 5 个 Java 契约测试和 5 个真实 Redis 集成测试，覆盖初始容量、连续扣减、耗尽拒绝、自动补充、capacity 封顶、空闲过期恢复、100 请求并发原子性和不同 voucherId 共用 GLOBAL key。
- 实测两种限流算法共 10 个真实 Redis 测试通过；两种算法单元测试加原秒杀迁移/事务回归共 24 个测试通过。

### 4. 遇到的坑

- 首次真实集成测试 5 个用例全部连接拒绝 → 127.0.0.1:6380 的项目 Redis 未运行，Docker Desktop 也未启动；同时 compose 即使只选择 Redis 也会先校验 RabbitMQ 必填变量 → 启动 Docker Desktop，并仅在命令进程中提供 RabbitMQ 占位变量后启动已有 Redis 7.2 服务，没有启动 RabbitMQ 或清空 Redis 数据。
- Redis 启动后的首次算法测试显示“耗尽后期望 0，实际 11 milli-token” → 连续调用之间经过了数毫秒，脚本按设计累积了少量补充 → 将断言修正为余额处于 `[0, 1000)`，即不足以再放行一个完整请求。这个现象反向验证了 milli-token 没有丢失小数补充。

### 5. 为什么这样改

- `refillRate` 描述长期稳定速率，`capacity` 描述可承受突发，两者比固定窗口上限更贴合秒杀开抢时的 burst 形态。
- 没有选择只保存整数 token，因为频繁请求若每次都更新时间，会反复舍弃不足一个 token 的补充值；也没有直接保存浮点数，避免持久化和比较中的小数表达问题。
- 当前仅支持正整数 token/秒和固定每次消耗一个 token，满足本项目秒杀 GLOBAL 场景，未为未来可能性扩展可变成本或小数 refill 配置。
- Token Bucket 尚未接入 HTTP 请求，当前不能声称秒杀入口已经受 GLOBAL 限流保护，也没有经过容量压测来确定真实 refillRate/capacity。

### 6. 面试复盘

1. refillRate 和 capacity 分别控制什么？refillRate 控制长期平均速率，capacity 控制空闲后最多积累的突发额度。
2. 为什么保存 milli-token？在使用毫秒时间时保留不足一个完整 token 的补充值，避免高频请求不断舍入为零。
3. 补充公式是什么？`min(capacityMilli, oldMilli + elapsedMillis × refillRatePerSecond)`，因为每毫秒每 token/秒对应一个 milli-token。
4. 为什么拒绝请求也要更新时间和余额？否则同一段 elapsed 会在后续请求中被重复计算；保存 milli-token 可以在更新时间的同时保留小数额度。
5. key 为什么可以在空桶恢复时间后过期？到该时刻桶逻辑上已经满，删除后下次满桶初始化不会额外增加额度。
6. GLOBAL 为什么不能带 voucherId？它保护整个秒杀入口；按券分桶会把总容量拆成多个独立桶，所有券合计可能突破系统上限。

## [2026-09-16] 接口限流 Phase 4：重复注解与 Spring AOP 接入能力

### 1. 背景 / 问题

- Phase 2/3 只有两个可直接调用的算法执行器，Controller 若手工拼 key、选择算法和处理拒绝，会让入口代码与 Redis 限流细节耦合，也难以在同一方法上声明多条规则。
- 通用接口需要支持 USER、IP、GLOBAL 三种维度，但当前阶段只建设实际会使用的 Counter 和 Token Bucket，不扩展条件表达式、动态脚本或未落地算法。

### 2. 方案选择

- 引入可重复的 `@RateLimit` 与容器注解，字段只包含当前需要的 dimension、algorithm、resourceKey、执行 order、可选业务参数下标，以及两种算法各自的必要参数。
- Aspect 只负责寻找全部重复注解、按 order 排序并在业务方法前依次调用执行器；key 解析和算法分派分别交给 `RateLimitKeyResolver`、`RateLimitRuleExecutor`，避免切面堆积业务逻辑。
- IP 当前只读取 `request.getRemoteAddr()`。项目没有可信代理网段配置，因此明确忽略客户端可伪造的 `X-Real-IP` 和 `X-Forwarded-For`；未来接入 Nginx 时需先建立可信代理边界再扩展。

### 3. 核心实现

- Maven 增加 `spring-boot-starter-aop`；新增 annotation、aspect、core、enums 和正常超限异常，保持限流模块独立于原秒杀 Lua。
- key 固定以 `rate_limit:{algorithm}:{resource}:{dimension}` 开头；USER 自动追加 `UserHolder` 中的用户 ID，IP 追加连接对端地址，GLOBAL 不追加用户/IP。`keyArgumentIndex` 只为当前秒杀 USER 追加 voucherId，不引入 SpEL。
- 正常额度不足抛出 `RateLimitExceededException`；Redis/Lua 异常继续保留 `RateLimitInfrastructureException`。Phase 4 只建立异常语义，HTTP 429/503 映射留给 Phase 7。
- 新增 key 解析和规则分派测试，验证三种维度、算法参数传递、客户端 forwarded header 不受信任，以及额度不足使用独立异常类型。

### 4. 遇到的坑

- 本次未遇到需要记录的实际实现问题。首次编译需要从 Maven 仓库下载 `spring-boot-starter-aop` 和 AspectJ Weaver，依赖下载及编译均成功。

### 5. 为什么这样改

- 重复注解让入口规则可见且可组合；显式 order 避免依赖 Java 反射返回重复注解的偶然顺序。
- 没有采用 SpEL 或“大而全”的条件系统。当前唯一动态片段是 voucherId，使用参数下标足够直接，也避免把复杂表达式解析塞进切面。
- Spring AOP 的同类内部调用仍不会经过代理；本项目把注解放在由 Spring MVC 外部调用的 public Controller 方法上，并且 Controller 没有通过 `this` 自调用该方法，从结构上避开这一陷阱。

### 6. 面试复盘

1. 为什么需要重复注解？同一个秒杀入口同时有 GLOBAL 容量保护和 USER 防刷，两条规则参数、算法与 key 维度不同。
2. 为什么 Aspect 不直接拼 key 和执行 Lua？让切面只做横切编排，key 语义和算法分派可独立测试、维护。
3. 为什么需要显式 order？重复注解的发现顺序不应承担业务语义；GLOBAL/USER 的先后必须清晰且可测试。
4. Spring AOP 自调用为什么失效？代理只能拦截从代理对象外部进入的方法调用，`this.method()` 不经过代理；本项目注解放在 MVC 调用的 Controller 入口。
5. 为什么不直接相信 X-Forwarded-For？客户端可以自行构造该 header；只有请求确实来自可信代理时才能使用代理转发的真实地址。
6. 正常拒绝和 Redis 故障为什么用不同异常？前者是预期流控结果，后者是基础设施不可用，后续分别映射 HTTP 429 与 503，并采用不同日志级别。

## [2026-09-16] 接口限流 Phase 5：秒杀 GLOBAL + USER 两级限流接入

### 1. 背景 / 问题

- 原秒杀入口直接进入 `VoucherOrderServiceImpl`，首先通过 `RedisIdWorker` 生成订单 ID，之后才执行 `seckill.lua` 和 RabbitMQ 发送。恶意重复请求和整体洪峰会先消耗这些前置资源。
- 两种算法核心已经独立验证，本阶段需要把它们放在 Controller 业务调用之前，同时保持原秒杀库存、MQ 消费和 MySQL 事务职责不变。

### 2. 方案选择

- 在 `VoucherOrderController.seckillVoucher` 上声明两条规则，执行顺序固定为 GLOBAL Token Bucket → USER Sliding Window Counter → 原 Service。
- GLOBAL 使用不含 voucherId 的 `voucher-order:seckill` 资源 key；USER 使用当前用户 ID 并追加 voucherId，实现同用户同券分桶。
- 接受 GLOBAL 已扣 token 后 USER 拒绝造成的少量额度损耗，不增加回滚、补偿、混合 Lua 或分布式事务。

### 3. 核心实现

- GLOBAL 初始参数采用设计文档中的 `capacity=1500`、`refillRate=1000 token/s`；USER 采用 `limit=5`、`window=1000ms`。这些只是未压测的学习性初值，Phase 8 前不能视为生产容量结论。
- 登录拦截器 order=1 在 Controller 调用前完成认证，刷新拦截器 order=0 已先将用户写入 `UserHolder`；因此 USER key 在 AOP 执行时能够读取当前用户。
- AOP 代理测试严格验证 GLOBAL、USER、Controller 三段顺序。GLOBAL 拒绝时 USER 和 Service 均不执行；USER 拒绝时允许已消费一个 GLOBAL token，但 Service 不执行；基础设施异常同样 fail-closed。
- 因限流拒绝发生在 Controller 调用 Service 之前，所以 `VoucherOrderServiceImpl` 内部的订单 ID 生成、原 `seckill.lua` 和 RabbitMQ 发送均不会发生。原 Service、Listener、Redisson 锁与数据库事务代码未修改。
- 实测筛选套件共 45 个测试、0 failures、0 errors，包含 11 个新增 key/AOP/接入测试、10 个真实 Redis 算法测试及原秒杀迁移和事务回归；Maven compile 与 diff 格式检查通过。

### 4. 遇到的坑

- 本次未遇到需要记录的实际实现问题。测试实现复核时将三次独立创建的 Mockito 顺序校验器改为一个共享 `InOrder`，让 GLOBAL → USER → Service 的先后断言更严格；这属于测试表达增强，首轮功能测试本身已通过。

### 5. 为什么这样改

- GLOBAL 在前可以在系统整体过载时尽早拒绝，减少 USER key 创建和额外 Lua 执行；代价是 USER 随后拒绝时浪费一个近似治理 token，属于已接受 trade-off。
- 限流放在 Controller AOP 而不是 Service 内部，能够保证生成订单 ID、库存资格判断和 MQ 发送之前完成，也不会污染原秒杀业务职责。
- 没有修改消费者或数据库事务，因为入口限流不负责库存、一人一单、消息削峰或最终持久化正确性。

### 6. 面试复盘

1. 为什么 GLOBAL 在 USER 前？全局过载时尽早停止，避免为大量用户创建状态和继续执行 USER Lua。
2. GLOBAL token 被浪费需要补偿吗？不需要；限流额度是近似治理状态，不是库存、余额或订单数据。
3. 如何证明限流早于订单 ID和 MQ？Controller 代理测试验证拒绝时整个 Service 未调用，而这些动作全部位于 Service 方法内部。
4. USER key 为什么包含 voucherId？防止用户请求券 7 的频率占用券 8 的独立防刷额度。
5. GLOBAL key 为什么不包含 voucherId？它保护整个秒杀入口的合计承载能力，按券拆桶会放大总额度。
6. 当前参数可以直接用于生产吗？不可以；必须结合应用线程池、Redis、MQ 入队/消费、数据库吞吐和尾延迟压测确定。
7. 两级限流通过后谁保证不超卖？仍由原 `seckill.lua`、异步消费事务和数据库约束负责，限流只做入口流量治理。

## [2026-09-17] 接口限流 Phase 6：验证码 60 秒发送冷却

### 1. 背景 / 问题

- 原 `UserServiceImpl.sendCode` 在手机号格式校验后立即生成并保存验证码，同一手机号可以高频重复请求，既会反复覆盖验证码，也可能造成短信通道成本和滥用风险。
- 该需求的业务语义就是“同一手机号 60 秒只能成功取得一次发送资格”，不需要 Sliding Window、Token Bucket、AOP 条件或 SpEL。

### 2. 方案选择

- 在业务层直接调用 `setIfAbsent(cooldownKey, "1", 60, SECONDS)`，对应 Redis `SET key value NX EX 60`。不存在时原子写入并设置 TTL，已存在时返回 false。
- cooldown 与验证码内容使用两个 key：`login:code:cooldown:{phone}` 保存值 `1`、TTL 60 秒；`login:code:{phone}` 保存六位验证码、TTL 2 分钟，避免两种生命周期互相覆盖。
- Redis 返回 null、连接异常或验证码保存失败均包装为 `RateLimitInfrastructureException`，按 fail-closed 处理。验证码保存失败后不删除已取得的 cooldown，接受最多 60 秒暂时无法重试的保守取舍。

### 3. 核心实现

- `sendCode` 顺序固定为：手机号格式校验 → `SET NX EX 60` → 生成验证码 → 保存验证码 → 模拟/真实发送。非法手机号直接返回，不访问 Redis。
- `setIfAbsent=false` 抛出正常限流异常，后续由全局异常处理返回 HTTP 429；不能判定冷却状态时抛基础设施异常，不能继续生成或发送验证码。
- 新增 6 个单元测试，验证首次成功、重复拒绝、手机号隔离、非法手机号零 Redis 交互、Redis null/异常 fail-closed，以及验证码保存失败。
- 新增 4 个真实 Redis 测试，验证 cooldown 值和 TTL、NX 排他、重复请求不覆盖原验证码、不同手机号隔离及非法手机号不创建任何 key。

### 4. 遇到的坑

- 首次完整真实 Redis 回归连接 `127.0.0.1:6380` 全部失败 → Docker Desktop 未运行，自动启动又因其用户目录中的 `sailor-ingest.sock` 被占用而退出 → 为避免删除 Docker 内部文件，改用系统已有的原生 Redis 临时测试实例。
- 原生 Redis 版本为 3.0.504 → 验证码依赖的 `SET NX EX` 测试 4/4 通过，但 Phase 2/3 Lua 使用的多字段 `HSET` 在该旧版本中报参数数量错误 → 确认为测试 Redis 版本差异，不为兼容非项目版本修改已在 Redis 7.2 验证过的算法脚本；临时 Redis 测试进程已停止。

### 5. 为什么这样改

- 单命令 `SET NX EX` 同时完成“不存在才写”和 TTL，避免 `GET` 后 `SET` 的并发竞态，也避免先 `SETNX`、后 `EXPIRE` 两条命令之间崩溃留下永久 key。
- 没有为了框架统一给验证码套 `@RateLimit`。直接业务代码更贴合冷却语义，阅读时也能一眼确认格式校验和发送之间的严格顺序。
- cooldown 成功但验证码保存失败不是强一致业务数据问题，不引入事务或补偿 Lua；fail-closed 优先防止基础设施故障期间放大发送请求。

### 6. 面试复盘

1. `SET NX EX 60` 分别表示什么？NX 表示 key 不存在才写，EX 60 表示 60 秒后自动过期。
2. 为什么不能先 GET 再 SET？两个请求可能同时读到不存在并都继续发送，判断和写入必须原子。
3. 为什么不能分开 SETNX 和 EXPIRE？两条命令之间进程崩溃会留下没有 TTL 的永久冷却 key。
4. 为什么验证码不用统一注解？它是固定的手机号冷却业务步骤，业务层单命令更直接，统一框架反而需要条件和动态校验。
5. 为什么非法手机号必须先校验？避免无效输入创建大量 Redis key，也避免占用短信发送资格状态。
6. 保存验证码失败后为什么不删除 cooldown？删除补偿本身也可能失败，并可能在 Redis 抖动时重新打开高频入口；这里选择最多 60 秒的保守 fail-closed。

## [2026-09-17] 接口限流 Phase 7：HTTP 429 / 503 与日志语义

### 1. 背景 / 问题

- 原 `WebExceptionAdvice` 只捕获所有 `RuntimeException`，记录完整 ERROR 堆栈并返回普通 `Result.fail`。正常超限和 Redis/Lua 故障无法通过 HTTP 状态区分，且恶意高频请求会制造大量无价值 ERROR 堆栈。
- Phase 4 已区分 `RateLimitExceededException` 与 `RateLimitInfrastructureException`，本阶段需要把异常语义落实到 HTTP 协议和日志级别。

### 2. 方案选择

- 正常额度不足映射为 HTTP 429 Too Many Requests，返回统一失败 JSON，只记录一行 WARN，不附完整堆栈。
- Redis、Lua、空返回或验证码冷却状态不可判定映射为 HTTP 503 Service Unavailable，记录 ERROR 和完整异常堆栈。
- 保留原有其他 `RuntimeException` 处理方式，不借本次限流改造改变所有历史接口的异常协议。

### 3. 核心实现

- 在 `WebExceptionAdvice` 中增加两个更具体的 `@ExceptionHandler`，返回 `ResponseEntity<Result>` 显式设置 429/503；Spring 会优先选择与实际异常类型最匹配的处理方法。
- 正常限流响应为 `Result.fail("请求过于频繁，请稍后再试")`；基础设施故障响应为 `Result.fail("限流服务暂时不可用，请稍后重试")`，不向客户端暴露 Redis key、连接地址或内部异常。
- MockMvc 测试真实经过 MVC 异常解析，验证状态码和 JSON 结构，而不是只直接调用 advice 方法。

### 4. 遇到的坑

- 本阶段 HTTP 映射实现未遇到需要记录的实际问题。测试输出中的 503 ERROR 完整堆栈是设计要求，用于证明基础设施异常保留诊断上下文；429 仅输出简短 WARN。

### 5. 为什么这样改

- 429 表示服务正常工作但客户端请求过快，调用方可以等待后重试；503 表示当前服务依赖不可用，监控和调用方需要按系统故障处理。只返回 JSON 失败而维持 HTTP 200 会丢失协议语义。
- 正常超限不打印 ERROR 堆栈可以避免攻击流量反过来冲击日志 I/O；基础设施故障仍保留堆栈，便于定位 Redis 连接和 Lua 执行问题。
- 专用异常让算法、验证码业务和 HTTP 表达解耦：底层只抛语义明确的异常，不依赖 Servlet API 或直接构造响应。

### 6. 面试复盘

1. 429 和 503 分别代表什么？429 是请求速率超过策略，503 是服务当前无法可靠提供能力。
2. 为什么正常限流不能记 ERROR 堆栈？它是预期结果，高频打印会造成日志放大和 I/O 压力。
3. 为什么用 `ResponseEntity`？`Result.fail` 只描述响应体，`ResponseEntity` 才能同时明确 HTTP 状态码。
4. 多个 `@ExceptionHandler` 如何选择？Spring 按异常类型匹配更具体的处理方法，限流专用异常不会落入通用 RuntimeException handler。
5. 为什么响应不直接返回异常 message？内部 message 可能包含资源名、Redis key 或连接信息，应返回稳定、安全的客户端文案。
6. fail-closed 与 503 的关系是什么？依赖异常时不放行业务，并用 503 明确告诉调用方这是基础设施不可用而非正常超限。
