# Redis Schema — 股票大数据平台

> **版本**: v2.2 | **更新**: 2026-07-07

---

## 1. 概述

Redis（`mid:6379`）作为实时数据缓存层，承载市场概览、个股行情、排行和告警四类数据。

| 约定 | 说明 |
|------|------|
| Key 前缀 | `stock:` |
| name 存储 | `stock:quote:ohlcv:{code}` 中存 name，`stock:quote:{code}` 中不存 |
| 数字格式 | Hash 以字符串存储；JSON 中 price/change 保留 2 位小数，volume 为整数 |

---

## 2. Key 一览

| # | Redis Key | 类型 | 写入方 | 状态 |
|---|-----------|------|--------|------|
| 3.1 | `stock:market:summary` | Hash | Spark Streaming → `MarketDataWriter` | ✅ 已实现 |
| 3.2 | `stock:quote:ohlcv:{code}` | String (JSON) | Spark Streaming → `MarketDataWriter` | ✅ 已实现 |
| 3.2.1 | `stock:quote:ohlcv:codes` | Set | Lua `redis_ohlcv_upsert.lua` → `SADD` | ✅ 已实现 |
| 3.3 | `stock:quote:{code}` | String (JSON) | data_gen | ✅ 已由 data_gen 写入 |
| 3.4 | `stock:minute:{code}:{minuteTime}` | Hash | Spark Streaming → `MarketDataWriter` | ✅ 已实现 |
| 3.5 | `stock:minute:codes:{minuteTime}` | Set | Spark Streaming → `MarketDataWriter` | ✅ 已实现 |
| 3.6 | `stock:minute:windows` | Set | Spark Streaming → `MarketDataWriter` | ✅ 已实现 |
| 3.7 | `stock:rank:up` / `stock:rank:amount` | ZSET | Spark Streaming → `RankWriter` | ✅ 已实现 |
| 3.7.1 | `stock:rank:quant` | ZSET | Spark 离线任务 | ❌ 待开发 |
| 3.8 | `stock:alert:latest` | List (JSON) | Spark Streaming | ❌ 待开发 |

---

## 3. Key 详细定义

### 3.1 市场概览 — `stock:market:summary` ✅

**基本属性**

| 属性 | 值 |
|------|-----|
| 类型 | Hash |
| 写入方 | `RedisWriter.updateMarketData()` |
| 读取方 | `MarketSummaryDTO` |
| TTL | 无（持续覆盖，每日归零） |
| MySQL 对应 | `ads_market_summary` |

**Hash Fields**

| Field | 类型 | 示例 | 说明 |
|-------|------|------|------|
| `stat_date` | string | `2026-07-03` | 统计日期，日清判断依据 |
| `stat_time` | string | `2026-07-03 14:30:00` | 最后更新批次时间 |
| `total_stocks` | int | `5205` | 当日出现过的不重复股票数 |
| `up_count` | int | `3215` | 上涨家数（change_pct > 0） |
| `down_count` | int | `1844` | 下跌家数（change_pct < 0） |
| `flat_count` | int | `146` | 平盘家数（change_pct = 0） |
| `avg_change_pct` | double | `0.88` | 平均涨跌幅 % = `_sum_pct / total_stocks` |
| `total_volume` | long | `89283109059` | 全市场当日累计成交量 |
| `total_amount` | double | `2052802240478.05` | 全市场当日累计成交额（元） |
| `_sum_pct` | double | — | ⚠️ 内部字段：涨跌幅累加值，前端不读 |

**更新机制**

Lua 脚本 `redis_ohlcv_upsert.lua` 原子增量更新：
1. 每只股票 `EVALSHA` 执行：GET 旧值 → 比较 sign/volume/amount → `HINCRBY`/`HINCRBYFLOAT` 增量 → `HSET stat_time`
2. 日清：每 batch 前 Driver 检查 `stat_date`，变化时 `DEL` 整个 Hash 重新累加
3. 跨天 batch（`distinct trade_date > 1`）：DEL + 跳过本 batch

```
HSET stock:market:summary stat_date 2026-07-03 stat_time "2026-07-03 14:30:00" \
  total_stocks 5205 up_count 3215 down_count 1844 flat_count 146 \
  avg_change_pct 0.88 total_volume 89283109059 total_amount 2052802240478.05
```

