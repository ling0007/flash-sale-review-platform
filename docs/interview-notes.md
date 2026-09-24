# 项目面试复盘

来源：[工程演进日志](engineering-log.md)、[限流方案权衡](黑马点评限流改造_工程权衡与面试问答.md)、[2026-09-08 秒杀 RabbitMQ 迁移审计](任务总结/seckill-rabbitmq-migration.md)、[2026-09-09 消费端可靠性与幂等](任务总结/seckill-consumer-reliability.md)。以当前代码为准，旧方案保留为演进历史。

初学者按 [秒杀 RabbitMQ 面试导读](interview-study-guide/seckill-interview-study-guide.md) 阅读源码、理解知识点、练习成段回答；本文保留考前速记。

## 当前项目亮点

Redis Lua 原子抢购资格与预扣库存，RabbitMQ JSON 消息异步下单，Listener 委托业务 Service，Redisson 用户锁覆盖完整的数据库事务提交。移除手写 Stream 消费和 pending 恢复；修复真实运行路径缺少事务的问题。

限流主线 Phase 2~8 已实现 Redis Lua Sliding Window Counter、Token Bucket、重复注解 + Spring AOP、秒杀两级限流、验证码 60 秒 cooldown、HTTP 429/503，以及登录拦截器+MVC+AOP+Advice 组合验证。秒杀入口固定按 GLOBAL Token Bucket → USER Sliding Window Counter 检查；任一拒绝或 Redis 异常都不会进入订单 ID、原秒杀 Lua 和 RabbitMQ 链路。Phase 3 已用 k6 验证 USER 限流并观察到多账号混合 429，正式阈值仍未经过持续容量校准。

消费端增加三次 stateless 本地尝试和错误消息转发；数据库 UNIQUE(user_id, voucher_id) 作为一人一单最终约束。唯一键冲突必须先完成事务回滚，再在外层核实业务键，避免库存已扣但重复订单插入失败仍提交。

消费可靠性阶段曾完成 23 个筛选测试与 Maven package，覆盖真实并发 UNIQUE 冲突、有限重试、错误消息上下文和消费者连接重建。限流阶段另有两种算法的 Redis 7.2 集成证据、验证码真实 Redis 测试及 43 个筛选回归；Phase 8 只补跑此前缺少的 4 个 HTTP 组合用例并全部通过，没有重复执行未变化套件。Phase 3 已有短批次性能与正确性实验，但没有寻找持续容量拐点，不声称吞吐提升或生产容量。

SQL 与索引 Phase 1 已在真实 MySQL 8.0.27 上保存券列表和热门笔记首页的 EXPLAIN 基线。当前券表 1 行、笔记表 4 行，分别出现 `ALL` 和 `Using filesort`，但没有足够证据支持增加索引；本阶段保留原索引，也没有优化后性能数据。原 SQL、索引核对及完整执行计划见 [2026-09-21 工程日志](engineering-log.md)。

商铺缓存 Phase 2 使用 JMeter 对现有 `GET /shop/{id}` 做了真实验证：100 threads、0 秒 ramp-up、15 秒窗口下，冷启动轮 22,616 个样本、0 错误，缓存建立前出现 12 次 MySQL 主键查询和 12 次 Redis SETEX；热缓存轮 37,994 个样本、0 错误，MySQL 查询增量为 0，Redis GET 增量与请求数一致。热缓存轮的 2,567.34/s 只是该固定本地实验的完成速率，不是容量结论；完整条件和尾延迟见 [2026-09-24 工程日志](engineering-log.md)。

秒杀 Phase 3 在专用券与账号上验证了真实 HTTP → 限流 → Lua → RabbitMQ → MySQL 链路。JMeter 10/200 独立账号两档均全部落单，500 线程瞬时起跑时 312 次连接建立失败、188 次请求被接受并全部落单；三档 Redis/MySQL 库存均与接受数一致，没有重复订单或错误队列消息。此结果定位了本机高并发 HTTP 连接边界，尚不能归因具体组件，也不能宣称系统持续容量；详见 [2026-09-24 秒杀工程记录](engineering-log.md)。

## 性能工作最终收敛（对应工程日志 Phase 1～3 与阶梯复测）

