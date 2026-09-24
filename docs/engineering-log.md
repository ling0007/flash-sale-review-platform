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

## [2026-09-17] 接口限流 Phase 8：HTTP 组合验证与压测准备

### 1. 背景 / 问题

- Phase 2~7 已分别验证算法 Lua、并发原子性、key 解析、AOP 顺序、异常映射和验证码冷却，但此前 AOP 测试与 MockMvc 异常测试是分开的，缺少一次请求跨越 RefreshToken/Login 拦截器、MVC、AOP、Advice 和 Controller 的组合证据。
- GLOBAL/USER 参数仍是设计初值。压测会让 HTTP 200 请求继续进入真实订单 ID、`seckill.lua`、RabbitMQ 和数据库链路，不能在未准备隔离数据时直接执行。

### 2. 方案选择

- 不重复执行已经通过且代码未变化的 10 个真实 Redis 算法测试、43 个筛选回归和 4 个验证码 Redis 测试，只新增最小的 Web/AOP 组合测试。
- 使用 MockMvc 调用带 Aspect 的 Controller 代理，按生产顺序挂载真实 `RefreshTokenInterceptor`、`LoginInterceptor` 和 `WebExceptionAdvice`；登录 Redis、算法执行器和订单 Service 使用 mock，从而验证组合边界但不连接 Redis、MQ、MySQL。
- 压测准备选择独立 k6 脚本，提供 USER constant-vus 与 GLOBAL constant-arrival-rate 两种模式。脚本只采集 200/429/503，不内置未经实测的性能通过阈值。

### 3. 核心实现

- 新增 `SeckillRateLimitHttpIntegrationTest` 四个用例：USER 拒绝跨层返回 429 且 Service 零调用；基础设施异常返回 503 且 Service 零调用；两级规则通过后返回原业务结果；无 token 时由登录拦截器返回 401，且限流器和 Service 都不调用。
- 新增 `scripts/load-test/seckill-rate-limit.js`，通过环境变量配置模式、URL、voucherId、token、到达率、VU 和持续时间，并分别统计 allowed、limited、unavailable 与 unexpected 响应。
- 新增压测 README，明确隔离环境、专用券/账号、Redis 7.2、分级升压、清理策略和全链路观测项。GLOBAL 场景要求足够多的独立 token，减少 USER 规则对结果解释的干扰。
- Phase 8 不新增 Redis 数据结构，也不修改两份 Lua：线上仍由 Sliding Window Hash 保存 `previous_count/current_count/current_window_start_ms`，Token Bucket Hash 保存 `tokens_milli/last_refill_ms`；Lua 的时间、加权/补充、判断、写回与 TTL 步骤仍由 Phase 2/3 测试负责。
- 新增组合测试实测 4 tests、0 failures、0 errors。当前环境未安装 k6，因此只完成脚本和执行清单，没有实际发压，也没有调整 1500/1000、5/1000ms 等参数。

### 4. 遇到的坑

- 本阶段新增组合测试及真实拦截器链增强均通过，没有发生需要记录的功能问题。测试 503 路径输出完整 ERROR 堆栈符合 Phase 7 的日志设计，不是测试失败。
- 检查压测工具时发现当前机器未安装 k6，因此没有伪造脚本执行结果；脚本需要在准备好隔离环境与 k6 后按 README 执行。

### 5. 为什么这样改

- 分层测试回答“各部件是否正确”，组合测试回答“部件连接后是否仍保持 HTTP 语义”；只保留四个跨层用例，避免把算法公式和并发测试在更慢的 Web 测试中再复制一遍。
- 没有选择完整 `@SpringBootTest` 连接 Redis、RabbitMQ 和 MySQL：那会重复算法与基础设施测试、扩大副作用，并让失败原因难定位；MockMvc 组合测试保留了本阶段真正关心的拦截器顺序、代理调用和 HTTP 映射。
- 压测脚本不设置主观 P95/QPS 门槛。合理阈值需要结合应用线程池、Redis、RabbitMQ、消费者和 MySQL 的同步观测，而不是先写一个必过数字。
- 没有在当前环境直接压测，因为放行请求会产生真实业务副作用。准备可重复脚本与安全清单比对未知环境盲目发压更符合工程边界。

### 6. 面试复盘

1. 单元、集成和压测分别回答什么？单元验证局部逻辑，组合集成验证层间契约，压测验证容量、延迟和拐点。
2. 为什么组合测试 mock Redis 算法？Lua 与真实 Redis 已单独验证；这里关注 MVC/AOP/Advice 连接，避免重复测试和外部依赖干扰。
3. 为什么验证 Service 零调用？订单 ID、秒杀 Lua 和 MQ 全在 Service 内，零调用直接证明入口拒绝足够早。
4. 为什么 GLOBAL 场景需要多个 token？token 太少会先频繁命中 USER 规则，429 状态本身无法区分是哪一级拒绝。
5. 为什么使用 constant-arrival-rate？它控制入口到达速率，更适合观察 Token Bucket 的 refillRate 和 capacity，而不是让 VU 自由跑出不稳定吞吐。
6. 为什么没有给出最终阈值？尚未执行隔离环境全链路压测，任何生产 QPS 或延迟结论都会是编造。

## [2026-09-17] 接口限流 Phase 9：工程日志与面试材料收敛

