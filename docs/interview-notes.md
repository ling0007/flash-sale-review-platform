# 项目面试复盘

来源：[工程演进日志](engineering-log.md)、[限流方案权衡](黑马点评限流改造_工程权衡与面试问答.md)、[2026-09-08 秒杀 RabbitMQ 迁移审计](任务总结/seckill-rabbitmq-migration.md)、[2026-09-09 消费端可靠性与幂等](任务总结/seckill-consumer-reliability.md)。以当前代码为准，旧方案保留为演进历史。

初学者按 [秒杀 RabbitMQ 面试导读](interview-study-guide/seckill-interview-study-guide.md) 阅读源码、理解知识点、练习成段回答；本文保留考前速记。

## 当前项目亮点

Redis Lua 原子抢购资格与预扣库存，RabbitMQ JSON 消息异步下单，Listener 委托业务 Service，Redisson 用户锁覆盖完整的数据库事务提交。移除手写 Stream 消费和 pending 恢复；修复真实运行路径缺少事务的问题。

限流主线 Phase 2~7 已实现 Redis Lua Sliding Window Counter、Token Bucket、重复注解 + Spring AOP、秒杀两级限流、验证码 60 秒 cooldown，以及 HTTP 429/503。秒杀入口固定按 GLOBAL Token Bucket → USER Sliding Window Counter 检查；任一拒绝或 Redis 异常都不会进入订单 ID、原秒杀 Lua 和 RabbitMQ 链路。

消费端增加三次 stateless 本地尝试和错误消息转发；数据库 UNIQUE(user_id, voucher_id) 作为一人一单最终约束。唯一键冲突必须先完成事务回滚，再在外层核实业务键，避免库存已扣但重复订单插入失败仍提交。

本阶段 23 个筛选测试与 Maven package 通过；包含上一阶段主链路回归、真实并发 UNIQUE 冲突、有限重试、错误消息上下文和消费者连接重建。临时 INSERT 故障两次后第三次成功，三个独立事务且库存仅减一次。没有性能压测，不声称吞吐提升或端到端可靠交付已完成。

## 技术选型与取舍

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

## 重点代码与动手练习

阅读 VoucherOrderController 上的两条 `@RateLimit`、RateLimitAspect、RateLimitKeyResolver、RateLimitRuleExecutor，以及两种限流器的 `tryAcquire` 和 Lua；再阅读 UserServiceImpl.sendCode 的严格执行顺序、RedisConstants 中两个验证码 key，以及 WebExceptionAdvice 的 429/503 分流。最后沿 VoucherOrderServiceImpl、Listener 和 PersistenceService 复习限流之后的职责边界。

在专用 vhost 运行 SeckillConsumerReliabilityTest，重点阅读 temporaryInsertFailuresRetryInFreshTransactions 和 databaseUniqueRaceRollsBackLoserEvenWithoutRedisLock：解释每次重试的事务边界，以及为什么唯一键失败没有留下额外扣库存。练习将临时故障次数从 2 改为 1，并相应断言两次尝试；仍不要开启生产者重试。

## 当前不足

- Redis/MQ 不原子、无生产者确认或失败补偿，仍可能丢单；错误消息转发也没有发布确认。
- MySQL 提交与 ACK 不原子，可能发生重复投递；UNIQUE 与事务保证业务键不重复插单/扣库存，不能据此宣称端到端 exactly-once。
- 错误队列需要人工核对，没有自动重放、Redis 回补或定时对账。长时间锁冲突或数据库故障仍会耗尽本地重试。
- Lua 未补充券不存在和活动起止时间校验；历史 Stream 积压不自动迁移。
- 原有教学测试包含大量写入，不能直接视为普通全量回归；本次未重验上传、关注等所有功能。
- 限流代码主线已完成到 Phase 7，但尚未进行 Phase 8 的完整端到端 HTTP 集成测试与容量压测，GLOBAL/USER 参数仍是学习性初值。普通 IP 能力尚未选择实际业务接口接入；当前只使用直连地址，接入可信反向代理前不解析 forwarded header。
