# Redis Schema — 股票大数据平台

> **版本**: v1.1  
> **状态**: 以 platform-web API 端实际 DTO 为准  
> **更新**: 2026-07-02  

---

## 1. 概述

Redis 作为实时数据缓存层，承载以下数据：

- 市场概览（每批次更新）
- 个股实时行情（逐笔更新）
- 涨跌 / 成交额 / 量化排行（每 5 分钟刷新）
- 告警事件（最新 N 条）

所有 key 均位于同一 Redis 实例（`mid:6379`），使用 `stock:` 命名空间。

> ⚠️ **name 不存 Redis**：股票名称不是实时变动数据，Redis 中不存储 `name` 字段。
> 前端需要展示名称时，用 `code` 去 MySQL `dim_stock` 表查询。

---

## 2. 命名规范

```
stock:{category}:{subcategory}
stock:{category}:{identifier}
```

| 规则 | 说明 |
|------|------|
| 分隔符 | `:` 冒号 |
| 股票代码格式 | `sh600519` / `sz000001` / `bj430047`（小写） |
| 数字字段 | JSON String 中保持原生类型（double / int / long），Hash 中以字符串存储 |

---

## 3. Key 定义

### 3.1 市场概览 — `stock:market:summary`

| 属性 | 值 |
|------|-----|
| **类型** | Hash |
| **写入方** | Spark Streaming Consumer（实时计算，每批次 HSET） |
| **读取方** | platform-web API → `MarketSummaryDTO` |
| **TTL** | 无（持续覆盖） |
| **MySQL 对应表** | `ads_market_summary` |

**Hash Fields:**

| Field | 类型 | 示例 | 说明 |
|-------|------|------|------|
| `stat_time` | string | `2026-07-02 14:30:00` | 统计批次时间 |
| `total_stocks` | int | `4521` | 有效股票总数 |
| `up_count` | int | `2180` | 上涨家数（change_pct > 0） |
| `down_count` | int | `2015` | 下跌家数（change_pct < 0） |
| `flat_count` | int | `326` | 平盘家数（change_pct = 0） |
| `avg_change_pct` | double | `0.38` | 平均涨跌幅 % |
| `total_volume` | long | `384920100` | 全市场总成交量（手） |
| `total_amount` | long | `5238491200` | 全市场总成交额（万元），取整 |

**写入示例 (Redis CLI):**
```
HSET stock:market:summary stat_time "2026-07-02 14:30:00" total_stocks 4521 up_count 2180 down_count 2015 flat_count 326 avg_change_pct 0.38 total_volume 384920100 total_amount 5238491200
```

> 📐 **扩展点**: 若后续成交额需要小数精度，`total_amount` 可改为 double/string 存储（如 `"5238491200.50"`），
> 写入方更新即可，读取方 `MarketSummaryDTO.totalAmount` 同步改为 `Double`。

---

### 3.2 个股实时行情 — `stock:quote:{code}`

| 属性 | 值 |
|------|-----|
| **类型** | String (JSON) |
| **写入方** | Spark Streaming Consumer（从 Kafka 消费行情数据） |
| **读取方** | platform-web API → `StockLatestDTO` |
| **TTL** | 无（持续覆盖；可考虑收盘后保留 24h） |

**当前 JSON Schema（v1 — Level-2 五档）:**

```json
{
  "trade_date": "2026-07-02",
  "trade_time": "14:30:00",
  "bid": 1850.50,
  "ask": 1851.00,
  "b1_v": "100",
  "b1_p": "1850.50",
  "b2_v": "200",
  "b2_p": "1850.00",
  "b3_v": "300",
  "b3_p": "1849.50",
  "b4_v": "150",
  "b4_p": "1849.00",
  "b5_v": "500",
  "b5_p": "1848.00",
  "s1_v": "80",
  "s1_p": "1851.00",
  "s2_v": "120",
  "s2_p": "1852.00",
  "s3_v": "200",
  "s3_p": "1853.00",
  "s4_v": "100",
  "s4_p": "1855.00",
  "s5_v": "300",
  "s5_p": "1856.00",
  "status": "00"
}
```

**字段说明:**

| 字段 | JSON 类型 | Java 类型 | 必填 | 说明 |
|------|----------|----------|------|------|
| `trade_date` | string | String | ✅ | 交易日期 `yyyy-MM-dd` |
| `trade_time` | string | String | ✅ | 交易时间 `HH:mm:ss` |
| `bid` | number | Double | ✅ | 买一价 |
| `ask` | number | Double | ✅ | 卖一价 |
| `b1_v` ~ `b5_v` | string | String | | 买一~五档量（手） |
| `b1_p` ~ `b5_p` | string | String | | 买一~五档价 |
| `s1_v` ~ `s5_v` | string | String | | 卖一~五档量（手） |
| `s1_p` ~ `s5_p` | string | String | | 卖一~五档价 |
| `status` | string | String | | 状态码，`00`=正常 |

