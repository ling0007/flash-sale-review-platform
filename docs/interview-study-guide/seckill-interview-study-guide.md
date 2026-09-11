# 从 Redis Lua 到 RabbitMQ：秒杀异步下单面试教程

这篇文章带你按一次真实秒杀请求读懂本项目的改造。每一步都回答三个问题：代码做了什么？为什么这样做？失败或重复时会怎样？配套阅读：[消费可靠性设计](../任务总结/seckill-consumer-reliability.md)、[工程演进日志](../engineering-log.md)、[面试复盘](../interview-notes.md)。

## 先建立整体认识

请求先进入 Redis Lua。脚本原子完成库存、一人一单、Redis 预扣和抢购资格记录；成功后 Java 把同一个 orderId、userId、voucherId 封装成消息发到 RabbitMQ，接口立即返回。Listener 收到消息后调用订单 Service，Service 在 MySQL 一个事务内条件扣库存并创建订单。失败最多重试三次，仍失败就进入错误队列；重复消息由 MySQL 唯一索引和事务保证幂等。

~~~text
HTTP → Redis Lua → RabbitTemplate → RabbitMQ
     → Listener → 事务 Service → MySQL
~~~

接口返回 orderId 只表示 Lua 成功且发送调用正常返回，不表示 MySQL 已提交。本阶段没有 Publisher Confirm、Return、生产者阻塞重试或补偿。

## 按什么顺序读代码

### 1. 先读消息路由：SeckillMqConstants

阅读 [SeckillMqConstants.java](../../src/main/java/com/hmdp/utils/SeckillMqConstants.java)。

问题：正常消息和失败消息分别去哪？

答案：正常消息发到 hmdp.seckill.direct，用 seckill.order.create 路由到 hmdp.seckill.order.queue；三次处理失败后，Recoverer 用 seckill.order 把消息转到 hmdp.error.direct 和 hmdp.seckill.error.queue。常量集中定义，避免生产者、消费者和配置各写一套字符串。

### 2. 再读消息契约：VoucherOrderMessage

阅读 [VoucherOrderMessage.java](../../src/main/java/com/hmdp/dto/VoucherOrderMessage.java)。

问题：为什么不直接发送 VoucherOrder 实体？

答案：单独写消息 DTO，只传必要的三个 id：订单 id、用户 id、优惠券 id。
一是消息内容更小，传输更快；二是数据库怎么改，不会影响 MQ 消息格式；三是也不会把数据库内部字段泄露到消息中间件。


### 3. 读 Lua：它快，但不是最终事实

阅读 [seckill.lua](../../src/main/resources/seckill.lua)，找库存判断、用户 Set、DECRBY 和资格记录。

问题：为什么必须用 Lua？Lua 成功是不是订单已经落库？

答案：如果分开写多条 Redis 命令：查库存、判断用户是否抢购、扣库存。高并发的时候，多条命令中间会插进别的用户请求。就会出现超卖，或者同一个用户抢到多张券。
Lua 脚本是在 Redis 服务端一次性全部跑完，中间不会被别的请求打断，具备原子性，就能避免上面这些并发问题。
但 Lua 执行成功，只是代表 Redis 这边校验通过，不代表订单已经存到数据库，真正库存以 MySQL 为准。
问题：Lua 成功是不是订单已经落库？
。

### 4. 读入口 Service：找 orderId 和生产者

阅读 [VoucherOrderServiceImpl.java](../../src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java) 的“生成 ID → 执行 Lua → 发送消息 → 返回 ID”。

问题：为什么消息使用同一个 orderId？

答案：接口返回值、日志和消费者写库使用同一标识，方便查询和排查。幂等键不是 orderId，而是 (userId, voucherId)，因为重复投递可能带来不同 orderId。

### 5. 读 RabbitMQ 配置：持久性和边界

阅读 [SeckillRabbitConfig.java](../../src/main/java/com/hmdp/config/SeckillRabbitConfig.java) 与 [application.yaml](../../src/main/resources/application.yaml)。

问题：durable 和 persistent 有什么用？

答案：durable 让交换机、队列、绑定在 broker 重启后仍存在；persistent 让消息按持久消息发送。它们不能证明生产者已到达 broker，因此没有 Confirm 就不能承诺绝不丢消息。地址、账号、密码从 spring.rabbitmq.* 和环境变量读取，连接失败会暴露异常而不是静默成功。

### 6. 读 Listener：AUTO ACK 的时机

阅读 [VoucherOrderListener.java](../../src/main/java/com/hmdp/listener/VoucherOrderListener.java)。
MQ 消费优惠券下单消息，先用 Redisson 用户级分布式锁防并发；调用事务方法扣库存建订单；捕获数据库唯一冲突异常，**二次查询区分是【用户重复下单】还是【其他主键冲突】**，只对用户重复下单做幂等放行，其余异常抛出交给 MQ 重试。

问题：AUTO ACK 是收到消息立刻确认吗？

