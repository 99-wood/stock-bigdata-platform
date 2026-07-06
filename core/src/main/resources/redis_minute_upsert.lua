-- ============================================================
-- Redis Lua: 分钟级 OHLCV raw 值记录（不做 delta，flush 时算 LAG）
--
-- 每个 quote 调用一次，记录该股票在当前 5 分钟窗口的 OHLCV raw 值。
-- 用 trade_time 比较处理 Kafka 多分区可能的乱序。
--
-- KEYS[1] = stock:minute:{code}:{minuteTime}
-- KEYS[2] = stock:minute:codes:{minuteTime}
-- KEYS[3] = stock:minute:windows
-- ARGV[1] = price
-- ARGV[2] = volume (当日累计)
-- ARGV[3] = amount (当日累计)
-- ARGV[4] = trade_date
-- ARGV[5] = trade_time ("HH:mm:ss")
-- ARGV[6] = code
-- ARGV[7] = minute_window ("09:30:00", 5分钟取整)
-- ============================================================

local minute_key  = KEYS[1]
local codes_key   = KEYS[2]
local windows_key = KEYS[3]

local price       = tonumber(ARGV[1])
local cum_vol     = tonumber(ARGV[2])
local cum_amt     = tonumber(ARGV[3])
local trade_time  = ARGV[5]
local minute_window = ARGV[4] .. ' ' .. ARGV[7]  -- "2026-07-01 09:30:00"

if not price or not cum_vol or not cum_amt then
    return redis.error_reply("missing required fields: price/volume/amount")
end

-- 检查是否本窗口首条
local stored_time = redis.call('HGET', minute_key, 'stored_time')

if stored_time == false then
    -- 首条: 初始化全部字段
    redis.call('HMSET', minute_key,
        'open',        price,
        'high',        price,
        'low',         price,
        'close',       price,
        'last_vol',    cum_vol,
        'last_amt',    cum_amt,
        'stored_time', trade_time,
        'trade_date',  ARGV[4]
    )
else
    -- 非首条: 更新 high/low，按时间判断 open/close
    local high = math.max(tonumber(redis.call('HGET', minute_key, 'high')), price)
    local low  = math.min(tonumber(redis.call('HGET', minute_key, 'low')),  price)

    if trade_time > stored_time then
        -- 更晚的快照 → 更新 close + last
        redis.call('HMSET', minute_key,
            'close',       price,
            'last_vol',    cum_vol,
            'last_amt',    cum_amt,
            'stored_time', trade_time
        )
    else
        -- 更早的快照 → 纠正 open
        redis.call('HSET', minute_key, 'open', price)
    end

    redis.call('HMSET', minute_key, 'high', high, 'low', low)
end

-- 跟踪
redis.call('SADD', codes_key,   ARGV[6])       -- code
redis.call('SADD', windows_key, minute_window)    -- 5分钟窗口时间

return 1