---

### 3.2 个股 OHLCV 行情 — `stock:quote:ohlcv:{code}` ✅

**基本属性**

| 属性 | 值 |
|------|-----|
| 类型 | String (JSON) |
| 写入方 | `RedisWriter.updateMarketData()` |
| 读取方 | platform-web API（待对接） |
| TTL | 无（每批次 SET 覆盖） |
| MySQL 对应 | `dws_stock_day` / `dws_stock_minute` |

**JSON Schema**

```json
{
  "name":       "浦发银行",  // 股票名称
  "price":      8.67,        // 最新成交价
  "open":       8.69,        // 今开盘
  "high":       8.82,        // 今日最高
  "low":        8.66,        // 今日最低
  "volume":     39890562,    // 当日累计成交量（整数）
  "amount":     348271497.00,// 当日累计成交额（元，2位小数）
  "change":     -0.03,       // 涨跌额 = price - prev_close
  "change_pct": -0.34,       // 涨跌幅 %
  "trade_date": "2026-07-03",// 行情日期 yyyy-MM-dd
  "trade_time": "11:29:17"   // 行情时间 HH:mm:ss
}
```

**字段说明**

| 字段 | 类型 | 来源 |
|------|------|------|
| `name` | string | `StockQuote.name` |
| `price` | number (2 位小数) | `StockQuote.price` |
| `open` | number (2 位小数) | `StockQuote.open` |
| `high` | number (2 位小数) | `StockQuote.high` |
| `low` | number (2 位小数) | `StockQuote.low` |
| `volume` | long (整数) | `StockQuote.volume` |
| `amount` | number (2 位小数) | `StockQuote.amount` |
| `change` | number (2 位小数) | 消费端计算 |
| `change_pct` | number (2 位小数) | 消费端计算 |
| `trade_date` | string | `StockQuote.tradeDate` |
| `trade_time` | string | `StockQuote.tradeTime` |

> ⚠️ **code 不在 JSON 内**，从 key 中提取。`stock:quote:ohlcv:sh600519` → `code = "sh600519"`
>
> ⚠️ **与 3.3 的区别**：本 key 存 OHLCV，`stock:quote:{code}` 存 Level-2 盘口，来源不同、互不覆盖。

**写入示例**
```
SET stock:quote:ohlcv:sh600000 '{"price":8.67,"open":8.69,"high":8.82,"low":8.66,"volume":39890562,"amount":348271497.00,"change":-0.03,"change_pct":-0.34,"trade_date":"2026-07-03","trade_time":"11:29:17"}'
```

### 3.2.1 OHLCV 代码集合 — `stock:quote:ohlcv:codes` ✅

| 属性 | 值 |
|------|-----|
| 类型 | Set（member=code） |
| 写入方 | `redis_ohlcv_upsert.lua` → `SADD` |
| 用途 | flush/清理/RankWriter 遍历所有 OHLCV code，替代 `KEYS *` 阻塞 Redis |

```
SMEMBERS stock:quote:ohlcv:codes
→ sh600000, sh600001, sh600004, ...
```

---

### 3.3 个股盘口行情 — `stock:quote:{code}`（Level-2 五档）

**基本属性**

| 属性 | 值 |
|------|-----|
| 类型 | String (JSON) |
| 写入方 | data_gen（直接写 Redis） |
| 读取方 | `StockLatestDTO` |
| TTL | 无（持续覆盖） |