### 1. 背景 / 问题

- Phase 2~8 已持续更新日志与面试笔记，如果在最终阶段重新复制全部算法说明，会形成多份容易失真的重复材料。
- 当前需要把“已完成能力、验证证据、尚未执行事项”收敛到最新状态，使后续复习不会把压测准备误读为已完成容量验证。

### 2. 方案选择

- 保留每个 Phase 的原始六段式工程记录，只新增 Phase 8/9 事实，不覆盖或重写历史决策。
- `interview-notes.md` 继续承担考前速记：更新当前亮点、测试证据、阅读路径、高频问题和不足，不复制 Lua 的逐步解释。
- 明确区分“代码功能完成”“自动测试通过”“脚本已准备”“真实压测未执行”四种状态。

### 3. 核心实现

- 将当前限流主线更新为 Phase 2~8：两种 Redis Lua 算法、AOP、秒杀接入、验证码 cooldown、HTTP 429/503、跨层组合测试和压测准备。
- 增加组合测试与压测方法相关面试题，并把 `SeckillRateLimitHttpIntegrationTest`、k6 脚本及 README 加入推荐阅读路径。
- 当前不足聚焦仍需完成的事实：未执行真实压测、参数未校准、普通 IP 能力尚未选择接口接入、可信代理解析未配置。

### 4. 遇到的坑

- 本阶段为文档收敛，没有发生新的实现问题。复核时主动避免把此前不同命令的测试数量简单相加成一次“全量测试”，分别保留各阶段真实运行证据。

### 5. 为什么这样改

- 工程日志保留过程证据，面试笔记提炼结论；两者职责不同。只维护一份最终速记可以降低参数、限制和完成状态相互矛盾的风险。
- 明确未验证项比包装成“高并发优化完成”更可信，也为下一步真实容量测试提供清晰入口。

### 6. 面试复盘

1. 如何证明项目不是只写了限流代码？有算法单元测试、Redis 集成测试、AOP/HTTP 组合测试和可执行压测准备，各层证据职责不同。
2. 可以说系统支持 1000 QPS 吗？不能；1000 是当前 refillRate 初值，不是压测结论。
3. 当前主线真正完成到哪里？功能与自动化验证完成到跨层 HTTP，压测脚本已准备但真实容量验证未执行。
4. 为什么文档也属于工程交付？它记录边界、取舍和验证事实，避免维护者把限流当作库存正确性或误用未校准参数。
5. 下一步最重要的工作是什么？在隔离环境执行分级压测，观测全链路指标后调整 USER/GLOBAL 参数并记录结果。

## [2026-09-21] SQL 与索引 Phase 1：真实库基线及暂缓建索引

### 1. 背景 / 问题

- 店铺券列表按 `shop_id`、`status` 过滤并左连接秒杀券；热门笔记按 `liked DESC` 分页。两条查询在代码中没有对应的过滤或排序索引，需要先核对真实数据库，而不能依据建表文件推断现网索引与数据量。
- 本阶段只分析这两条读查询，并核对秒杀一人一单的已有唯一索引；不进行 HTTP 压测或缓存改造。

### 2. 方案选择

- 对本机 `127.0.0.1:3306/hmdp` 的 MySQL 8.0.27 只读查询 `COUNT(*)`、`SHOW INDEX`、数据分布和 `EXPLAIN`。固定券列表 `shopId=1`、热门笔记第一页 `LIMIT 0,10`，保留原 SQL 与计划，供同条件复核。
- 把 `tb_voucher(shop_id, status)` 和热门笔记的 `liked` 排序索引作为候选评估；真实表目前过小，不为得到“优化”结果而建索引。`tb_voucher_order(user_id, voucher_id)` 已有唯一索引，保持不变。

### 3. 核心实现

- 实测行数：`tb_voucher=1`、`tb_seckill_voucher=0`、`tb_blog=4`、`tb_voucher_order=0`。券表唯一记录为 `shop_id=1,status=1`；笔记 `liked` 分布为 `13:1、11:2、2:1`。`SHOW INDEX` 确认券、秒杀券、笔记均仅有各自主键；订单表有主键及两列顺序为 `user_id,voucher_id` 的 `uk_voucher_order_user_voucher`。券和订单索引显示的 `Cardinality=0` 是当前统计估计，不能解释成字段没有不同值。
- 优惠券 Mapper 原 SQL（以 `shopId=1` 代入参数）：

```sql
SELECT v.`id`, v.`shop_id`, v.`title`, v.`sub_title`, v.`rules`, v.`pay_value`,
       v.`actual_value`, v.`type`, sv.`stock`, sv.begin_time, sv.end_time
FROM tb_voucher v
LEFT JOIN tb_seckill_voucher sv ON v.id = sv.voucher_id
WHERE v.shop_id = 1 AND v.status = 1;
```

| 优化前表 | type | possible_keys | key | rows | filtered | Extra |
| --- | --- | --- | --- | ---: | ---: | --- |
| v | ALL | NULL | NULL | 1 | 100.00 | Using where |
| sv | eq_ref | PRIMARY | PRIMARY | 1 | 100.00 | NULL |

- 热门笔记首页与 MyBatis-Plus 分页计数的基线 SQL：

