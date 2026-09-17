-- Sliding Window Counter（滑动窗口计数器）
--
-- KEYS[1]：一个完整的限流 key。每个用户/券或 IP/资源组合都应该使用不同 key。
-- ARGV[1]：limit，一个窗口允许的最大加权请求数。
-- ARGV[2]：windowMillis，窗口长度，单位统一为毫秒。
--
-- Redis Hash 中实际保存：
-- current_window_start_ms：当前固定窗口的起始毫秒时间戳
-- previous_count：上一个固定窗口中已经放行的请求数
-- current_count：当前固定窗口中已经放行的请求数
--
-- 返回值：1 = 本次请求已计数并放行；0 = 本次请求被限流且不计数。

local key = KEYS[1]
local limit = tonumber(ARGV[1])
local windowMillis = tonumber(ARGV[2])

if not limit or limit <= 0 then
    return redis.error_reply('limit must be a positive number')
end
if not windowMillis or windowMillis <= 0 then
    return redis.error_reply('windowMillis must be a positive number')
end

-- 使用 Redis 服务器时间，而不是应用服务器时间。这样多实例部署时不会因机器时钟偏差
-- 把同一个请求桶计算到不同窗口。TIME 返回“秒、微秒”，这里统一换算成毫秒。
local redisTime = redis.call('TIME')
local nowMillis = tonumber(redisTime[1]) * 1000 + math.floor(tonumber(redisTime[2]) / 1000)

-- 将时间按 windowMillis 对齐到固定窗口左边界。
-- 例如 window=1000ms、now=1250ms，则当前窗口起点为 1000ms。
local currentWindowStart = nowMillis - (nowMillis % windowMillis)

local storedWindowStartRaw = redis.call('HGET', key, 'current_window_start_ms')
local previousCountRaw = redis.call('HGET', key, 'previous_count')
local currentCountRaw = redis.call('HGET', key, 'current_count')

local storedWindowStart
local previousCount
local currentCount

if not storedWindowStartRaw then
    -- key 第一次出现时，从一个空的当前窗口开始。
    storedWindowStart = currentWindowStart
    previousCount = 0
    currentCount = 0
else
    -- 已存在的 Hash 必须三个字段齐全。部分字段丢失时不能猜测状态，否则可能错误放行。
    if not previousCountRaw or not currentCountRaw then
        return redis.error_reply('sliding window counter state is incomplete')
    end

    storedWindowStart = tonumber(storedWindowStartRaw)
    previousCount = tonumber(previousCountRaw)
    currentCount = tonumber(currentCountRaw)
    if not storedWindowStart or not previousCount or not currentCount then
        return redis.error_reply('sliding window counter state is not numeric')
    end
    if previousCount < 0 or currentCount < 0 then
        return redis.error_reply('sliding window counter state must not be negative')
    end

    if currentWindowStart < storedWindowStart then
        -- Redis 宿主机时间明显回拨时，继续计算会产生大于 1 的权重。
        -- 返回脚本错误，让 Java 按基础设施异常 fail-closed，而不是静默破坏限流状态。
        return redis.error_reply('redis time moved to an earlier rate limit window')
    end

    if currentWindowStart > storedWindowStart then
        local passedWindows = math.floor((currentWindowStart - storedWindowStart) / windowMillis)
        if passedWindows == 1 then
            -- 只跨过一个窗口：原来的当前窗口成为新的上一窗口。
            previousCount = currentCount
        else
            -- 至少跨过两个窗口：旧计数已经完全落在最近一个窗口范围之外。
            previousCount = 0
        end
        currentCount = 0
        storedWindowStart = currentWindowStart
    end
end

local elapsedInCurrentWindow = nowMillis - storedWindowStart

-- 上一窗口与“最近一个窗口”的重叠比例会从 1 线性衰减到 0：
-- previousWeight = (windowMillis - elapsedInCurrentWindow) / windowMillis
-- estimated = previousCount * previousWeight + currentCount
local previousWeight = (windowMillis - elapsedInCurrentWindow) / windowMillis
local estimated = previousCount * previousWeight + currentCount

local allowed = 0
-- 判断的是“加入本次请求后的估算值”。如果只判断 estimated >= limit，
-- estimated=4.9、limit=5 时仍会再放行一次，使放行后的估算值变成 5.9。
if estimated + 1 <= limit then
    currentCount = currentCount + 1
    allowed = 1
end

redis.call('HSET', key,
        'current_window_start_ms', storedWindowStart,
        'previous_count', previousCount,
        'current_count', currentCount)

-- 上一窗口最多只会影响紧邻的下一个窗口。两倍窗口 TTL 既保留计算所需状态，
-- 又会在该限流对象停止访问后自动回收 key。被拒绝的活跃请求也会刷新 TTL。
redis.call('PEXPIRE', key, windowMillis * 2)

return allowed