```json
{
  "trade_date": "2026-07-02",
  "trade_time": "14:30:00",
  "bid": 1850.50, "ask": 1851.00,
  "b1_v": "100", "b1_p": "1850.50",
  "b2_v": "200", "b2_p": "1850.00",
  "b3_v": "300", "b3_p": "1849.50",
  "b4_v": "150", "b4_p": "1849.00",
  "b5_v": "500", "b5_p": "1848.00",
  "s1_v": "80",  "s1_p": "1851.00",
  "s2_v": "120", "s2_p": "1852.00",
  "s3_v": "200", "s3_p": "1853.00",
  "s4_v": "100", "s4_p": "1855.00",
  "s5_v": "300", "s5_p": "1856.00",
  "status": "00"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `trade_date` | string | ✅ | 交易日期 `yyyy-MM-dd` |
| `trade_time` | string | ✅ | 交易时间 `HH:mm:ss` |
| `bid` | number | ✅ | 买一价 |
| `ask` | number | ✅ | 卖一价 |
| `b1_v` ~ `b5_v` | string | | 买一~五档量（手） |
| `b1_p` ~ `b5_p` | string | | 买一~五档价 |
| `s1_v` ~ `s5_v` | string | | 卖一~五档量（手） |
| `s1_p` ~ `s5_p` | string | | 卖一~五档价 |
| `status` | string | | 状态码，`00`=正常 |

> 📐 后续可追加 OHLCV 字段（price/open/high/low/volume/amount/change_pct），Jackson 默认忽略未知字段，向后兼容。

---

---
### 3.4 分钟 OHLCV — `stock:minute:{code}:{minuteTime}` ✅

**基本属性**

| 属性 | 值 |
|------|-----|
| 类型 | Hash |
| 写入方 | `MarketDataWriter` → `redis_minute_upsert.lua` |
| 读取方 | flush 时 → MySQL `dws_stock_minute` |
| TTL | 无（日清时删除） |

**Hash Fields**

| Field | 类型 | 说明 |
|-------|------|------|
| `open` | double | 窗口内最早 `tradeTime` 的 price |
| `high` | double | 窗口内最高 price |
| `low` | double | 窗口内最低 price |
| `close` | double | 窗口内最晚 `tradeTime` 的 price |
| `last_vol` | long | 窗口内最晚快照的当日累计成交量 |
| `last_amt` | double | 窗口内最晚快照的当日累计成交额（元） |
| `stored_time` | string | 窗口内最晚 `tradeTime`（HH:mm:ss），用于乱序判断 |
| `trade_date` | string | 交易日期 yyyy-MM-dd |

**设计要点**

- 只存 raw 值，不做 delta。flush 时用 LAG 做差：`vol(N) = last_vol(N) - last_vol(N-1)`
- Kafka 乱序处理：用 `stored_time` 比较，更晚更新 close/last，更早纠正 open
- high/low 始终取极值，不受顺序影响

```
HGETALL stock:minute:sh600000:2026-07-02 11:30:00
→ open=8.59 high=8.59 low=8.59 close=8.59 last_vol=44555713 last_amt=387520785
```

### 3.5 分钟窗口股票索引 — `stock:minute:codes:{minuteTime}` ✅

| 属性 | 值 |
|------|-----|
| 类型 | Set（member=code） |
| 写入方 | `redis_minute_upsert.lua` → `SADD` |
| 用途 | flush/清理时遍历该窗口所有股票，避免 `KEYS *` 阻塞 Redis |

```
SMEMBERS stock:minute:codes:2026-07-02 11:30:00
→ sh600000, sh600001, sh600004, ...
```

### 3.6 活跃窗口集合 — `stock:minute:windows` ✅

| 属性 | 值 |
|------|-----|
| 类型 | Set（member="yyyy-MM-dd HH:mm:00"） |
| 写入方 | `redis_minute_upsert.lua` → `SADD` |
| 用途 | 日清/跨天时遍历所有窗口进行 flush 和清理 |

```
SMEMBERS stock:minute:windows
→ 2026-07-02 09:30:00, 2026-07-02 09:35:00, ...
```

---
### 3.7 排行榜 — `stock:rank:up` / `stock:rank:amount` ✅

**基本属性**

| 属性 | 值 |
|------|-----|
| 类型 | ZSET（member=code, score=排序依据值） |
| 写入方 | Spark Streaming → `RankWriter.update()`（每 batch 全量刷新） |
| 读取方 | `RankItemDTO` / platform-web API |
| TTL | 无（`DEL` + `ZADD` 全量替换） |

**已实现的 Rank Key**

| Key | score 字段 | 排序方向 | 读命令 |
|-----|-----------|---------|--------|
| `stock:rank:up` | `change_pct`（涨跌幅 %） | 高→低 | `ZREVRANGE stock:rank:up 0 19 WITHSCORES` |
| `stock:rank:amount` | `amount`（成交额，元） | 高→低 | `ZREVRANGE stock:rank:amount 0 19 WITHSCORES` |

**实现细节**

- 每 batch 执行：`SMEMBERS stock:quote:ohlcv:codes` → Pipeline `GET` 全量 OHLCV JSON → 提取 change_pct/amount → `DEL` + `ZADD` 逐股写入
- 跌幅榜：从 `stock:rank:up` 用 `ZRANGE 0 19`（从低到高取负值最大的，不另建 key）
- ZADD O(log N) 逐股更新，ZREVRANGE O(log N + M) 取 Top 20
- MySQL 同步：`ads_stock_rank`（REPLACE INTO，每 batch 60 行）

**待实现**

| Key | score 字段 | 说明 |
|-----|-----------|------|
| `stock:rank:quant` | `quant_score`（0-100） | 量化评分，依赖 `ads_quant_score` 离线任务 |

量化因子权重（`cluster/config/quant-weight.properties`）：

| 因子 | 权重 | 说明 |
|------|------|------|
| momentum | 0.35 | 动量因子 |
| volume_factor | 0.30 | 量能因子 |
| volatility | 0.20 | 波动率因子 |
| relative_strength | 0.15 | 相对强度因子 |

---

### 3.8 告警事件 — `stock:alert:latest` ❌

| 属性 | 值 |
|------|-----|
| 类型 | List（JSON String） |
| 写入方 | Spark Streaming（触发告警时 `LPUSH` + `LTRIM`） |
| 读取方 | `AlertDTO`（`LRANGE 0 49`） |
| TTL | 保留最近 1000 条 |

**JSON**
```json
{ "alert_type": "change_pct", "code": "sh600519",
  "curr_value": 5.23, "threshold": 5.00,
  "event_time": "2026-07-02 14:30:00" }
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `alert_type` | string | ✅ | `change_pct` / `volume_surge` / `quant_breakout` |
| `code` | string | ✅ | 股票代码 |
| `curr_value` | double | ✅ | 当前触发值 |
| `threshold` | double | ✅ | 触发阈值 |
| `event_time` | string | ✅ | 事件时间 `yyyy-MM-dd HH:mm:ss` |