```sql
SELECT id, shop_id, user_id, title, images, content, liked, comments, create_time, update_time
FROM tb_blog ORDER BY liked DESC LIMIT 0, 10;
SELECT COUNT(*) AS total FROM tb_blog;
```

| 优化前查询 | type | possible_keys | key | rows | filtered | Extra |
| --- | --- | --- | --- | ---: | ---: | --- |
| 热门笔记分页 | ALL | NULL | NULL | 4 | 100.00 | Using filesort |
| 分页计数 | index | NULL | PRIMARY | 4 | 100.00 | Using index |

- 本次没有修改 SQL、业务代码或数据库索引，因此不存在“优化后 EXPLAIN”或性能增幅。以上基线是执行计划与数据规模记录，不是接口响应时间或吞吐 benchmark。

### 4. 遇到的坑

- 本机 3306 可连接，但 PATH 中没有 `mysql` 客户端，Docker daemon 也未运行；改用现有 Python `mysql.connector` 执行相同的只读 SQL，成功取得真实库结果。没有因此修改数据库或应用配置。

### 5. 为什么这样改

- `v` 缺少过滤索引而显示 `ALL`，但当前仅 1 行，扫描成本极低；`sv` 通过 `voucher_id` 主键做 `eq_ref` 连接。`(shop_id,status)` 满足 WHERE 最左前缀的候选条件，但只有在代表性数据规模和实际读取压力下减少扫描、且收益覆盖维护成本时才值得新增。
- 热门笔记的 `Using filesort` 目前只排序 4 行；`liked` 在点赞接口会更新。给它建索引会增加每次点赞更新成本，且宽字段仍需回表，不能仅凭出现 filesort 就认定值得建索引。
- 一人一单查重同时按 `user_id`、`voucher_id` 等值过滤，已有唯一索引可按列顺序匹配；不增加同列冗余索引。若未来数据增长，应使用相同 SQL、相同数据与环境重新采集 EXPLAIN，再决定是否修改并复测。

### 6. 面试复盘

1. 为什么券列表 `type=ALL` 仍不加索引？真实表只有 1 行；访问类型只是线索，必须结合 `rows`、数据分布及写入代价判断。
2. `sv` 的 `eq_ref` 说明什么？对 `v.id` 的每一行，通过 `sv.voucher_id` 唯一主键最多匹配一行。
3. 为什么候选联合索引顺序是 `(shop_id,status)`？查询以店铺为主要过滤维度，两列均等值过滤；`shop_id` 作为前导列还能支持只按店铺查询，但最终仍要看真实选择性与工作负载。
4. `filtered=100` 是否代表查询很快？不是；它是估计行通过条件的比例，仍需连同扫描行数和执行情况解读。
5. 为什么热门笔记 `Using filesort` 不立即建索引？当前仅 4 行，排序开销小；`liked` 经常更新，索引有写成本，取完整笔记还可能回表。
6. `Cardinality=0` 能证明索引无效吗？不能；它是索引统计估计，在空表或极小表上尤其不能代替实际数据分布。
7. 一人一单为什么不再建 `(voucher_id,user_id)`？当前查重同时给定两列，现有 `(user_id,voucher_id)` 唯一索引已经匹配并承担最终约束；反向索引需要独立的查询证据。

## [2026-09-24] 商铺缓存 Phase 2：冷启动与热缓存性能验证

### 1. 背景 / 问题

- `GET /shop/{id}` 当前通过 `ShopServiceImpl.queryById` 调用 `CacheClient.queryWithPassThrough`。命中 `cache:shop:{id}` 时直接返回 Redis JSON；未命中时通过 MyBatis-Plus `getById` 查询 MySQL，再写入 Redis。
- 当前实现没有协调同一 key 的并发重建。需要用真实请求区分缓存失效瞬间与热缓存稳态，观察是否出现数据库请求放大，而不是临时关闭缓存制造无缓存对照组。

### 2. 方案选择

- 在既有 `scripts/load-test` 下新增参数化 JMeter 计划，显式传入 `BASE_URL`、`shopId`、`threads`、`rampUp` 和 `duration`；保留原 k6 限流脚本。
- 本地选择真实存在的 `shopId=1`。应用、JMeter、MySQL 8.0.27、Redis 7.2 位于同一机器；两轮都使用 100 threads、0 秒 ramp-up、15 秒 duration。正式测试前用 `shopId=2` 初始化 DispatcherServlet、Hikari 连接池和相关类，避免把应用首次初始化只计入冷缓存结果。
- MySQL 使用 `performance_schema.events_statements_summary_by_digest` 前后差值统计 `tb_shop WHERE id = ?`；Redis 使用 `INFO commandstats` 的 GET/SETEX 累计值差值。JMeter 使用非 GUI 模式并生成 JTL 与 HTML 报告。

### 3. 核心实现

- 缓存 key 是 `cache:shop:{shopId}`，正常对象 TTL 为 30 分钟，空值 TTL 为 2 分钟。数据库查询发生在 `queryWithPassThrough` 的 `dbFallback.apply(id)`，正常结果通过 `CacheClient.set` 使用 SETEX 写回；`ShopServiceImpl.update` 在数据库更新后删除同一 key。
- 测试库 `tb_shop` 实测 14 行，ID 范围 1~14。本次只读取 `shopId=1`，没有修改商铺数据。
- JMeter HTML 报告结果如下。Throughput 是这一次固定本地负载下的完成速率，不代表已找到系统容量上限。