这轮工作的顺序是**候选问题 → 真实基线 → 同条件实验/对账 → 判断改动收益 → 保留现状并说明边界**。下面数值均为本地实验值，不是生产容量或本轮新增功能带来的提升。

| 模块 | 实验与关键数据 | 发现 | 工程决策 |
| --- | --- | --- | --- |
| SQL | `tb_voucher=1` 行，券列表 `type=ALL,rows=1`；`tb_blog=4` 行，热门笔记 `Using filesort` | 数据量不足以证明瓶颈；订单现有 `(user_id,voucher_id)` UNIQUE 已匹配查重 | 保留索引；`(shop_id,status)` 和点赞排序索引只作未来候选，不存在优化后 EXPLAIN |
| 商铺缓存失效 | 100 threads、22,616 samples、1,532.87/s、P95 156ms、P99 296ms、0 错误；MySQL/SETEX 各 12 次；首秒 P95 499ms | 同一 key 有短暂并发回源 | 保留现有 Pass Through；目前无证据值得引入互斥锁或逻辑过期 |
| 商铺热缓存 | 100 threads、37,994 samples、2,567.34/s、P95 128ms、P99 161ms、0 错误；Redis GET 37,994 次，MySQL 查询 0 | 已有缓存稳态隔离该接口 MySQL 查询 | 作为缓存机制的验证，不宣称本轮新增缓存或固定百分比提升 |
| 秒杀正确性 | 10/10、200/200 独立账号返回 orderId 并最终落库；库存、Redis 资格、MQ publish/ack、订单对齐 | 成功路径在该测试规模下闭环，无重复订单、错误队列为空 | 保留现有 Lua/MQ/事务/唯一约束；orderId 不代表已持久化 |
| 秒杀建连边界 | 两轮 200 档均 0 拒绝；300 档分别 76/300、188/300 拒绝；400/500 档拒绝数波动 | 本机瞬时建连首次观测拒绝区间为 200～300 threads；同机整机 CPU 高，500 档 Tomcat busy 曾达 200/200 | 不盲调 Tomcat；无法从本地短批次推出持续容量或单一组件根因 |

本轮**新增的是实验能力**：参数化 JMeter、独立账号 CSV 场景、k6 业务结果分类、Redis/MySQL/RabbitMQ 对账、MQ 观察脚本、JVM/Tomcat/Hikari JMX 与 Windows 主机采样及复现步骤。**被验证的原有能力**是商铺 Redis 缓存、秒杀 Lua、RabbitMQ 异步落单、数据库唯一约束、GLOBAL/USER 限流；不要在简历中写成这一轮才新增。原始条件和过程留在工程日志与 [压测 README](../scripts/load-test/README.md)。

### 推荐简历表述

| 推荐程度 | 可直接使用的 bullet | 真实证据与常见追问 |
| --- | --- | --- |
| 高 | 针对商铺详情缓存失效和热读设计 JMeter 对照实验，结合 Redis 命令与 MySQL 查询增量定位同 key 的 12 次并发回源；热缓存轮 37,994 次请求对应 MySQL 查询 0 次，据此保留现有缓存实现并记录失效窗口风险。 | 固定 100 threads/15 秒、0 错误；追问冷轮为何并非全程冷缓存、P95/P99、为什么不加锁。 |
| 高 | 为既有秒杀链路搭建多账号参数化压测和 Redis/RabbitMQ/MySQL 对账，验证 10/10 与 200/200 独立请求从 Lua 接受到最终落库、库存与资格一致，区分 HTTP 返回 orderId 和持久化完成。 | 专用券、独立 token、主/错误队列及重复订单核对；追问限流干扰、消息确认与故障窗口。 |
| 中 | 对秒杀短批次 `Connection refused` 补做两轮 200→500 阶梯采样，两轮均在 300 线程档首次观察到拒绝；关联同机 CPU、Tomcat、Hikari、GC 与 TCP 状态，把问题界定为本机 HTTP 建连/接入边界并避免无依据调参。 | 200 档两轮均 0 拒绝，300 档 76/188 次；追问拒绝数波动、accept queue 缺失、为何不是容量结论。 |

SQL/索引判断更适合作为面试故事：`ALL` 和 `Using filesort` 都有真实基线，但表仅 1/4 行，本轮**没有**索引性能提升可写。上述 `/s` 均为固定本地条件的完成速率，不应改写为“系统支持 XX QPS”。