```
LPUSH stock:alert:latest '{...json...}'
LTRIM stock:alert:latest 0 999
LRANGE stock:alert:latest 0 49
```

---

## 4. 已实现部分的实现细节

### 4.1 代码位置

| 文件 | 角色 |
|------|------|
| `core/.../streaming/MarketDataWriter.java` | 实时写入：日清/跨天检查 + Pipeline Lua EVALSHA → Redis + MySQL 市场概览 |
| `core/.../batch/MarketFlusher.java` | 持久化：分钟 LAG + 日线 flush → MySQL `dws_stock_minute` / `dws_stock_day` + key 清理 |
| `core/.../streaming/RankWriter.java` | 实时榜单：Redis ZSet 排名 + MySQL `ads_stock_rank` 写入 |
| `core/.../common/RedisKeys.java` | 统一 Redis key 常量（含 OHLCV 跟踪 SET `OHLCV_CODES`） |
| `core/.../common/LuaScriptManager.java` | Lua 脚本加载 + SHA 缓存管理 |
| `core/src/main/resources/redis_ohlcv_upsert.lua` | 日线 OHLCV + 市场汇总 Lua 原子更新（维护 `stock:quote:ohlcv:codes` SET） |
| `core/src/main/resources/redis_minute_upsert.lua` | 分钟 OHLCV raw 值记录，乱序处理 |
| `core/.../streaming/QuoteStreamingJob.java` | 入口：调用 `MarketDataWriter.write()` + `RankWriter.update()` + shutdown flush |
| `core/.../batch/FlushJob.java` | 独立 flush 任务（daily-replay / full-replay 最后一步） |

**辅助类**

| 文件 | 角色 |
|------|------|
| `core/.../common/RedisUtil.java` | Jedis 连接工厂 |
| `core/.../common/JdbcUtil.java` | JDBC 驱动 + 连接管理 |
| `core/.../common/StockQuote.java` | Kafka 消息 POJO |
| `core/.../common/Config.java` | 配置加载 |

### 4.2 Lua 脚本流程