| 场景 | Threads | Samples | Throughput(/s) | Avg(ms) | P95(ms) | P99(ms) | Error Rate | MySQL 查询增量 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 冷启动/缓存失效瞬间 | 100 | 22,616 | 1,532.87 | 64.92 | 156 | 296 | 0.00% | 12 |
| 热缓存稳态 | 100 | 37,994 | 2,567.34 | 38.70 | 128 | 161 | 0.00% | 0 |

- 冷启动前 `EXISTS cache:shop:1=0`。该轮 Redis GET 增量 22,616、SETEX 增量 12，和 MySQL 主键查询增量 12 一致，说明多个请求在首个缓存写入完成前同时读到未命中并各自查库、写回。测试结束后 key 已存在，TTL 为 1,779 秒。
- 冷启动首个 1 秒窗口包含 459 个样本，Avg 245.84ms、P95 499ms、范围 23~546ms；随后第 2 秒 Avg 80.83ms/P95 158ms，第 3 秒 Avg 78.61ms/P95 136ms。这里的逐秒值由 JTL 按请求开始时间分桶，用来描述恢复过程；表格中的 P95/P99 来自 JMeter HTML 全轮统计。
- 热缓存轮开始前再次预热并确认 key 存在、TTL 为 1,763 秒。该轮 Redis GET 增量 37,994、SETEX 增量 0，MySQL 查询、检查行及返回行增量均为 0，证明稳态请求全部走现有 Redis 读取路径。

### 4. 遇到的坑

- 当前 PATH 没有 `redis-cli`，因此通过项目 Redis 容器内的客户端执行 DEL、EXISTS、TTL 与 INFO，不影响应用使用的同一 Redis 实例。
- 首次生成指标快照时，Python MySQL 驱动把聚合结果返回为 `Decimal`，直接 JSON 序列化失败；将四个累计字段显式转换为整数后重新采集。失败发生在正式压测前，没有丢失或污染测试窗口。
- 仓库指定的 RabbitMQ 3.13 镜像当时未完成拉取；本阶段接口不使用 MQ，但应用启动会声明 RabbitMQ 拓扑，因此两轮统一使用本机已有的 RabbitMQ 3.8 临时容器满足启动依赖。该差异不改变商铺 Redis/MySQL 调用链，但结果只代表本次本地环境。

### 5. 为什么这样改

- 实验确认当前 Pass Through 在热缓存下能完全隔离该接口的 MySQL 主键查询，且两轮均无错误；这是对既有缓存价值的验证，不是本阶段新增缓存后的性能提升。
- 冷启动确有 12 倍数据库查询放大，但只出现在同一 key 的失效窗口；测试库仅 14 行，主键查询很轻，缓存很快恢复。单次本地实验尚未证明该放大会让数据库或接口达到不可接受状态，因此本阶段不修改业务代码。
- 互斥锁可减少同 key 重建查询，但会让未获得锁的请求等待/重试并增加锁管理；逻辑过期可降低热点 key 过期抖动，但接受短期旧数据且需要预热与异步重建。当前证据不足以承担这些语义和复杂度，保留现有 Cache-Aside / Pass Through。
- 冷启动与热缓存的总体吞吐和延迟可以用于说明本轮现象，但不能直接当作“缓存带来固定百分比提升”：冷启动轮的大多数请求在 key 建立后也已转为热读，且实验没有构造无缓存版本或寻找系统容量拐点。

### 6. 面试复盘

1. 为什么不能把删除缓存后的整轮称为冷缓存？第一次成功查询就会写回，后续请求已经进入缓存恢复或热缓存状态。
2. 如何证明发生缓存击穿式放大？缓存失效后同一 SQL 的 MySQL digest 增加 12，Redis SETEX 同样增加 12，而测试对象只有一个 key。
3. 为什么热缓存轮能证明数据库被隔离？37,994 次请求对应 37,994 次 Redis GET，而 MySQL 查询、检查行和返回行增量均为 0。
4. 为什么本阶段不立刻加互斥锁？虽然观察到 12 次并发查库，但没有数据库压力异常或错误证据；锁会增加等待、重试及故障边界。
5. Cache-Aside 更新为何先更新数据库再删缓存？数据库是事实来源，删除缓存让下一次读取重建；仍需认识并发读写窗口可能产生短暂不一致。
6. 为什么关注 P95/P99 而不只看 Avg？冷启动少量慢请求会被大量恢复后的快请求稀释，尾延迟更能反映失效瞬间的用户体验。
7. 这次 Throughput 能否写成系统支持 2,567 QPS？不能；它只是单机、固定 100 线程和 15 秒窗口下的完成速率，没有分级升压寻找容量拐点。

## [2026-09-24] 秒杀 Phase 3：限流验证、逐级请求与最终订单对账

### 1. 背景 / 问题

- 当前秒杀入口依次经过登录、GLOBAL/USER 限流、`seckill.lua`、RabbitMQ 和 MySQL。HTTP 返回订单 ID 发生在异步消费之前，需要用实际请求和最终订单对账来验证完整链路。
- 已有 k6 脚本只按 HTTP 状态计数，无法区分 HTTP 200 中的 Lua 接受与业务失败；仓库尚无 JMeter 秒杀计划和多账号 CSV 方案。