答案：不是。Listener 正常返回，Spring 才视为成功；Service 抛出的异常必须继续向外传播。若 Listener catch 后只打印日志并正常返回，框架会错误 ACK，消息就丢了。

Listener 只做日志和委托，数据库业务留在 Service，避免复制业务规则，也让事务边界清晰。

### 7. 读 Retry 和错误队列

在 [application.yaml](../../src/main/resources/application.yaml) 找 retry，在配置类找 RepublishMessageRecoverer。

问题：max-attempts: 3 是几次？

答案：总共三次：首次、等待约 1 秒后的第二次、等待约 2 秒后的第三次。当前事务是在每次业务调用内部开启的，所以一次失败先回滚，下一次 retry 再开启新事务，stateless retry 更符合这个边界。

问题：为什么不无限 requeue？

答案：毒消息或持续故障会反复占用业务队列。三次耗尽后转发到持久错误队列，并保留原 JSON、异常头、原交换机和路由键，供人工排查；它不是自动重放。

### 8. 读订单事务：重复消息为什么不重复扣库存

阅读 [VoucherOrderPersistenceService.java](../../src/main/java/com/hmdp/service/impl/VoucherOrderPersistenceService.java)。

关键顺序是：查重 → 条件扣库存 → 插入订单。

问题：为什么必须条件更新？

答案：不能“SELECT 库存 → Java 判断 → 普通 UPDATE”，因为读写之间可能被其他事务抢走最后一件。代码使用等价 SQL：

~~~sql
UPDATE tb_seckill_voucher
SET stock = stock - 1
WHERE voucher_id = ? AND stock > 0;
~~~

更新 0 行就抛库存不足异常。
豆包：先查询库存再扣减是两条 SQL，存在竞态条件会超卖；条件更新把判断和扣减合并成一条原子 SQL，借助 InnoDB 行锁，保证不会超卖。乐观锁。

问题：两个重复消息同时消费会怎样？

答案：两者都可能先查不到订单，但只有一个 INSERT 能通过 (user_id, voucher_id) UNIQUE。失败方的 DuplicateKeyException 必须离开 @Transactional 方法，库存更新和插入一起回滚；事务外 Service 再确认业务订单已存在，把它作为幂等成功。不能在事务内部 catch 后直接 return，否则可能提交第二次库存扣减。

### 9. 读数据库约束：Redis 不是最终防线

阅读 [hmdp.sql](../../src/main/resources/db/hmdp.sql) 和 [20260909_voucher_order_unique.sql](../../src/main/resources/db/migration/20260909_voucher_order_unique.sql)。

问题：Redis 已经一人一单，为什么还要 UNIQUE？

答案：Redis 是前置快速拦截，可能被重复 MQ、其他写入入口或异常流程绕过；MySQL (user_id, voucher_id) 唯一索引是最终事实约束，不依赖某个应用实例是否拿到锁。

### 10. 最后读测试：用证据证明设计

阅读 [SeckillConsumerReliabilityTest.java](../../src/test/java/com/hmdp/SeckillConsumerReliabilityTest.java)。

问题：怎么证明幂等而不是只相信代码？

答案：重复投递和并发投递都要同时断言“订单只有一条、库存只减一次”；还要测试临时异常重试、连续失败进入错误队列、消费者重启后 durable 队列保留消息。项目已用 RabbitMQ、Redis、MySQL 集成测试覆盖这些场景，验收结果为 23 个测试通过。

## 面试高频问题：成段回答

**为什么从 Redis Stream 换成 RabbitMQ？**

“Redis Stream 也能做消息队列，但是要自己写很多逻辑：维护消费组、手动处理 ACK、处理 pending 未确认消息。
换成 RabbitMQ 之后，重试机制、死信 / 错误队列、消息 ACK、消费者管理中间件都帮我们实现好了，我们业务代码只关心下单逻辑，开发维护会简单很多。Redis 还保留，继续做 Lua 脚本的资格校验。不是说 Redis Stream 性能不行，是为了简化消息可靠性这块的开发。
**如何保证消费端可靠？**

：我们是做到至少一次消费，做不到严格的恰好一次。
逻辑是：监听器业务异常直接往外抛，不吞异常，失败触发本地重试，最多重试 3 次。重试失败转发错误队列。数据库靠事务 + 唯一索引保证幂等，就算消息重复投递，业务结果不会出错。
但是我们生产者这边没有开启 Publisher‑Confirm 确认机制，存在一小段不确定性窗口，有可能消息发送的时候 broker 宕机，生产者不知道消息有没有到达 MQ，所以不能保证完全不丢消息。
**为什么重复消息不会重复扣库存？**

“Redis Set 是第一层快速拦截，MySQL (user_id, voucher_id) UNIQUE 是最终约束。并发消息即使同时查重未命中，唯一键失败的一方也必须让异常穿过事务代理，回滚前面的条件扣库存；事务外确认订单已存在后才把重复消息视为成功。”