```
EVALSHA → CJSON 解析新 JSON
        → GET 旧 JSON
        → 旧 == nil: total_stocks+1, up/down/flat 对应+1, sum/volume/amount 直接加
        → 旧 ≠ nil: sign 变则调计数, volume/amount/sum 做 delta
        → SET 新快照, HSET stat_time
        → avg_change_pct = _sum_pct / total_stocks
```

### 4.3 日清 + Flush

- 触发：Driver 侧每 batch 前 `HGET stat_date` ≠ 今日
- 流程：`flushAll`（分钟 LAG → MySQL + 日线 → MySQL）→ `DEL` Redis → `HSET stat_date`
- 跨天 batch（`distinct trade_date > 1`）：flushAll + DEL + 跳过
- shutdown 时：检测 marker 后先 flushAll 再停 StreamingContext

### 4.4 并发安全

- `HINCRBY`/`HINCRBYFLOAT` 是 Redis 原子命令
- Lua 脚本整体原子执行，单 key 读-改-写不被打断
- 不同 stock 数据完全独立
- EVALSHA（首次 SCRIPT LOAD，Redis 重启后 NOSCRIPT → 回退 EVAL → 重新 LOAD）

---

## 5. 数据流

```
新浪 API → Kafka(stock_quote_raw)
               │
               ▼
      Spark Streaming Consumer (QuoteStreamingJob)
               │
       ┌───────┼────────┬───────────┐
       ▼       ▼        ▼           ▼
    Redis   MySQL     HDFS       Kafka OA
  (实时)   dim_stock (DWD Parquet) (offset)
       │
       ├── RankWriter: ZADD → stock:rank:up / stock:rank:amount (每 batch)
       │
       │   日清/Shutdown:
       │   MarketFlusher → MySQL dws_stock_minute (LAG) + dws_stock_day
       │
       ▼
  platform-web API
       │
       ▼
   前端 Dashboard
```

| 数据 | Redis | MySQL |
|------|-------|-------|
| 市场概览 | ✅ `stock:market:summary` (Hash) | `ads_market_summary` |
| 个股 OHLCV | ✅ `stock:quote:ohlcv:{code}` (JSON) | `dws_stock_day` |
| 分钟 OHLCV | ✅ `stock:minute:{code}:{minuteTime}` (Hash) | `dws_stock_minute` |
| 个股盘口 | ✅ `stock:quote:{code}` (JSON) | — |
| 排行 | ✅ `stock:rank:up` / `stock:rank:amount` (ZSET) | `ads_stock_rank` |
| 量化 | ❌ `stock:rank:quant` (ZSET) | `ads_quant_score` |
| 告警 | ❌ `stock:alert:latest` (List) | — |

---

## 6. DTO 对齐

| DTO | 对应 Redis Key | 读取方式 |
|-----|---------------|---------|
| `MarketSummaryDTO` | `stock:market:summary` | `HGETALL` |
| `StockLatestDTO` | `stock:quote:{code}` | `GET` + key 中提取 code |
| `RankItemDTO` | `stock:rank:*` | `ZREVRANGE` / `ZRANGE` WITHSCORES |
| `AlertDTO` | `stock:alert:latest` | `LRANGE 0 49` |

> `stock:quote:ohlcv:{code}` 暂未对接 API，需新增 DTO 或在现有 DTO 追加字段。

---

## 7. 变更记录

| 日期 | 版本 | 变更 |
|------|------|------|
| 2026-07-07 | v2.2 | 新增 §3.2.1 OHLCV 跟踪 SET、§3.7 RankWriter 实时榜单 ✅、§4.1 代码拆分、§5 数据流更新；consumer 启动 FLUSHDB |
| 2026-07-06 | v2.1 | 新增 §3.4-3.6 分钟 OHLCV key，RedisWriter→MarketDataWriter，日清加 LAG flush，集群脚本优化 |
| 2026-07-03 | v2.0 | 全文重构：统一 Key 格式、标注实现状态、合并排行章节、拆分已实现细节、精简 DTO 对齐、新增 Key 一览表 |
| 2026-07-03 | v1.2 | 新增 `stock:quote:ohlcv:{code}` + stat_date/_sum_pct 字段 + Lua 机制说明 |
| 2026-07-02 | v1.1 | 以 platform-web DTO 为准重写 §3.2（Level-2 五档） |
| 2026-07-02 | v1.0 | 初始版本，定义全部 7 类 Key 的 schema |