### 2. 方案选择

- 保留正式限流阈值：GLOBAL Token Bucket `capacity=1500/refillRate=1000/s`，USER Sliding Window Counter `limit=5/window=1000ms`。k6 验证限流；JMeter 每账号单请求，逐级使用 10、200、500 线程，单轮请求数低于 GLOBAL 初始桶容量，不覆盖阈值。
- 使用本地专用券 ID 10、600 个带标记的独立测试账号/token；每轮仅在主队列和错误队列清空后重置该券订单、库存及 Redis 购买资格。环境为本机应用与 JMeter、MySQL 8.0.27、Redis 7.2、RabbitMQ 3.13。测试券和账号只服务于本轮，结果报告保存在 `target/load-test`。
- RabbitMQ Management API 每 100ms 采样 ready/unacked，并记录 publish/ack 计数；消费结束后核对 Redis 库存、资格 Set、MySQL 库存、订单数、去重用户数和错误队列。

### 3. 核心实现

- 秒杀链路：`POST /voucher-order/seckill/{id}` → `RateLimitAspect` 按 GLOBAL 后 USER 检查 → `VoucherOrderServiceImpl` 生成订单 ID、执行 `seckill.lua` → 发送到 `hmdp.seckill.direct` / `seckill.order.create` / `hmdp.seckill.order.queue` → `VoucherOrderListener` → `VoucherOrderPersistenceService` 事务内查重、条件扣库存、插单。Lua 返回 `0/1/2` 分别表示接受、库存不足、重复购买；Redis 使用 `seckill:stock:{voucherId}` 和 `seckill:order:{voucherId}`。消费者重试耗尽后转发到 `hmdp.seckill.error.queue`；数据库 `(user_id,voucher_id)` 唯一索引兜底。
- 扩展原 k6 脚本，统计 HTTP 200 的 `accepted_responses` 与 `business_failed_responses`；USER 模式可用 `ITERATIONS` 限制总请求数，保留原持续模式。新增 `seckill-capacity.jmx`、无真实 token 的 CSV 模板、RabbitMQ 观察脚本，并在原压测 README 中写入初始化、运行、对账与清理步骤。JMeter 将 429 视为正常限流，将 503 和连接/其他异常视为错误。
- USER 正确性：同一账号 10 次请求，HTTP 200 为 5 次（其中 1 次返回订单 ID、4 次一人一单业务失败），HTTP 429 为 5 次，503 为 0；最终 Redis/MySQL 库存均从 5000 降到 4999，订单和购买资格均为 1，主队列清空、错误队列为 0。
- GLOBAL 观察：k6 恒定到达率目标 2500/s、持续 5s，实际完成 11,491 次、丢弃 1,010 次未发出的迭代。HTTP 429 为 4,927，503 为 0；HTTP 200 为 6,564，其中 600 次返回订单 ID、5,964 次一人一单业务失败。600 个账号被轮换复用，429 混合了 USER/GLOBAL 两种规则，因此本轮**不能单独计算 GLOBAL 拒绝率**。最终 600 单全部落库，两端库存均为 4400，主队列清空，错误队列为 0。
- JMeter 每线程使用一个独立 CSV token，循环仅一次。`duration=60` 是最大允许时间，各轮在请求完成后提前结束；TPS 是短批次完成速率，不是持续承载能力。

| 阶段 | Threads | Requests | TPS | Avg(ms) | P95(ms) | P99(ms) | 429 | 503 | 业务失败 | Lua 接受 | 最终订单 | 网络/其他错误 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| A 链路正确性 | 10 | 10 | 15.04 | 72.20 | 138 | 138 | 0 | 0 | 0 | 10 | 10 | 0 |
| B 中并发 | 200 | 200 | 352.73 | 165.04 | 314.95 | 333.99 | 0 | 0 | 0 | 200 | 200 | 0 |
| C 较高并发 | 500 | 500 | 704.23* | 501.60 | 684.95 | 707 | 0 | 0 | 0 | 188 | 188 | 312 次连接拒绝 |

- `*` Stage C 的 704.23/s 包含 312 次未建立 HTTP 连接的失败请求，不能作为业务 TPS；188 次接受请求在该报告中的完成速率为 264.79/s，也不代表稳定容量。错误率为 62.4%。Stage A/B 错误率均为 0。
- 每轮库存与资格对账：A 初始库存 10，结束 Redis/MySQL 均为 0、资格 Set 10；B 为 200→0、资格 Set 200；C 为 500→312、资格 Set 188。三轮 `COUNT(DISTINCT user_id)` 分别为 10/200/188，重复订单组数均为 0。主队列 publish/ack 各轮增量分别为 10/200/188；最终 ready/unacked 均为 0，错误队列始终为 0。
- RabbitMQ 100ms 采样在 B/C 的 publish/ack 计数上覆盖了整轮，采样峰值 ready/unacked 均为 0；只能说明采样点未观察到积压，不能排除两次采样之间出现短暂排队。A 的观察器启动时间早于 JMeter 较长的初始化阶段，采样未覆盖该轮发布；A 的最终 publish/ack 增量从两次队列快照核对均为 10。
- 本轮没有逐档采集 JVM CPU/GC、Tomcat 线程/accept queue、MySQL 活跃连接与慢查询增量、Redis 命令延迟；因此能够对账正确性与定位连接失败阶段，不能完成具体资源瓶颈归因。后续若研究入口边界，应补这些同步指标并使用独立压测机。

