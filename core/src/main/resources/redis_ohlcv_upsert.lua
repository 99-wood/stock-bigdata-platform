-- ============================================================
-- Redis Lua: OHLCV 行情 upsert + 市场概览增量更新
--
-- 原子操作: GET 旧值 → 比较 sign/volume/amount → HINCRBY(FLOAT) → SET 新值
-- 调用方通过 EVALSHA/EVAL 执行，一次网络往返完成全部操作。
--
-- KEYS[1] = stock:quote:ohlcv:{code}
-- KEYS[2] = stock:market:summary
-- KEYS[3] = stock:quote:ohlcv:codes (code 集合, 替代 KEYS)
-- ARGV[1] = 新行情 JSON 字符串
-- ARGV[2] = 统计时间 "yyyy-MM-dd HH:mm:ss"
-- ARGV[3] = code
--
-- 日清逻辑不在此脚本 —— 由 Java 侧在 batch 开始前处理
-- ============================================================

local ohlcv_key   = KEYS[1]
local summary_key = KEYS[2]
local new_json    = ARGV[1]
local stat_time   = ARGV[2]

-- ① 解析新值
local new = cjson.decode(new_json)

if not new.change_pct or not new.volume or not new.amount then
    return redis.error_reply("missing required fields in JSON: change_pct/volume/amount")
end

-- sign: 1=涨, -1=跌, 0=平
local new_sign = 0
if new.change_pct > 0 then
    new_sign = 1
elseif new.change_pct < 0 then
    new_sign = -1
end

-- ② 读旧值
local old_json = redis.call('GET', ohlcv_key)

if old_json == false then
    ------------------------------------------------------------------
    -- 今天第一次出现（新股 or 日清后的首批）
    ------------------------------------------------------------------
    redis.call('HINCRBY', summary_key, 'total_stocks', 1)

    if new_sign > 0 then
        redis.call('HINCRBY', summary_key, 'up_count', 1)
    elseif new_sign < 0 then
        redis.call('HINCRBY', summary_key, 'down_count', 1)
    else
        redis.call('HINCRBY', summary_key, 'flat_count', 1)
    end

    redis.call('HINCRBYFLOAT', summary_key, '_sum_pct',     new.change_pct)
    redis.call('HINCRBYFLOAT', summary_key, 'total_volume', new.volume)
    redis.call('HINCRBYFLOAT', summary_key, 'total_amount', new.amount)

else
    ------------------------------------------------------------------
    -- 已有旧值: 比较后增量更新
    ------------------------------------------------------------------
    local old = cjson.decode(old_json)

    local old_sign = 0
    if old.change_pct > 0 then
        old_sign = 1
    elseif old.change_pct < 0 then
        old_sign = -1
    end

    -- sign 变化时调整涨跌平计数
    if old_sign ~= new_sign then
        -- 减旧
        local old_field = 'flat_count'
        if old_sign > 0 then old_field = 'up_count'
        elseif old_sign < 0 then old_field = 'down_count' end
        redis.call('HINCRBY', summary_key, old_field, -1)

        -- 加新
        local new_field = 'flat_count'
        if new_sign > 0 then new_field = 'up_count'
        elseif new_sign < 0 then new_field = 'down_count' end
        redis.call('HINCRBY', summary_key, new_field, 1)
    end

    -- volume / amount / changePct 增量（当日累计值）
    redis.call('HINCRBYFLOAT', summary_key, 'total_volume', new.volume - old.volume)
    redis.call('HINCRBYFLOAT', summary_key, 'total_amount', new.amount - old.amount)
    redis.call('HINCRBYFLOAT', summary_key, '_sum_pct',     new.change_pct - old.change_pct)
end

-- ③ 更新 OHLCV 快照
redis.call('SET', ohlcv_key, new_json)

-- ④ 更新统计时间
redis.call('HSET', summary_key, 'stat_time', stat_time)

-- ⑤ 重新计算平均涨跌幅
local total = redis.call('HGET', summary_key, 'total_stocks')
local sum   = redis.call('HGET', summary_key, '_sum_pct')
if total and sum and tonumber(total) > 0 then
    redis.call('HSET', summary_key, 'avg_change_pct', tonumber(sum) / tonumber(total))
end

-- ⑥ 维护 code 集合 (替代 KEYS, 供 RankWriter 和 flushAllDaily 使用)
redis.call('SADD', KEYS[3], ARGV[3])

return 1
