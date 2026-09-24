# 秒杀限流压测准备

本节保留 k6 限流实验的执行清单。Phase 3 已在本地专用券和账号上运行过限流与逐级秒杀实验，真实结果及边界见 [工程日志](../../docs/engineering-log.md)；正式阈值没有据此调整，也不宣称生产容量。下文 JMeter 计划用于每账号单请求的全链路对账。

## 安全前提

放行的 HTTP 200 请求会继续执行订单 ID、`seckill.lua`、RabbitMQ 和数据库下单链路。运行前必须确认：

1. 使用隔离测试环境，不能指向生产或共享开发环境。
2. 使用专用测试券、库存和账号，明确如何清理测试订单及 Redis 购买资格。
3. Redis 必须使用项目版本 7.2；MySQL、RabbitMQ、消费者和应用均健康。
4. 先记录空载基线，再逐级升压；出现大量 503、进程异常或依赖不健康时立即停止。
5. GLOBAL 场景准备足够多的独立 token。token 太少时 USER 规则会先产生大量 429，无法仅凭状态码判断 GLOBAL 桶的拐点。

## USER 场景

一个用户反复请求同一张券，验证同用户同券的防刷效果：

```powershell
k6 run `
  -e MODE=user `
  -e BASE_URL=http://127.0.0.1:8081 `
  -e VOUCHER_ID=7 `
  -e TOKEN=replace-with-test-token `
  -e VUS=10 `
  -e DURATION=10s `
  scripts/load-test/seckill-rate-limit.js
```

预期关注：少量请求进入业务，大部分返回 429；不能仅用最终订单数推断限流次数，因为原有一人一单也会拒绝请求。

## GLOBAL 场景

使用多个测试账号，以恒定到达率观察 Token Bucket：

```powershell
k6 run `
  -e MODE=global `
  -e BASE_URL=http://127.0.0.1:8081 `
  -e VOUCHER_ID=7 `
  -e TOKENS=token-a,token-b,token-c `
  -e RATE=1000 `
  -e DURATION=20s `
  scripts/load-test/seckill-rate-limit.js
```

不要直接从 1000 req/s 起步。应从明显低于预估容量的速率开始，按阶梯逐步增加，并为每档保留稳定观察时间。

## 必须同时观察的指标

- k6：`allowed_responses`、`limited_responses`、`unavailable_responses`、P95/P99 延迟、丢弃迭代数。
- 应用：线程池、CPU、GC、连接池、429/503 数量和 ERROR 日志。
- Redis：命令延迟、CPU、连接数、慢日志、内存和 key 数量。
- RabbitMQ：publish rate、ready/unacked、消费者速率和错误队列。
- MySQL：连接池、事务耗时、锁等待、条件扣库存和插单吞吐。

`refillRate` 应以系统可长期稳定处理的入口速率为依据；`capacity` 表示允许吸收的短时 burst。两者必须基于上述全链路指标调整，不能只根据 Redis QPS 或 k6 的单一吞吐拍值。

---

# 商铺缓存性能验证（JMeter）

`shop-cache.jmx` 只验证当前 `GET /shop/{id}` 的 Cache-Aside / Pass Through 行为，不临时关闭缓存，也不构造“无缓存版本”。冷启动表示删除一个真实商铺的缓存后立即发起并发请求；同一轮中缓存一旦写回，后续请求已经属于恢复阶段。

## 1. 参数与测试商铺

测试计划要求通过 `-J` 显式传入以下参数，不在 `.jmx` 中保存个人地址或数据：

| 参数 | 含义 |
| --- | --- |
| `BASE_URL` | 应用地址，例如本地环境的 `http://127.0.0.1:8081` |
| `shopId` | `tb_shop` 中真实存在且允许测试的商铺 ID |
| `threads` | 并发线程数 |
| `rampUp` | 全部线程启动所需秒数 |
| `duration` | 持续时间（秒） |

先在隔离或本地数据库选择商铺，不要盲用生产数据：

```sql
SELECT id, name, type_id FROM tb_shop ORDER BY id LIMIT 20;
```

应用链路使用 `cache:shop:{shopId}`，正常商铺缓存 TTL 为 30 分钟；不存在商铺的空值缓存 TTL 为 2 分钟。以下命令假设仓库 Compose Redis 容器名为 `hm-dianping-redis-1`，若名称不同请替换。