### 4. 遇到的坑

- PowerShell 调用 Windows `jmeter.bat` 时，未加引号的 `-JtokenCsv=...csv` 被拆分并报 `Unknown arg: .csv`；将整个 `-JtokenCsv=...` 用双引号包裹后，Stage A/B/C 均正常读取 CSV。
- Stage A 首次 8 秒 RabbitMQ 观察窗口在 JMeter 完成初始化前结束，未覆盖消息发布。延长观察窗口后仍因启动相位错开无法用采样证明 A 的瞬时峰值，因此保留 publish/ack 前后累计值并如实标记峰值不可得。
- Stage C 中 312 次请求出现 `HttpHostConnectException: Connection refused`，另外 188 次正常返回订单 ID。应用进程在测试后仍监听 8081，RabbitMQ 188 条消息全部 ACK，MySQL 最终 188 单，Redis/MySQL 库存一致。现有证据只能定位到本机 HTTP 连接建立边界；不能仅凭本次结果认定是 Tomcat backlog、Windows 网络栈或某个依赖的根因。按逐级实验规则停止升压。
- 清理专用数据时，临时脚本逐个启动 Docker CLI 删除 600 个已过期的 USER 限流 key，执行过慢；核对 SQL 删除和 600 个 token 的批量删除已完成后，停止该脚本。USER 限流 key 的 2 秒 TTL 已到期，扫描为 0；最终复核专用券/订单/用户均为 0，600 个 token 均不存在。临时凭证 CSV 和元数据已删除，只保留脱敏的 JTL/HTML/聚合结果。

### 5. 为什么这样改

- 10 与 200 请求两档证明在该隔离数据和本地负载下，Lua 接受数、MQ publish/ack、Redis 资格与最终订单数一致，且无超卖、重复订单、错误队列消息。500 线程档揭示本机入口连接失败，但已接受的 188 单仍完整落库；这更准确地划定了当前实验边界。
- 本阶段不修改正式限流、Lua、MQ 或 MySQL 业务代码：没有证据指向 Redis、MQ 消费或数据库瓶颈，也没有足够证据为 HTTP 连接拒绝选择具体代码改法。后续若研究入口连接容量，应独立测量应用线程/accept queue、操作系统连接状态和 JMeter 机器资源，使用更平缓的 ramp-up 与独立压测机复核。
- 原有生产者发送没有 Confirm/Return 或补偿，Lua 预扣与 MQ 发送之间仍有丢单窗口；MySQL 提交和 ACK 也非原子。本轮成功对账不等于这些故障窗口已被消除。

### 6. 面试复盘

1. 返回订单 ID 是否等于最终下单成功？不是；还要等 RabbitMQ 消费并核对 MySQL 订单。
2. 429 能否计入系统错误率？限流策略正常拒绝应单独统计；503 和连接失败才表示基础设施或网络异常。
3. 为什么 k6 的 4,927 次 429 不能全算 GLOBAL？同一 600 个 token 被反复轮换，USER 规则也会拒绝，且 k6 丢弃了 1,010 个未发出的迭代。
4. 如何证明没有超卖或重复订单？核对初始库存减最终订单数等于 Redis/MySQL 剩余库存，资格 Set 与订单数一致，并检查 `(user_id,voucher_id)` 重复组为 0。
5. 500 线程档的 704.23/s 能否称作秒杀容量？不能，其中 312 次未建立 HTTP 连接；该档只能说明本机入口在这种瞬时连接模型下出现失败。
6. ready/unacked 采样峰值为 0 是否证明 RabbitMQ 从未积压？不能，100ms 采样可能漏掉更短的积压；publish/ack 总量与最终队列状态提供的是另一层证据。
7. 为什么没有立即调整 Tomcat 或限流配置？已确认连接边界，但未分离本机网络、JMeter 与应用 accept queue 的贡献；正式限流在 JMeter 三档均未提前截断请求。
8. 完整对账是否消除了发送可靠性风险？没有。没有生产者 Confirm/Return，Lua 预扣与 MQ 发送也不是原子步骤；本轮只能证明实测成功路径。

## [2026-09-24] 秒杀连接拒绝阶梯复测：200 至 500 线程

### 1. 背景 / 问题

- Phase 3 的 500 线程短批次有 312 次 `Connection refused`，但缺少同步的应用、JVM、连接池、Windows TCP 与发压机 CPU 指标，无法说明拒绝从何处开始。

### 2. 方案选择

- 沿用原 `seckill-capacity.jmx`、正式限流阈值和每线程一个独立账号、一次请求的模型。专用券与 500 个标记账号逐档重置；200、300、400、500 线程都设 `rampUp=1s`、`duration=60s`（实际一次性请求提前完成）。同机应用/JMeter、MySQL 8.0.27、Redis 7.2、RabbitMQ 3.13 不变。
- 隔离启动时仅启用 Tomcat/Hikari JMX MBean；新增只读本地 Attach 与 Windows 主机采样器。第一轮找边界，第二轮补整机 CPU 并验证边界是否重复。每轮 JTL 记录连接拒绝，接受数与最终订单、库存对账。采样峰值只统计与 JTL 请求起止时间重叠的窗口。