### 面试讲述

**30 秒：**“这轮我做的是有证据的性能验证。先用真实表数据量、`SHOW INDEX` 和 `EXPLAIN` 判断券列表与热门笔记目前不值得加索引；再用 JMeter 验证已有商铺缓存，热读 37,994 次请求对应 0 次 MySQL 查询，同时发现失效瞬间 12 次并发回源；最后用独立账号压秒杀并对账 MQ、Redis、MySQL，10 和 200 请求档全部落库。补充阶梯实验发现本机 300 线程档开始出现连接拒绝，但没有把短批次结果写成生产容量，也没有盲目调参。”

**2 分钟：**“我先从真实调用链里选券列表和热门笔记做 SQL 候选，核对实际表只有 1 行券和 4 行笔记。虽然执行计划分别有 `ALL` 和 `Using filesort`，当前扫描/排序成本很低，点赞字段还存在更新索引的写入代价，因此保留索引。接着固定商铺详情 `/shop/{id}`，把缓存失效瞬间和热缓存稳态分开测试：失效后首批并发使 MySQL 和 Redis SETEX 各增加 12 次；热缓存轮 37,994 个请求对应 MySQL 查询 0。这里验证的是项目原有缓存的价值，不是本轮新增缓存后的提速。秒杀部分，我用每账号一次请求避开一人一单和 USER 限流的干扰，并把 HTTP 接受、RabbitMQ publish/ack、Redis 资格/库存和 MySQL 订单逐项对账；10 和 200 档全部最终落库。较高线程档出现的是 HTTP 建连拒绝，于是补两轮 200 到 500 的同步资源采样：两轮 200 都无拒绝、300 开始有拒绝，但数量波动；JMeter 与应用同机，整机 CPU 较高，500 档 Tomcat 忙线程也曾达到 200/200，没有 accept queue 数据。我把结论限定为本机建连/接入边界，没有把 704.23/s 或成功请求数当作系统持续容量。整个过程的决策是：有证据才改，证据不足时保留现状并讲清适用边界。”

### 本轮知识学习顺序

| 优先级 | 知识点与面试价值 | 阅读位置 | 应掌握到的深度 |
| --- | --- | --- | --- |
| 必须 | EXPLAIN 与索引成本：常问“ALL/filesort 为何不加索引” | `VoucherMapper.xml`、`BlogServiceImpl.queryHotBlog/likeBlog`、Phase 1 工程日志 | 能结合 `type/key/rows/filtered/Extra`、真实 1/4 行、选择性、最左前缀及 `liked` 更新成本解释暂缓 |
| 必须 | Cache-Aside 与失效窗口：能讲真实缓存链路 | `ShopServiceImpl.queryById/update`、`CacheClient.queryWithPassThrough/set`、Phase 2 日志 | 画出命中、未命中回源写 TTL、更新删 key；解释 12 次回源与为何不加锁 |
| 必须 | 压测指标语义：防止把平均值或短批次 TPS 当容量 | `shop-cache.jmx`、`seckill-capacity.jmx`、压测 README | 解释 P95/P99、0 错误、冷/热状态、请求完成速率与持续容量的区别 |
| 必须 | 限流与业务拒绝分离：面试常追问 429 的归因 | `VoucherOrderController`、`RateLimitAspect`、`seckill-rate-limit.js` | 讲清 GLOBAL→USER 顺序、429/503/200 业务失败/Lua 接受四类及账号复用的干扰 |
| 必须 | Lua 原子预扣与返回语义：秒杀链路入口 | `seckill.lua`、`VoucherOrderServiceImpl.seckillVoucher` | 讲清库存、一人一单检查/预扣原子性和 0/1/2 返回码；知道它不保证后续 MQ/MySQL 成功 |
| 必须 | 异步消息与最终落单对账：体现端到端思维 | `VoucherOrderListener`、`VoucherOrderPersistenceService`、Phase 3 日志 | 区分 orderId 与 DB 订单；能复述 publish/ack、ready/unacked、错误队列、库存/订单数核对 |
| 必须 | MySQL 事务与幂等：面试追问重复消息和超卖 | `VoucherOrderPersistenceService`、订单 UNIQUE 迁移、Phase 3 日志 | 解释条件扣库存、事务回滚、唯一键兜底及 MySQL commit/ACK 非原子 |
| 必须 | 建连故障归因边界：体现不夸大结论 | `ObserveJvm.java`、`observe-seckill-host.ps1`、阶梯复测日志 | 能说明两轮 200→300 首次拒绝、采样遗漏、同机 CPU 干扰、Tomcat/Hikari 指标不能单独定根因 |
| 有余力 | 热门笔记排序与分页计数成本 | `BlogServiceImpl.queryHotBlog/likeBlog`、Phase 1 EXPLAIN | 能讨论 `liked` 索引可能避免排序但增加更新成本，宽列回表仍存在 |
| 有余力 | RabbitMQ 重试与错误队列限制 | `VoucherOrderListener`、MQ 配置、消费可靠性日志 | 讲清有限重试/错误转发与缺少 Confirm/Return、自动回补之间的差别 |
| 有余力 | Windows TCP 状态与 accept queue | 主机采样器、阶梯复测日志 | 知道 TIME_WAIT 累积不等于端口耗尽，ESTABLISHED 与已建连接相关，accept queue 本轮未测 |
| 有余力 | 测试数据与环境控制 | 压测 README、Phase 2/3 日志 | 说明专用券、独立 token、同条件对照、清理和本地单机实验的外推限制 |