## 2. Redis 与 MySQL 观测

删除缓存并确认不存在：

```powershell
docker exec hm-dianping-redis-1 redis-cli DEL cache:shop:1
docker exec hm-dianping-redis-1 redis-cli EXISTS cache:shop:1
```

预热并确认缓存存在、查看剩余 TTL：

```powershell
Invoke-RestMethod http://127.0.0.1:8081/shop/1 | Out-Null
docker exec hm-dianping-redis-1 redis-cli EXISTS cache:shop:1
docker exec hm-dianping-redis-1 redis-cli TTL cache:shop:1
```

每轮测试前后保存 Redis 命令累计值，使用差值判断 GET/SET 压力：

```powershell
docker exec hm-dianping-redis-1 redis-cli INFO commandstats | Select-String 'cmdstat_get|cmdstat_set'
```

MySQL 8 可从 `performance_schema` 的 digest 累计值观察实际查询次数。每轮前后执行并取 `COUNT_STAR` 差值；不要在共享数据库执行清空统计的命令：

```sql
SELECT DIGEST_TEXT, COUNT_STAR, SUM_TIMER_WAIT, SUM_ROWS_EXAMINED, SUM_ROWS_SENT
FROM performance_schema.events_statements_summary_by_digest
WHERE SCHEMA_NAME = 'hmdp'
  AND DIGEST_TEXT LIKE 'SELECT%FROM `tb_shop`%WHERE `id` = ?%'
ORDER BY COUNT_STAR DESC;
```

如果数据库禁用 `performance_schema` 或 digest 未匹配，可在隔离环境临时开启 MySQL general log，或通过应用 SQL 日志逐条计数；必须注明采集方式及其额外开销。

## 3. 冷启动实验

使用确定存在的 `shopId` 删除缓存，确认 `EXISTS` 返回 `0`，记录 Redis/MySQL 起始累计值后立即运行：

```powershell
jmeter -n -t scripts/load-test/shop-cache.jmx `
  -JBASE_URL=http://127.0.0.1:8081 `
  -JshopId=1 -Jthreads=50 -JrampUp=1 -Jduration=15 `
  -l target/load-test/shop-cache-cold.jtl `
  -e -o target/load-test/shop-cache-cold-report
```

结束后再次保存累计值及 key TTL。重点结合 JTL 的时间序列观察首批请求，说明 MySQL 查询是否被并发放大，以及缓存建立后延迟如何恢复。整轮汇总不能被表述为“所有请求都走冷缓存”。

## 4. 热缓存稳态实验

先请求一次并确认 `EXISTS=1`，记录新的 Redis/MySQL 起始值，再以相同线程、ramp-up 和持续时间运行：

```powershell
jmeter -n -t scripts/load-test/shop-cache.jmx `
  -JBASE_URL=http://127.0.0.1:8081 `
  -JshopId=1 -Jthreads=50 -JrampUp=1 -Jduration=15 `
  -l target/load-test/shop-cache-hot.jtl `
  -e -o target/load-test/shop-cache-hot-report