**为什么使用 stateless retry？**

“当前没有 Listener 外层事务，@Transactional 在每次业务调用内部开启。失败的一次调用完整回滚，retry 再调用一次并开启新事务，因此 stateless 与现有事务边界一致。不能机械地认为‘有事务就用 stateful’。”
是否选用 stateful，判断依据不是有没有事务，而是重试是否由外部重新投递触发、需要跨线程 / 跨方法调用延续重试计数。
如果重试是在同一个线程内循环执行，每次重试都是独立事务，失败完整回滚，事务边界和 stateless 重试边界一致，就应该使用 stateless retry。
补充一个坑点
如果这个场景强行改成 stateful=true：
stateful 会用业务对象作为 key 缓存 RetryContext。但你的重试本就在单线程内循环，不需要缓存；还会引入额外缓存清理问题，甚至内存泄漏。

**为什么需要错误队列？**

答：短暂的临时异常可以靠重试解决。如果是毒消息，比如消息数据本身非法，怎么处理都失败，就不能无限重试。
把消息转到错误队列，消息体、异常信息都保留下来，方便我们排查问题。我们项目这里不会自动重新消费错误队列，需要人工看情况处理，防止盲目执行业务逻辑。

**Redis 扣成功了，但 MQ 发送失败怎么办？**

我当前项目主要落地了 Redis Lua 预扣、RabbitMQ 异步下单和消费端幂等，生产者这一侧目前没有完整实现 Confirm 和自动补偿，所以这确实是当前方案的一个一致性缺口。

如果按生产级方案完善，我会采用最终一致性：Lua 预扣时记录一条带 orderId 的 pending 状态，MQ 开启异步 Publisher Confirm 和 Return。对于能够明确判断发送失败的情况，通过幂等 Lua 恢复库存和用户购买资格；但对于发送结果未知或者 Lua 成功后应用直接宕机这种窗口，不能贸然补偿，而是由定时对账任务检查数据库订单和 pending 状态，再决定重发还是补偿。

所以这里不需要为了强一致直接上 Seata，而是接受短暂不一致，通过补偿和对账最终收敛。

**Redis 和 MySQL 库存不一致怎么办？**

答：
Redis 是秒杀入口的预扣库存，MySQL 是最终业务库存，所以消费积压期间两者短暂不一致本身是允许的。真正需要处理的是消息丢失或者消费永久失败造成的长期不一致。生产方案里普通明确失败立即补偿，极端窗口由 pending 状态和定时对账处理；对账也不能简单比较两个库存数字，因为队列里可能还有尚未消费的订单，要结合订单状态判断。

**那为什么你自己没做？**

这个问题反而很好回答：
这个项目我目前优先保证了高并发主链路和消费端幂等。生产者端如果完整实现最终一致性，不能只加一个 Confirm 回调，还需要 pending 状态、幂等补偿以及对账，否则反而可能出现误补偿和库存多加。所以目前把它作为明确的架构改进点，没有为了简历展示去做一个不完整的伪可靠方案。
## 初学者练习与自检

1. 画出 Listener → VoucherOrderServiceImpl → VoucherOrderPersistenceService，标出事务代理在哪一层。
2. 将不存在优惠券的消息发入业务队列，观察三次尝试后的错误队列和 x-exception-* 头。
3. 解释为什么 Redis 锁失效时，数据库 UNIQUE 仍能保证一单一扣。

考前确认自己能说清：orderId 返回、broker 接收、MySQL 提交的区别；AUTO ACK、retry、requeue、错误队列的区别；唯一键冲突为何必须先回滚；未开启 Confirm 后还剩哪些风险。


AUTO ACK 是 Broker 自动删消息，宕机丢消息；requeue 是放回原队列立刻重投，无计数易死循环；DLX retry 走死信拓扑延迟可控重试；DLQ 存放重试耗尽的消息，人工处理。
唯一键冲突抛出异常时事务已经失效，存在部分执行 SQL，必须回滚释放资源；且该异常为重试无效的业务异常，不适合 requeue。
未开启 Publisher Confirm：网络中断消息状态未知、Broker 宕机内存消息丢失、消息路由失败生产者无感知，无法确认消息是否成功抵达 Broker。
## 延伸阅读

- [RabbitMQ 拓扑与错误转发](../../src/main/java/com/hmdp/config/SeckillRabbitConfig.java)
- [订单事务](../../src/main/java/com/hmdp/service/impl/VoucherOrderPersistenceService.java)
- [事务外幂等判断](../../src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java)
- [可靠性验收记录](../任务总结/seckill-consumer-reliability.md)
- [Spring AMQP 2.2 重试说明](https://docs.spring.io/spring-amqp/docs/2.2.6.RELEASE/reference/html/)

测试通过代表功能和失败路径已验证，不代表做过吞吐、延迟或 broker 断电恢复压测。