## 技术选型与取舍

- 券列表的 `tb_voucher(shop_id,status)` 是待数据规模增长后重新验证的候选；秒杀券关联已使用 `voucher_id` 主键 `eq_ref`。当前 1 行券表全表扫描不构成已证实的瓶颈。
- 热门笔记按 `liked DESC` 分页，当前 4 行的 `Using filesort` 不足以证明建索引划算；点赞会更新 `liked`，应同时衡量索引维护成本。秒杀一人一单继续使用现有 `(user_id,voucher_id)` 唯一索引，不叠加同列冗余索引。
- 商铺详情使用 Cache-Aside / Pass Through：key 为 `cache:shop:{id}`，正常 TTL 30 分钟，未命中按主键查 MySQL 并写回，更新商铺后删缓存。真实冷启动出现 12 次同 key 并发查库，但当前小数据环境没有错误或不可接受的数据库压力证据，因此保留现状，不引入互斥锁或逻辑过期。
- 秒杀容量实验保持正式 GLOBAL `1500/1000/s`、USER `5/1000ms` 阈值；JMeter 每 token 单请求的 10/200/500 三档均未遇到 429。k6 USER 10 次请求得到 5 次 429、5 次进入业务；k6 多账号 GLOBAL 场景虽有 4,927 次 429，但账号复用使 USER/GLOBAL 拒绝混合，不能单独计算 GLOBAL 拒绝率。500 线程档的连接拒绝发生在 HTTP 建连阶段，已接受的 188 单仍全部经 MQ 落库。