```

正式测量使用非 GUI 模式。每次运行前删除旧 `.jtl` 和报告目录；JMeter 不会覆盖已有报告目录。HTML 报告的 Statistics 表记录 Throughput、Average、95th pct、99th pct 和 Error %，数据库及 Redis 使用同一轮前后累计值的差值。

## 5. 结果记录

| 场景 | Threads | TPS | Avg(ms) | P95(ms) | P99(ms) | Error Rate | MySQL 查询情况 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 冷启动/缓存失效瞬间 |  |  |  |  |  |  |  |
| 热缓存稳态 |  |  |  |  |  |  |  |

冷启动还要单列：首批请求的样本数量与延迟范围、MySQL 查询增量、Redis SET 增量，以及缓存建立后延迟是否恢复。JMeter 发出的请求数只描述负载和完成量，不能直接当作系统在更高压力下仍可维持的容量结论。

---

# 秒杀正确性与容量实验（k6 + JMeter）

本实验只使用隔离环境和专用券、账号。`seckill-rate-limit.js` 验证正式限流，`seckill-capacity.jmx` 每个 CSV token 只请求一次，验证通过限流后的 Lua → RabbitMQ → MySQL 链路。HTTP 返回订单 ID 仅表示 Lua 接受且发送调用已返回，最终订单必须等待消费完成后核对。

## 1. 初始化专用数据

先启动 MySQL、Redis 7.2、RabbitMQ 和应用，并使用独立 RabbitMQ vhost 或本地独占环境。专用券必须为秒杀券，`tb_voucher.type=1`、`tb_voucher.status=1`，`tb_seckill_voucher` 的活动时间覆盖整场实验。为 Stage A/B/C 分别准备至少 10/200/500 个**不同**账号和 token；每轮库存至少等于该轮账号数。账号 token 需要按项目的 `login:token:{token}` Hash 结构写入 Redis，字段包含 `id`、`nickName`、`icon`，并设置足够长的 TTL。不要提交真实 token CSV。

CSV 可从 `seckill-tokens.example.csv` 复制到被 Git 忽略的本地目录；首行固定为 `token,userId`。每个文件至少包含对应线程数的有效、唯一 token。JMeter 使用共享 CSV、`recycle=false`、单线程单请求，缺行则线程停止。`duration` 是线程组允许的最长时间，实际一次性请求往往提前结束，因此报告中的 TPS 是**这一批请求的完成速率**，不是持续容量。

每轮前只操作专用券及专用账号，并确认主/错误队列空。以下 SQL 中的 `@voucher_id` 与 `@stock` 必须替换为本轮专用值；只有确认不涉及共享数据后才能执行重置：

```sql
SET @voucher_id = 10;
SET @stock = 500;
SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = @voucher_id;
DELETE FROM tb_voucher_order WHERE voucher_id = @voucher_id;
UPDATE tb_seckill_voucher SET stock = @stock WHERE voucher_id = @voucher_id;
```

```powershell
docker exec hm-dianping-redis-1 redis-cli SET seckill:stock:10 500
docker exec hm-dianping-redis-1 redis-cli DEL seckill:order:10
docker exec hm-dianping-redis-1 redis-cli DEL rate_limit:token_bucket:voucher-order:seckill:global
```

USER 限流 key 为 `rate_limit:sliding_window_counter:voucher-order:seckill:user:{userId}:arg:{voucherId}`。专用账号的相关 key 要按 token CSV 中的 userId 逐个清理，或等待 2 秒 TTL 到期；不要对共享 Redis 执行全库清空。

## 2. 限流正确性（正式阈值）

当前 Controller 的 GLOBAL 为 `capacity=1500, refillRatePerSecond=1000`，USER 为 `limit=5, windowMillis=1000`。k6 使用已有脚本；小样本 USER 验证可以通过 `ITERATIONS` 限制总次数：

```powershell
k6 run -e MODE=user -e BASE_URL=http://127.0.0.1:8081 `
  -e VOUCHER_ID=10 -e TOKEN=replace-with-test-token `
  -e VUS=1 -e ITERATIONS=10 -e DURATION=10s `
  scripts/load-test/seckill-rate-limit.js
```

GLOBAL 验证需要足够多独立账号，且应记录 `dropped_iterations`：负载发生器丢弃的迭代没有发到应用。脚本目前按 VU 轮换 token，所以长时间高到达率会同时触发 USER 和 GLOBAL；仅凭 429 总数**不能**断定全是 GLOBAL 拒绝。通过 Redis 桶状态、规则日志或更严格的一请求一账号脚本补充归因后，才能声称单独测得 GLOBAL 拒绝率。k6 的 `http_req_failed` 默认会把 HTTP 429 计入失败；本实验以 `limited_responses` 记录正常限流，以 `unavailable_responses` 和 `unexpected_responses` 记录异常。`allowed_responses` 仅表示 HTTP 200；`accepted_responses` 和 `business_failed_responses` 再按响应体区分。

## 3. JMeter 逐级执行

以下命令在仓库根目录运行。PowerShell 中 `-JtokenCsv=...` 必须作为一个带引号的参数传给 `jmeter.bat`，否则文件名中的点可能被错误拆开。每轮前确认专用库存、账号数、主队列和错误队列；每轮后先等待消费完成并对账，再决定是否升压。报告目录必须不存在。

```powershell
jmeter -n -t scripts/load-test/seckill-capacity.jmx `
  -JBASE_URL=http://127.0.0.1:8081 -JvoucherId=10 `
  "-JtokenCsv=target/load-test/seckill-stage-a-tokens.csv" `
  -Jthreads=10 -JrampUp=1 -Jduration=60 `
  -l target/load-test/seckill-stage-a.jtl `
  -e -o target/load-test/seckill-stage-a-report
```