### 3. 核心实现

- `scripts/load-test/ObserveJvm.java` 读取 Tomcat `currentThreadsBusy/currentThreadCount/maxThreads`、Hikari `ActiveConnections/ThreadsAwaitingConnection`、JVM 进程 CPU、堆、GC 和线程数；`observe-seckill-host.ps1` 读取整机/JMeter 进程 CPU 与 8081 端口的客户端/服务端 TCP 状态。采样器输出到忽略的 `target/load-test`；README 记录复现命令和采样局限。业务代码、限流阈值与秒杀 JMeter 计划均未改变。
- 两轮每档均为 0 个 429、0 个 503、0 个业务失败；下表错误均是 JTL 中 `HttpHostConnectException: Connection refused`。四档按顺序执行，第一轮 200 档因主机 CPU 指标未就绪另补一次 200 复测；第二轮四档均同步记录整机 CPU。

| 档位 | 第一轮拒绝/请求 | 第二轮拒绝/请求 | 第二轮 Tomcat busy/max 采样峰值 | 第二轮 JVM 线程峰值 | 第二轮 Hikari active 峰值 | 第二轮整机 CPU 峰值 | 第二轮 JVM CPU 峰值 | 第二轮 JMeter 进程 CPU 峰值* | 第二轮 JVM Heap 峰值 | 第二轮 GC 增量 | 第二轮客户端 ESTABLISHED/TIME_WAIT 峰值 |
| --- | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |
| 200 | 0/200 | 0/200 | 6/200 | 217 | 1 | 80.2% | 15% | 599.1% | 105.8 MiB | 1 次 / 2ms | 200 / 200 |
| 300 | 76/300 | 188/300 | 34/200 | 218 | 1 | 91.1% | 7% | 841.6% | 107.5 MiB | 1 次 / 3ms | 112 / 200 |
| 400 | 126/400 | 75/400 | 180/200 | 297 | 1 | 98.8% | 15% | 603.9% | 104.8 MiB | 1 次 / 2ms | 325 / 312 |
| 500 | 330/500 | 58/500 | 200/200 | 309 | 1 | 98.7% | 11% | 360.1% | 103.8 MiB | 1 次 / 3ms | 442 / 637 |

- `*` JMeter 进程 CPU 以一个逻辑核为 100%；JVM CPU 是 `ProcessCpuLoad`，以整机可用 CPU 为分母。500 档主机采样仅命中 2 个请求窗口采样点，CPU 峰值可能明显低估。100ms 目标采样频率在同机 CPU 争用下无法保证，Tomcat 和 TCP 的短尖峰也可能漏采。第二轮 `TIME_WAIT` 的档前基数已从 200 上升到 637 左右，峰值低于千级；不能据此推断临时端口耗尽。
- 第一轮 200/300/400/500 的 Lua 接受与最终订单分别为 200/224/274/170；第二轮分别为 200/112/325/442。每档 Redis 资格数、MySQL 订单数相等，Redis/MySQL 剩余库存均为 `500-接受数`，订单去重用户数与订单数相等。最终 RabbitMQ 主队列 ready/unacked 和错误队列均为 0。

### 4. 遇到的坑

- 首次探测 JMX 时 Tomcat MBean 未暴露、Hikari 池尚未初始化，采样器显示 `NA`。在隔离应用启动时启用 Tomcat/Hikari MBean，并先用独立请求触发 Hikari 后，相关指标可读；正式业务配置未改变。
- 第一版 Hikari MBean 过滤条件误把 `type=Pool (HikariPool-1)` 当成精确 `type=Pool`，导致采样为空；按真实 ObjectName 修正并复测。第一轮主机采样器未记录整机 CPU，所以补做了第二轮完整阶梯。
- 同样的 300/400/500 档连接拒绝数量在两轮中显著波动，且不随档位单调上升；采样间隔也在整机高负载时变长。因此只能把**本机、本配置的首次观测拒绝档**定位在 200 与 300 之间，不能写成稳定的 300 线程上限或单一组件根因。

### 5. 为什么这样改

- 300 档第二轮 Tomcat 忙线程峰值只有 34/200，却有 188 次连接拒绝，Hikari 活跃峰值 1、GC 仅 3ms，说明这些失败发生在业务落库之前，不能归因数据库连接池或 GC。整机 CPU 91.1%、JMeter 进程最多消耗约 8.4 核，且 400/500 档整机峰值接近 99%，同机发压资源争用是重要干扰。
- 500 档又采到 Tomcat 忙线程 200/200；采样无法读取操作系统 accept queue，也可能漏掉更短的线程峰值。因此**不能断言只是 JMeter/Windows 网络问题，也不能断言只是 Tomcat 配置问题**。当前证据把问题定位在本机突发 HTTP 建连/接入边界；若需精确拆分，应改用独立压测机并采集服务端 accept queue/网络拒绝计数复测。
- 不修改 Tomcat、限流或秒杀业务配置：现有数据足以说明短批次本机建连边界，却不足以选择有把握的最小配置修改。被接受的请求仍完整落库，本轮不宣称新的持续容量或性能提升。

### 6. 面试复盘