- Redis 保留 Lua、缓存、登录、GEO；仍使用 7.2，并统一 Redisson/Spring Redis 连接配置。先前 Stream 版本兼容、ACK 修复已在历史日志中保留，当前代码不再有该消费链路。
- RabbitMQ DirectExchange 精确匹配订单创建路由，持久拓扑由代码声明。独立 VoucherOrderMessage 防止把数据库实体当消息协议。
- 消费重试使用 stateless：事务在 retry callback 内部开启，每次异常先回滚，下一次尝试开启新事务。只因“业务有事务”就选择 stateful 是不准确的，还要看重试与事务的嵌套关系。
- RepublishMessageRecoverer 在三次尝试耗尽后保留原 JSON、异常栈和原路由，发送到持久错误队列；没有自动回放。仍不启用 Confirm/Return、生产者发送重试或补偿，Redis/MQ 两步调用和错误转发仍有失败窗口。
- USER/IP 限流选择 Sliding Window Counter：相比固定窗口减轻边界突刺，相比 ZSET Sliding Window Log 不保存逐请求 member。公式为 `previousCount × (1 - 当前窗口进度) + currentCount`；决策时还要加上本次请求。它是近似流量治理状态，不负责库存正确性。
- 限流时间统一来自 Lua 内的 Redis `TIME`，窗口和 TTL 使用毫秒。Hash TTL 为两倍窗口；状态残缺、Redis 时间回拨、脚本空返回或未知返回码均按基础设施异常处理，不静默放行。
- 秒杀 GLOBAL 选择 Token Bucket：Redis Hash 保存 `tokens_milli` 和 `last_refill_ms`，`1000 milli-token = 1 token`。公式为 `min(capacityMilli, oldMilli + elapsedMillis × refillRatePerSecond)`；使用整数定点数保留毫秒级补充，并用 capacity 限制最大 burst。
- Token Bucket key 的 TTL 至少 1 秒，并覆盖空桶恢复满桶所需时间。key 过期时桶已在逻辑上恢复为满桶，因此下次满桶初始化不会额外放大突发。所有 voucherId 必须共用同一个 GLOBAL key。
- `@RateLimit` 支持重复声明，Aspect 按显式 order 逐条执行；key 解析、算法分派与切面编排分离。秒杀 GLOBAL key 不含券 ID，USER key 包含 `userId + voucherId`。不写混合 Lua，也不补偿少量治理额度损耗。
- 当前项目没有可信反向代理配置，IP 维度只使用连接对端 `getRemoteAddr()`，不信任客户端传入的 `X-Real-IP` / `X-Forwarded-For`。
- 验证码不套通用注解：手机号校验成功后直接执行 `SET cooldownKey 1 NX EX 60`，再生成、保存和发送验证码。cooldown 与验证码内容使用两个独立 key；Redis 异常默认 fail-closed。
- 正常限流通过专用异常返回 HTTP 429 并只记录 WARN；Redis/Lua/冷却状态异常返回 HTTP 503 并记录 ERROR 堆栈。响应使用固定客户端文案，不暴露内部异常信息。
- HTTP 组合测试使用 MockMvc 调用带 Aspect 的 Controller 代理，挂载真实 RefreshToken/Login 拦截器与 Advice，验证未登录时在 AOP 前返回 401、429/503 时 Service 零调用、全部规则通过时返回原业务结果；算法与 Redis 细节仍由更低层测试负责，避免重复。
- k6 脚本区分 USER constant-vus 与 GLOBAL constant-arrival-rate，统计 200/429/503。脚本不内置未经实测的性能门槛，真实执行必须使用隔离环境、专用券和测试账号。

## AOP 基础速记