Stage B 将 CSV、`threads` 改为 200；Stage C 逐步增加到 500 或隔离环境允许的较低值。每账号只请求一次，避免一人一单和 USER 规则污染容量实验。单轮 500 次请求低于 GLOBAL 初始桶容量 1500，但仍需检查 429 实际是否为零。如果出现大量网络错误、503、主队列持续不清空或依赖异常，停止升压。正式限流阈值不必为此实验覆盖；若将来需要持续超过默认阈值的实验，应先在隔离环境实现配置覆盖、验证生效，并记录实际阈值。

## 4. 同步观测与最终对账

RabbitMQ Management API 或管理页记录主队列 `ready`、`unacked`、publish、ack 与错误队列数量。仓库脚本可轮询主队列，但其 `peak_*` 是采样峰值，可能漏掉毫秒级短暂积压：

```powershell
$env:RABBITMQ_USERNAME='test-user'
$env:RABBITMQ_PASSWORD='test-password'
python scripts/load-test/observe-rabbit.py --duration 30 `
  --output target/load-test/stage-a-rabbit.json
```

Redis 逐轮记录库存、购买资格和限流 key：

```powershell
docker exec hm-dianping-redis-1 redis-cli GET seckill:stock:10
docker exec hm-dianping-redis-1 redis-cli SCARD seckill:order:10
docker exec hm-dianping-redis-1 redis-cli HGETALL rate_limit:token_bucket:voucher-order:seckill:global
docker exec hm-dianping-redis-1 redis-cli INFO commandstats
```

MySQL 在消费完成后核对库存、订单数、重复订单和连接/慢查询；需要数据库用户有相应读取权限：

```sql
SET @voucher_id = 10;
SELECT stock FROM tb_seckill_voucher WHERE voucher_id = @voucher_id;
SELECT COUNT(*) AS final_orders, COUNT(DISTINCT user_id) AS distinct_users
FROM tb_voucher_order WHERE voucher_id = @voucher_id;
SELECT user_id, voucher_id, COUNT(*) AS duplicates
FROM tb_voucher_order WHERE voucher_id = @voucher_id
GROUP BY user_id, voucher_id HAVING COUNT(*) > 1;
SHOW GLOBAL STATUS WHERE Variable_name IN
  ('Threads_connected','Threads_running','Questions','Slow_queries');