1. 拒绝从哪档开始？本机两轮 200 档为 0，300 档分别为 76/300、188/300；这是观测区间，不是稳定容量上限。
2. 为什么 300 档 Tomcat busy 34/200 仍不能证明 Tomcat 无关？采样可能漏掉毫秒级峰值，而且没有 accept queue 计数。
3. 为什么不能将高 CPU 直接归因 JMeter？应用与 JMeter 同机，整机 CPU 统计包含两者；JMeter 进程 CPU 明确显示发压端占多核，但服务端接入层也可能同时受影响。
4. Hikari active 峰值 1 说明什么？本轮数据库连接池没有观测到明显占满，不能解释连接建立前的 `Connection refused`；该峰值同样受采样间隔限制。
5. `TIME_WAIT` 约 637 能证明端口耗尽吗？不能，还要看可用临时端口范围、占用总数和连接失败时的系统错误；这里数值本身没有提供此证据。
6. 为什么拒绝数不随 200→500 单调增加？同机线程调度、JMeter 发起时序与采样相位会改变瞬时建连波形，短批次没有形成稳定负载；应多次复测并分离发压机。
7. 为什么不能把成功的 442/500 写成系统支持 442 TPS？这是单次请求数，另有 58 次连接拒绝；请求完成速率与持续稳定容量是不同指标。

## [2026-09-24] 性能工作 Phase 4：证据、工程决策与面试表述收敛

### 1. 背景 / 问题

- SQL 基线、商铺缓存冷/热实验、秒杀正确性对账和两轮连接拒绝阶梯复测已经分别记录。最终展示需要明确区分“本轮新增的实验方法”“项目原有能力的验证”与“尚未解决的风险”，避免把本地短批次完成速率或未发生的索引/缓存改动写成优化收益。

### 2. 方案选择

- 不再压测或修改业务架构。工程日志保留原始条件与结果；面试笔记整理简历 bullet、30 秒/2 分钟讲述、问答与学习优先级；根 README 只给项目阅读者一个简洁的性能验证入口，复现命令继续留在 `scripts/load-test/README.md`。

### 3. 核心实现

- SQL：券表 1 行、笔记表 4 行；即使有 `ALL`/`Using filesort`，仍保留原索引和 SQL。`(shop_id,status)` 与排序索引只作未来代表性数据下的候选，现有 `(user_id,voucher_id)` UNIQUE 继续承担一人一单约束。
- 缓存：已有 Pass Through 的热读轮 37,994 次请求对应 MySQL 查询 0；失效窗口 MySQL 和 SETEX 各增加 12。该证据支持缓存价值与失效风险的描述，不构成本轮新增缓存的性能提升百分比。
- 秒杀：10/10 与 200/200 独立请求最终落单；两轮阶梯均在 300 线程档首次出现连接拒绝，而拒绝数不稳定。结论限定为本机瞬时建连/接入边界，不称作持续容量，也不单独归因 Tomcat、Windows 或 JMeter。
- 新增的是参数化 JMeter、账号 CSV、k6 业务分类、Redis/MQ/MySQL 对账和同步资源采样等**实验能力**；Redis 商铺缓存、Lua、RabbitMQ 异步订单、UNIQUE 和两级限流是**原有能力的验证**。本阶段只编辑文档，没有新 benchmark 或业务代码改动。

### 4. 遇到的坑

- 本次文档收敛未遇到需要记录的实际问题；实验中的真实异常与定位过程保留在各阶段日志，不重新编造成新的踩坑。

### 5. 为什么这样改

- 统一“证据 → 决策 → 边界”的口径，防止将 Phase 1 写成索引提速、将 Phase 2 写成新增 Redis 提速，或将 704.23/s、442/500、200 线程无失败写成秒杀容量。没有足够证据支持联合索引、缓存锁或 Tomcat 调参，因此保留现状。
- 已知 Redis 预扣与 MQ 发送不原子、无生产者 Confirm/Return、MySQL commit 与 MQ ACK 不原子、错误转发无发布确认且错误队列不会自动修复 Redis 状态；成功路径压测未消除这些故障窗口。SQL 判断受 1/4 行小样本限制，本地同机测试不能外推生产容量。

### 6. 面试复盘

1. 这一轮到底“优化”了什么？新增可复现测量与对账能力，验证原有链路并作出暂缓改动的工程决策；没有凭空新增索引或缓存机制。
2. `ALL`/`Using filesort` 出现时为什么保留原 SQL？实际只有 1/4 行，读收益缺证据，写入维护成本真实存在。
3. 缓存轮能写“性能提升”吗？不能；缓存原本就存在，冷轮还会在首个写回后转热，只能描述两种状态和 MySQL 压力差异。
4. 返回 orderId 能否证明订单成功？不能；要等待 MQ 消费并核对最终 MySQL 订单、Redis 资格和两端库存。
5. 为什么 200 线程无拒绝不是容量？单批次、单机、一次请求模型未测持续稳态与独立发压；300 档已有波动拒绝。
6. 能否说瓶颈必然是 Tomcat？不能；整机/JMeter CPU 高，500 档 busy 采到上限，但 300 档未采到忙线程打满，且缺 accept queue 指标。
7. 成功对账是否保证消息不丢？不保证；生产者发送及错误转发没有确认，Redis/MQ 与 MySQL/ACK 均非原子。