> ⚠️ **code 不在 JSON 内**：code 从 Redis key 中提取。
> 例如 `stock:quote:sh600519` → `code = "sh600519"`，由 `RedisService` set 到 DTO 上。

**📐 扩展规划（v2 — Level-1 OHLCV 兼容字段）:**

为兼容日线分析、K 线图等场景，后续可在 JSON 中**追加**以下可选字段（向后兼容，旧字段不动）：

```json
{
  "...以上 v1 字段保留...": "",
  "price": 1850.50,            // 新增: 当前价（= bid）
  "open": 1845.00,             // 新增: 开盘价
  "high": 1855.00,             // 新增: 最高价
  "low": 1842.00,              // 新增: 最低价
  "pre_close": 1846.00,        // 新增: 前收盘价
  "volume": 12345678,          // 新增: 累计成交量（手）
  "amount": 22849365000.00,    // 新增: 累计成交额（万元）
  "change": 4.50,              // 新增: 涨跌额
  "change_pct": 0.2438         // 新增: 涨跌幅 %
}
```

> Jackson 默认忽略未知字段，新增字段不会破坏现有反序列化。
> 写入方可按数据源能力逐步追加，读取方 `StockLatestDTO` 按需添加对应 getter。

---

### 3.3 涨幅排行 — `stock:rank:up`

| 属性 | 值 |
|------|-----|
| **类型** | Sorted Set (ZSET) |
| **写入方** | Spark 离线任务（每 5 分钟 ZADD 一批） |
| **读取方** | platform-web API → `RankItemDTO`（ZRANGE / ZREVRANGE 取 Top N） |
| **TTL** | 无（每批次全量替换: DEL + ZADD） |

**ZSET 结构:**
- **member**: 股票代码（如 `sh600519`）
- **score**: `change_pct`（涨跌幅，double），正值为上涨

**读取命令:**
```
ZREVRANGE stock:rank:up 0 19 WITHSCORES
```

> 每 5 分钟全量刷新：`DEL stock:rank:up` → `ZADD stock:rank:up 2.5 sh600519 1.8 sz000001 ...`

---

### 3.4 跌幅排行 — `stock:rank:down`

| 属性 | 值 |
|------|-----|
| **类型** | Sorted Set (ZSET) |
| **写入方** | Spark 离线任务（每 5 分钟） |
| **读取方** | platform-web API → `RankItemDTO`（ZRANGE 取跌幅最大） |
| **TTL** | 无（每批次全量替换） |

**ZSET 结构:**
- **member**: 股票代码
- **score**: `change_pct`（负值越大 = 跌越多）

**读取命令（取跌幅最大前 20）:**
```
ZRANGE stock:rank:down 0 19 WITHSCORES
```

---

### 3.5 成交额排行 — `stock:rank:amount`

| 属性 | 值 |
|------|-----|
| **类型** | Sorted Set (ZSET) |
| **写入方** | Spark 离线任务（每 5 分钟） |
| **读取方** | platform-web API → `RankItemDTO`（ZRANGE / ZREVRANGE 取 Top N） |
| **TTL** | 无（每批次全量替换） |

**ZSET 结构:**
- **member**: 股票代码
- **score**: `amount`（成交额，万元）

**读取命令:**
```
ZREVRANGE stock:rank:amount 0 19 WITHSCORES
```

---

### 3.6 量化评分排行 — `stock:rank:quant`

| 属性 | 值 |
|------|-----|
| **类型** | Sorted Set (ZSET) |
| **写入方** | Spark 离线任务（每 5 分钟 / 每日） |
| **读取方** | platform-web API → `RankItemDTO`（ZRANGE / ZREVRANGE 取 Top N） |
| **TTL** | 无（每批次全量替换） |

**ZSET 结构:**
- **member**: 股票代码
- **score**: `quant_score`（综合评分 0-100）

**量化因子**（4 因子权重来自 `cluster/config/quant-weight.properties`）:

| 因子 | 权重 | 说明 |
|------|------|------|
| momentum | 0.35 | 动量因子 |
| volume_factor | 0.30 | 量能因子 |
| volatility | 0.20 | 波动率因子 |
| relative_strength | 0.15 | 相对强度因子 |