```

每轮记录 `请求数 = 429 + 503 + 200业务失败 + Lua接受 + 网络/其他错误`。只有在主队列 `ready=0`、`unacked=0` 且错误队列为空后，才比较 Lua 接受数与 MySQL 最终订单数；Redis 和 MySQL 库存都应等于初始库存减去接受数。

| 阶段 | Threads | Requests | TPS | Avg | P95 | P99 | 429 | 503 | 业务失败 | Lua 接受 | 最终订单 | 网络/其他错误 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| A 正确性 | | | | | | | | | | | | |
| B 中并发 | | | | | | | | | | | | |
| C 较高并发 | | | | | | | | | | | | |

| 阶段 | 采样峰值 ready | 采样峰值 unacked | 最终主队列 | 错误队列 | publish/ack 增量 | 是否消费完成 |
| --- | ---: | ---: | ---: | ---: | --- | --- |
| A | | | | | | |
| B | | | | | | |
| C | | | | | | |

## 5. 本机连接拒绝阶梯复测（200 → 300 → 400 → 500）

本节只用于定位短批次并发建连的拒绝起点，不把一次性请求完成速率当成持续容量。沿用上面的专用券、独立账号 CSV、逐轮重置及最终订单对账。四档统一 `rampUp=1`、`duration=60`、同一应用与依赖实例；每档至少准备 500 个有效账号，且每个账号每档只请求一次。每档结束确认主/错误队列清空，再重置该专用券订单、MySQL/Redis 库存和 Redis 一人一单 Set；USER key 等待 TTL 到期。所有输出放在被 Git 忽略的 `target/load-test`，不得提交真实 token。

为了让 Tomcat 与 Hikari 暴露 JMX 指标，在隔离环境启动应用前设置 `SERVER_TOMCAT_MBEANREGISTRY_ENABLED=true` 和 `SPRING_DATASOURCE_HIKARI_REGISTER_MBEANS=true`。应用就绪后先用独立请求触发 Hikari 初始化。JDK 21 本地 Attach 采样器只读应用 MBean，Windows 脚本采集整机/JMeter CPU 与本地 8081 端口 TCP 状态。执行示例（把 PID、券 ID、CSV 换成本轮实际值）：

```powershell
javac --add-modules jdk.attach scripts/load-test/ObserveJvm.java
$appPid = 12345
$stage = 200
$voucherId = 'REPLACE_WITH_TEST_VOUCHER_ID'
Start-Process java.exe -ArgumentList @('--add-modules','jdk.attach','-cp','scripts/load-test','ObserveJvm',"$appPid",'100','18000') -WindowStyle Hidden -RedirectStandardOutput "target/load-test/stair-$stage-jvm.csv" -RedirectStandardError "target/load-test/stair-$stage-jvm.err"
Start-Process pwsh.exe -ArgumentList @('-NoProfile','-File','scripts/load-test/observe-seckill-host.ps1','-DurationSeconds','18','-IntervalMs','100') -WindowStyle Hidden -RedirectStandardOutput "target/load-test/stair-$stage-host.csv" -RedirectStandardError "target/load-test/stair-$stage-host.err"
jmeter -n -t scripts/load-test/seckill-capacity.jmx `
  -JBASE_URL=http://127.0.0.1:8081 "-JvoucherId=$voucherId" `
  '-JtokenCsv=target/load-test/stair-tokens.csv' `
  "-Jthreads=$stage" -JrampUp=1 -Jduration=60 `
  -l "target/load-test/stair-$stage.jtl"
```

只在采样器输出覆盖 JTL `timeStamp` 至 `timeStamp + elapsed` 的窗口时计算峰值。采样间隔受同机 CPU 争用影响，`tomcat_busy`/TCP 峰值可能漏掉更短的尖峰；`server_syn_received=0` 不能单独证明 accept queue 未满。`jmeter_cpu_pct` 是进程 CPU，以**单个逻辑核为 100%**，`machine_cpu_pct` 才是整机 CPU。`client_time_wait` 包含前一档未回收的连接，应记录档前基数，不能把它直接解释为端口耗尽。连接拒绝以 JTL 中 `HttpHostConnectException` 且消息含 `Connection refused` 精确计数，429/503 单独统计。需看 `.err` 确认 JMX 的 Tomcat/Hikari MBean 均存在；没有暴露时对应指标写“不可得”，不补猜测值。

| 档位 | 请求数 | 连接拒绝 | Tomcat busy/max 峰值 | JVM 线程峰值 | Hikari active 峰值 | 整机/JVM CPU 峰值 | JMeter CPU 峰值 | Heap 峰值 | GC 次数/耗时增量 | 客户端 ESTABLISHED/TIME_WAIT 峰值 |
| --- | ---: | ---: | --- | ---: | ---: | --- | ---: | ---: | --- | --- |
| 200 | | | | | | | | | | |
| 300 | | | | | | | | | | |
| 400 | | | | | | | | | | |
| 500 | | | | | | | | | | |

每档还要核对 HTTP 已接受数、MySQL 最终订单数、Redis/MySQL 剩余库存，以及 RabbitMQ 主/错误队列。若出现拒绝，需要用独立压测机和接入层队列指标复核；同机采样只能定位可疑边界，无法单独证明具体系统调用或组件的根因。

## 6. 清理

停止发压，确认主队列清空且错误队列没有待处理消息；保存 JTL、HTML 报告和对账结果。按专用券 ID 删除 `tb_voucher_order`、`tb_seckill_voucher`、`tb_voucher`，按专用账号 ID 删除测试用户；按 token CSV 删除各自的 `login:token:*`，再删除该券 `seckill:stock:*`、`seckill:order:*` 和本轮专用 USER 限流 key。GLOBAL key 与其他券共享，只能在独占 Redis 中清理。删除之前再次用券标题/用户标记核对目标，绝不导入会重建表的 `hmdp.sql`。