- AOP 用于集中处理散落在多个业务入口前后的横切逻辑，例如限流、日志、权限和事务。本项目用它把限流检查放到 Controller 方法执行前，Controller 方法体无需手工调用限流器。
- `@RateLimit` 只是运行时元数据，本身不会执行代码；`RateLimitAspect` 读取它并执行规则。`@Aspect` 声明切面，`@Around` 声明环绕通知，表达式是切点，`ProceedingJoinPoint` 表示本次方法调用。
- `joinPoint.proceed()` 才会继续执行原 Controller 方法。限流异常发生在它之前，所以可以直接阻断订单 ID、秒杀 Lua 和 MQ 链路；遗漏 `proceed()` 则正常请求也不会进入业务。
- Spring AOP 基于代理：外部调用代理对象时才能拦截；同类中的 `this.method()` 不经过代理。本项目由 Spring MVC 从外部调用 public Controller 方法，因此不会走同类自调用路径。
- 重复注解在底层由 `RateLimits` 容器承载，Aspect 使用 Spring 的合并注解工具一次取出全部规则，再按显式 order 排序，不能把反射返回顺序当成业务顺序。

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
10. Sliding Window Counter 如何估算最近窗口？使用上一窗口计数乘以尚与最近窗口重叠的比例，再加当前窗口计数；本项目判断 `estimated + 1 <= limit` 才放行。
11. 为什么不用 ZSET Sliding Window Log？USER/IP 防刷不需要保留每次请求时间戳；Counter 每个 key 固定三个字段，代价是接受上一窗口内分布均匀这一近似假设。
12. Redis Lua 已经原子，为什么仍说算法有误差？Lua 消除并发读改写竞态；误差来自两桶加权模型本身，两者不是同一问题。
13. Token Bucket 的 refillRate 和 capacity 有何区别？前者控制长期平均速率，后者控制最多可积累多少突发额度，不能只配一个参数代替另一个。
14. 为什么不用整数 token？毫秒级频繁请求可能每次只补充不足一个 token；若只保存整数并更新时间，这部分额度会不断丢失，所以使用 milli-token 定点数。
15. 为什么拒绝后也更新 lastRefill？本次 elapsed 已经兑换成余额；不更新会让下一请求重复计算同一段时间。小数余额由 milli-token 保存，不会因为更新时间而丢失。
16. 为什么 GLOBAL key 不包含 voucherId？GLOBAL 保护整个秒杀入口；按券分桶会让每张券各自获得一套 capacity/refillRate，合计流量可能突破系统容量。
17. 为什么用重复注解而不是一个注解塞两套参数？GLOBAL 和 USER 的算法、key 维度与参数不同，独立规则更清楚，也能复用到普通接口。
18. 如何保证两条规则顺序？注解提供显式 order，Aspect 收集全部重复注解后排序；测试验证 GLOBAL → USER → Controller。
19. AOP 为什么放 Controller？它是 HTTP 入口治理，能保证订单 ID、秒杀 Lua 和 MQ 都尚未执行；MVC 外部调用代理方法，也避开同类自调用问题。
20. 为什么允许 GLOBAL token 被 USER 拒绝后浪费？限流额度是近似治理状态，不值得为此引入混合 Lua、补偿或分布式事务。
21. 如何证明被限流后没有进入原链路？代理测试在 GLOBAL 拒绝、USER 拒绝和基础设施异常三种情况下都验证 `IVoucherOrderService` 零调用；相关动作全部位于该 Service 内。
22. 为什么验证码使用 SET NX EX 而不是 AOP？手机号 60 秒冷却是固定业务步骤，单条原子命令最贴合语义；套统一框架会增加条件解析和抽象成本。
23. 为什么不能 GET 后再 SET cooldown？并发请求可能同时读取到 key 不存在；NX 必须让判断和写入成为一次 Redis 原子操作。
24. 验证码 cooldown 和验证码内容为什么分两个 key？一个控制 60 秒发送资格，一个保存 2 分钟登录凭证，生命周期和职责不同。
25. 429 与 503 有什么区别？429 是策略正常拒绝，503 是依赖故障导致服务无法可靠判断；客户端重试和监控告警语义不同。
26. 为什么 429 不打印完整 ERROR 堆栈？正常限流可能非常频繁，打印堆栈会放大攻击流量对日志系统的压力；503 则需要堆栈诊断故障。
27. 为什么还要做 HTTP 组合测试？独立测试只能证明各层各自正确；组合测试验证登录拦截器先建立用户上下文、MVC 调用代理，以及异常穿过 AOP 后仍由 Advice 转成正确状态码。
28. 为什么组合测试不再连接真实 Redis？算法 Lua 已有 Redis 集成测试；组合测试只关注 Web/AOP 契约，重复引入 Redis 会降低定位效率。
29. constant-vus 和 constant-arrival-rate 如何选择？前者适合固定并发用户反复请求的 USER 场景，后者适合按目标到达速率观察 GLOBAL Token Bucket。
30. 当前可以宣称限流支持多少 QPS？不能。refillRate=1000 是学习性初值，必须经过隔离环境全链路压测才能形成容量结论。
31. `EXPLAIN` 出现 `type=ALL` 就必须加索引吗？不必。券表当前只有 1 行，先结合 `rows`、选择性与真实读取压力判断；没有代表性数据时不能宣称索引有收益。
32. `Using filesort` 是否必然是热门笔记瓶颈？不是。当前仅排序 4 行；还要考虑 `liked` 更新带来的索引维护成本，以及分页计数和逐条补充用户信息的链路开销。
33. 一人一单查重为什么不补反向联合索引？当前条件同时指定 `user_id` 和 `voucher_id`，现有同列唯一索引已经匹配；没有仅按 `voucher_id` 查询的证据。
34. 为什么删除缓存后的整轮不能叫冷缓存？首个请求写回后，后续请求已经命中缓存；应单独观察首批请求和 MySQL 查询增量。
35. 如何证明热缓存确实保护了数据库？本次热缓存轮 37,994 次 Redis GET，MySQL 对应查询增量为 0，而不是只凭接口延迟猜测。
36. 冷启动出现 12 次查库为什么仍保留现状？它证明存在并发重建窗口，但本地 14 行数据下没有错误或明显数据库压力；互斥锁和逻辑过期各自带来等待或一致性成本，需要更强证据。
37. 为什么不能声称系统支持 2,567 QPS？这只是固定线程和短持续时间下的完成速率，没有分级升压、资源拐点及持续稳定性验证。
38. 秒杀 HTTP 200 返回订单 ID 后还要核对什么？等待主队列 ready/unacked 清空，核对 MQ publish/ack、错误队列、MySQL 订单、Redis/MySQL 库存和一人一单资格。
39. 为什么 429 不算系统错误？它是限流策略正常拒绝；503、连接失败和无法解释的响应要另算。HTTP 200 还需按 `success` 与 `data` 区分业务失败和 Lua 接受。
40. 为什么 500 线程的 704.23/s 不是秒杀容量？其中 312 个请求连接失败，188 个被 Lua 接受；短批次请求也不能证明持续稳定吞吐。
41. RabbitMQ 采样 ready/unacked 为 0 是否能证明毫无积压？不能。100ms 采样可能漏掉短暂队列，需同时看 publish/ack 差值和最终队列状态。
42. k6 GLOBAL 场景有 429，为什么不能全部归因 GLOBAL？脚本轮换复用 600 个 token，USER 规则也会拒绝；此外到达率发生器丢弃的迭代未形成 HTTP 请求。
43. 补充阶梯复测把连接拒绝定位到哪档？相同短批次模型下，200 线程两轮都是 0，300 线程两轮分别拒绝 76/300、188/300；可说首次观测区间在 200 到 300，不能说系统上限是 300 线程。
44. 为什么不能直接说瓶颈是本地压测机？整机 CPU 在第二轮 300/400/500 档峰值分别为 91.1%/98.8%/98.7%，JMeter 进程占用多核，但 500 档 Tomcat busy 也采到 200/200。两者同机，未测 accept queue；需要独立压测机进一步区分。
45. 为什么基本排除数据库连接池与 GC 是本轮连接拒绝的直接原因？Hikari active 采样峰值每档都是 1、无等待线程，GC 增量只有 1 次、2~3ms；异常是 HTTP 连接建立阶段的 `HttpHostConnectException`。采样结论只限于本轮窗口。
46. `TIME_WAIT` 与 `ESTABLISHED` 应怎样解读？ESTABLISHED 与本轮建立的连接数一起看；TIME_WAIT 含前一档残留且最高采到数百，不能单凭其增长断言临时端口耗尽。
47. 本项目的 Cache-Aside 读写路径是什么？`ShopServiceImpl` 调用 `CacheClient.queryWithPassThrough`：先读 `cache:shop:{id}`，未命中查 MySQL 并以 30 分钟 TTL 写回；更新商铺成功后删除 key。缓存失效瞬间可有多请求同时查库。
48. 为什么要同时看 Avg 与 P95/P99？冷启动首秒 Avg 245.84ms、P95 499ms，而整轮 Avg 64.92ms；大量缓存恢复后的快请求会稀释短时尾延迟。热缓存整轮 P95/P99 为 128/161ms。
49. `seckill.lua` 的原子性保证到哪里？库存判断、一人一单资格判断与 Redis 预扣在同一 Lua 执行内；返回 0/1/2 分别代表接受/库存不足/重复购买。Lua 不覆盖 RabbitMQ 发送及 MySQL 事务，不能证明端到端原子性。
50. 消费者为何仍需数据库幂等？MQ 可重投，消费者必须在事务内查重、条件扣库存并受 `(user_id,voucher_id)` UNIQUE 兜底；唯一键冲突需先回滚，再在事务外核实业务键，避免多扣库存。
51. Redis 与 MySQL 库存怎样对账？每轮记录初始库存、Lua 接受数、Redis `seckill:stock:*`、资格 Set、MySQL 库存和订单/去重用户数；等主队列 ready/unacked 清空后验证两端剩余库存均为初始值减接受数。成功路径对账不消除发送故障窗口。
52. Tomcat `maxThreads=200` 是否意味着最多支持 200 个连接？不是；它限制工作线程，连接还可能等待接入/处理。阶梯复测的 500 档 busy 曾达 200/200，但 300 档 busy 采样未满也发生拒绝，且未测 accept queue，不能凭这一项断定根因。
53. 为什么不直接调大 Tomcat 参数？两轮拒绝数波动、JMeter 与应用同机且整机 CPU 高，既可能有发压端争用，也可能有服务端接入排队；未测 accept queue，调参收益与代价无从验证。本轮只保留诊断结论。