**读取命令:**
```
ZREVRANGE stock:rank:quant 0 19 WITHSCORES
```

---

### 3.7 告警事件 — `stock:alert:latest`

| 属性 | 值 |
|------|-----|
| **类型** | List |
| **写入方** | Spark Streaming（触发告警时 LPUSH） |
| **读取方** | platform-web API → `AlertDTO`（LRANGE 取最新 N 条） |
| **TTL** | 建议保留最近 1000 条（LTRIM 裁剪） |

**Value (JSON String):**

```json
{
  "alert_type": "change_pct",
  "code": "sh600519",
  "curr_value": 5.23,
  "threshold": 5.00,
  "event_time": "2026-07-02 14:30:00"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `alert_type` | string | ✅ | 告警类型：`change_pct` / `volume_surge` / `quant_breakout` |
| `code` | string | ✅ | 股票代码 |
| `curr_value` | double | ✅ | 当前触发值 |
| `threshold` | double | ✅ | 触发阈值 |
| `event_time` | string | ✅ | 事件时间 `yyyy-MM-dd HH:mm:ss` |

**写入命令:**
```
LPUSH stock:alert:latest '{...json...}'
LTRIM stock:alert:latest 0 999
```

**读取命令:**
```
LRANGE stock:alert:latest 0 49
```

---

## 4. 数据流架构

```
新浪 API → Kafka (raw quotes)
               │
               ▼
      Spark Streaming Consumer
               │
       ┌───────┼────────┐
       ▼       ▼        ▼
    Redis   MySQL    HDFS
  (实时)   (兜底)   (离线)
       │
       ▼
  platform-web API
       │
       ▼
   前端 Dashboard
```

| 数据 | Redis（实时） | MySQL（兜底/历史） |
|------|-------------|-----------------|
| 市场概览 | `stock:market:summary` (Hash) | `ads_market_summary` |
| 个股行情 | `stock:quote:{code}` (JSON) | `dws_stock_day` / `dws_stock_minute` |
| 排行 | `stock:rank:*` (ZSET) | `ads_stock_rank` |
| 量化 | `stock:rank:quant` (ZSET) | `ads_quant_score` |
| 告警 | `stock:alert:latest` (List) | （暂无 MySQL 表，后续补充） |

---

## 5. 前后端对齐状态

| DTO | Schema 匹配 | 备注 |
|-----|-----------|------|
| `StockLatestDTO` | ✅ 匹配 | Level-2 五档（bid/ask/b1_p~b5_p/s1_p~s5_p），code 从 key 提取 |
| `MarketSummaryDTO` | ✅ 匹配 | `totalAmount` 当前为 `Long`（取整），如需小数精度见 §3.1 扩展点 |
| `RankItemDTO` | ✅ 匹配 | bid/ask/tradeDate/tradeTime/score/status，score 来自 ZSET |
| `AlertDTO` | ✅ 匹配 | alert_type/code/name/curr_value/threshold/event_time |

---

## 6. 对接指引（给 Spark Streaming 写入方）

写入方（PR #9 或后续任务）需要按本文档定义的 Key 和数据结构写入 Redis：

| 数据 | Redis Key | 数据结构 | 写入命令 |
|------|----------|---------|---------|
| 市场概览 | `stock:market:summary` | Hash（8 字段） | `HSET` |
| 个股行情 | `stock:quote:{code}` | JSON String（Level-2 五档） | `SET` |
| 涨幅排行 | `stock:rank:up` | ZSET（member=code, score=change_pct） | `DEL` + `ZADD` 全量刷新 |
| 跌幅排行 | `stock:rank:down` | ZSET（member=code, score=change_pct） | `DEL` + `ZADD` 全量刷新 |
| 成交额排行 | `stock:rank:amount` | ZSET（member=code, score=amount） | `DEL` + `ZADD` 全量刷新 |
| 量化排行 | `stock:rank:quant` | ZSET（member=code, score=quant_score） | `DEL` + `ZADD` 全量刷新 |
| 告警 | `stock:alert:latest` | List（JSON String） | `LPUSH` + `LTRIM` |

> ⚠️ **重要**: 写入方必须严格对齐本文档的 JSON 字段名（包括 snake_case 的 `@JsonProperty` 名称），
> 否则 API 端反序列化后字段为 null。

---

## 7. 变更记录

| 日期 | 版本 | 变更 |
|------|------|------|
| 2026-07-02 | v1.1 | 以 platform-web DTO 为准重写 §3.2（Level-2 五档），新增扩展规划、对接指引 |
| 2026-07-02 | v1.0 | 初始版本，定义全部 7 类 Key 的 schema |
