-- Token Bucket（令牌桶）
--
-- KEYS[1]：完整的桶 key。秒杀 GLOBAL 必须让所有 voucherId 共用同一个 key。
-- ARGV[1]：capacity，桶最多保存的完整令牌数，控制瞬时 burst。
-- ARGV[2]：refillRatePerSecond，每秒补充的完整令牌数，控制长期平均速率。
--
-- Redis Hash 中实际保存：
-- tokens_milli：剩余 milli-token，1000 milli-token = 1 个完整令牌
-- last_refill_ms：上次计算补充后的 Redis 毫秒时间戳
--
-- 返回值：1 = 已扣除一个完整令牌并放行；0 = 令牌不足且未扣减。

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refillRatePerSecond = tonumber(ARGV[2])
local tokenScale = 1000

if not capacity or capacity <= 0 then
    return redis.error_reply('capacity must be a positive number')
end
if not refillRatePerSecond or refillRatePerSecond <= 0 then
    return redis.error_reply('refillRatePerSecond must be a positive number')
end

local capacityMilli = capacity * tokenScale

-- Redis TIME 返回“秒、微秒”。统一转换成毫秒，确保多应用实例共享同一时间来源。
local redisTime = redis.call('TIME')
local nowMillis = tonumber(redisTime[1]) * 1000 + math.floor(tonumber(redisTime[2]) / 1000)

local storedTokensRaw = redis.call('HGET', key, 'tokens_milli') --剩余 milli-token
local lastRefillRaw = redis.call('HGET', key, 'last_refill_ms') --上次计算补充后的 Redis 毫秒时间戳
local tokensMilli --现在的 milli-token 余额
local lastRefillMillis --上次计算补充后的 Redis 毫秒时间戳

if not storedTokensRaw and not lastRefillRaw then
    -- 新桶从满桶开始，所以第一次突发最多可以连续放行 capacity 个请求。
    tokensMilli = capacityMilli
    lastRefillMillis = nowMillis
elseif not storedTokensRaw or not lastRefillRaw then
    -- 部分字段丢失时无法可靠还原桶状态，返回错误而不是猜测并放行。
    return redis.error_reply('token bucket state is incomplete')
else
    tokensMilli = tonumber(storedTokensRaw)
    lastRefillMillis = tonumber(lastRefillRaw)
    if not tokensMilli or not lastRefillMillis then
        return redis.error_reply('token bucket state is not numeric')
    end
    if tokensMilli < 0 then
        return redis.error_reply('token bucket tokens must not be negative')
    end
    if nowMillis < lastRefillMillis then
        return redis.error_reply('redis time moved backwards for token bucket')
    end

    -- 配置中的 capacity 可能在发布后调小，先封顶可防止沿用超过新容量的旧余额。
    tokensMilli = math.min(tokensMilli, capacityMilli)
end

local elapsedMillis = nowMillis - lastRefillMillis

-- 1 token = 1000 milli-token；refillRate 的单位是 token/秒。
-- 因此经过 elapsedMillis 后应补充：
-- (elapsedMillis / 1000) * refillRatePerSecond * 1000
-- = elapsedMillis * refillRatePerSecond 个 milli-token。
--
-- 当 elapsed 已足以从空桶补满时直接设为 capacity，既表达“长时间空闲后满桶”，
-- 也避免没有必要地计算一个很大的 elapsed * refillRate 乘积。
local fullRefillMillis = math.ceil(capacityMilli / refillRatePerSecond)
if elapsedMillis >= fullRefillMillis then
    tokensMilli = capacityMilli
else
    tokensMilli = math.min(capacityMilli,
            tokensMilli + elapsedMillis * refillRatePerSecond)
end

local allowed = 0
if tokensMilli >= tokenScale then
    tokensMilli = tokensMilli - tokenScale
    allowed = 1
end

-- 即使本次拒绝，也要保存不足一个完整令牌的余额和当前时间。
--数学上纯拒绝场景结果近似等价，但工程上**必须每次推进状态**，否则时间戳陈旧 + key 可能丢失，最终会破坏限流准确性。
-- 使用 milli-token 后，频繁请求不会反复丢掉这部分小数补充值。
redis.call('HSET', key,
        'tokens_milli', tokensMilli,
        'last_refill_ms', nowMillis)

-- 从空桶恢复到满桶最多需要 fullRefillMillis。此后删除 key 与保留一个满桶状态语义相同：
-- 下一次请求会按满桶初始化。至少保留 1 秒，避免高 refillRate 下 key 过于频繁地创建和删除。
local ttlMillis = math.max(1000, fullRefillMillis)
redis.call('PEXPIRE', key, ttlMillis)

return allowed