## 重点代码与动手练习

阅读 VoucherOrderController 上的两条 `@RateLimit`、RateLimitAspect、RateLimitKeyResolver、RateLimitRuleExecutor，以及两种限流器的 `tryAcquire` 和 Lua；再阅读 UserServiceImpl.sendCode、WebExceptionAdvice、SeckillRateLimitHttpIntegrationTest。压测部分阅读 `scripts/load-test/seckill-rate-limit.js` 与同目录 README，最后沿 VoucherOrderServiceImpl、Listener 和 PersistenceService 复习限流之后的职责边界。

在专用 vhost 运行 SeckillConsumerReliabilityTest，重点阅读 temporaryInsertFailuresRetryInFreshTransactions 和 databaseUniqueRaceRollsBackLoserEvenWithoutRedisLock：解释每次重试的事务边界，以及为什么唯一键失败没有留下额外扣库存。练习将临时故障次数从 2 改为 1，并相应断言两次尝试；仍不要开启生产者重试。

SQL 与索引 Phase 1 阅读 `VoucherMapper.xml`、`BlogServiceImpl.queryHotBlog`、`BlogServiceImpl.likeBlog` 与 `VoucherOrderPersistenceService.orderExists`，对照 [工程日志中的真实库 EXPLAIN](engineering-log.md) 解释候选索引为何暂缓。

商铺缓存 Phase 2 阅读 `ShopController.queryShopById`、`ShopServiceImpl.queryById/update`、`CacheClient.queryWithPassThrough/set` 和 `RedisConstants`，再用 `scripts/load-test/shop-cache.jmx` 重现实验，重点解释冷启动中 12 次查库与热缓存中 0 次查库分别证明什么。

秒杀 Phase 3 阅读 `VoucherOrderController` 的两条限流注解、`RateLimitAspect`、`seckill.lua`、`VoucherOrderServiceImpl.seckillVoucher/createVoucherOrder`、`VoucherOrderListener`、`VoucherOrderPersistenceService` 与 `SeckillMqConstants`；对照 `scripts/load-test/seckill-capacity.jmx` 的响应分类和工程日志中的三档对账，说明为什么返回订单 ID 后仍需等待最终落库。

连接拒绝补充实验阅读 `scripts/load-test/ObserveJvm.java`、`observe-seckill-host.ps1` 和同目录 README 的阶梯复测步骤；用 JTL 的 `timeStamp` 与 `elapsed` 筛选同步采样窗口，解释 300 档已经拒绝、但两轮拒绝数不单调，以及为何同机实验不能给出精确根因。来源见工程日志「秒杀连接拒绝阶梯复测」。

## 当前不足

- Redis/MQ 不原子、无生产者确认或失败补偿，仍可能丢单；错误消息转发也没有发布确认。
- MySQL 提交与 ACK 不原子，可能发生重复投递；UNIQUE 与事务保证业务键不重复插单/扣库存，不能据此宣称端到端 exactly-once。
- 错误队列需要人工核对，没有自动重放、Redis 回补或定时对账。长时间锁冲突或数据库故障仍会耗尽本地重试。
- Lua 未补充券不存在和活动起止时间校验；历史 Stream 积压不自动迁移。
- 原有教学测试包含大量写入，不能直接视为普通全量回归；本次未重验上传、关注等所有功能。
- Phase 3 已完成 10/200/500 线程的短批次秒杀实验；补充两轮 200→300→400→500 阶梯后，两轮均在 300 档首次观察到本机 HTTP 连接拒绝。整机 CPU 与 Tomcat 接入线程均出现高峰，拒绝数波动且没有 accept queue 计数；持续容量、GLOBAL 独立拒绝率与具体建连根因仍未确定。GLOBAL/USER 参数仍是学习性初值。普通 IP 能力尚未选择实际业务接口接入；当前只使用直连地址，接入可信反向代理前不解析 forwarded header。
