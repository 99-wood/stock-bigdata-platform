# platform-web 后端 API 文档

> SpringBoot 3.x 后端暴露的 REST API 与 WebSocket 接口。

---

## 目录

- [1. 统一返回格式](#1-统一返回格式)
- [2. HTTP API](#2-http-api)
  - [2.1 Dashboard — 市场概览](#21-dashboard--市场概览)
  - [2.2 Stock — 个股与榜单](#22-stock--个股与榜单)
  - [2.3 Alert — 预警消息](#23-alert--预警消息)
  - [2.4 Debug — 调试接口（仅 dev）](#24-debug--调试接口仅-dev)
- [3. WebSocket 实时推送](#3-websocket-实时推送)
- [4. 数据模型 (DTO)](#4-数据模型-dto)
- [5. Redis 数据源](#5-redis-数据源)

---

## 1. 统一返回格式

所有 HTTP 接口返回 `ApiResponse<T>`：

```json
{
  "code": 200,
  "message": "success",
  "data": { },
  "timestamp": 1751423400000
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `code` | `int` | 业务状态码 |
| `message` | `string` | 提示信息 |
| `data` | `T` / `null` | 返回数据 |
| `timestamp` | `long` | 毫秒级时间戳 |

**错误码：**

| code | 含义 |
|------|------|
| 200 | 成功 |
| 401 | 未登录 / Token 过期（预留） |
| 403 | 无权限（预留） |
| 404 | 资源不存在 |
| 500 | 服务器内部错误 |

---

## 2. HTTP API

### 基础信息

| 项目 | 值 |
|------|-----|
| 端口 | `8088` |
| 数据格式 | JSON, UTF-8 |

### 接口一览

| 方法 | 路径 | 说明 | 数据源 |
|------|------|------|--------|
| GET | `/api/dashboard/summary` | 市场概览 | `stock:market:summary` (Hash) |
| GET | `/api/stocks` | 股票列表 / 关键字搜索 | `stock:quote:*` (String) |
| GET | `/api/stocks/{code}` | 个股详情 | `stock:quote:{code}` (String) |
| GET | `/api/stocks/top-up` | 涨幅榜 Top N | `stock:rank:up` (ZSet) |
| GET | `/api/stocks/top-down` | 跌幅榜 Top N | `stock:rank:down` (ZSet) |
| GET | `/api/stocks/top-amount` | 成交额榜 Top N | `stock:rank:amount` (ZSet) |
| GET | `/api/stocks/top-quant` | 量化评分榜 Top N | `stock:rank:quant` (ZSet) |
| GET | `/api/alerts/latest` | 最新预警消息 | `stock:alert:latest` (List) |
| GET | `/api/debug/redis-keys` | Redis 诊断 *(dev only)* | 直接 SCAN Redis |

| Topic | 类型 | 说明 | 间隔 |
|-------|------|------|------|
| `/topic/market` | `MarketSummaryDTO` | 市场概览推送 | 5s |
| `/topic/rank/up` | `List<RankItemDTO>` | 涨幅榜 Top 20 | 5s |
| `/topic/rank/down` | `List<RankItemDTO>` | 跌幅榜 Top 20 | 5s |
| `/topic/rank/amount` | `List<RankItemDTO>` | 成交额榜 Top 20 | 5s |
| `/topic/time` | `string` | 最新交易时间 | 5s |

---

### 2.1 Dashboard — 市场概览

#### `GET /api/dashboard/summary`

获取市场概览。

- **Controller**: `DashboardController.java`
- **数据源**: Redis Hash `stock:market:summary`
- **返回值**: `ApiResponse<MarketSummaryDTO>`

```json
{
  "code": 200, "message": "success",
  "data": {
    "stat_time": "2026-07-06 14:30:00",
    "total_stocks": 5120,
    "up_count": 2340,
    "down_count": 2500,
    "flat_count": 280,
    "avg_change_pct": 0.35,
    "total_volume": 1234567890,
    "total_amount": 9876543.21
  },
  "timestamp": 1751423400000
}
```

---

### 2.2 Stock — 个股与榜单

**Controller**: `StockController.java`，基础路径 `/api/stocks`

#### `GET /api/stocks`

全量或关键字搜索股票列表。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `keyword` | `string` | 否 | 搜索关键字，匹配股票代码；不传返回全部 |

- **数据源**: Redis String `stock:quote:*`
- **返回值**: `ApiResponse<List<StockLatestDTO>>`

```json
{
  "code": 200, "message": "success",
  "data": [
    {
      "code": "sh600519",
      "name": "贵州茅台",
      "price": 1194.96,
      "bid": 1194.50,
      "ask": 1195.00,
      "open": 1169.00,
      "high": 1215.00,
      "low": 1151.01,
      "prev_close": 1168.63,
      "change": 26.33,
      "change_pct": 2.25,
      "volume": 66878,
      "amount": 794924,
      "trade_date": "2026-07-06",
      "trade_time": "14:30:00",
      "status": "00",
      "b1_p": "1194.50", "b1_v": "100",
      "s1_p": "1195.00", "s1_v": "200"
    }
  ]
}
```

#### `GET /api/stocks/{code}`

查询单只股票详情。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `code` | `string` (路径) | 是 | 股票代码，如 `sh600519` |

- **数据源**: Redis String `stock:quote:{code}`
- **返回值**: `ApiResponse<StockLatestDTO>`
- **错误**: 不存在时返回 `{ "code": 404, "message": "股票不存在: sh600519" }`

#### `GET /api/stocks/top-up`

涨幅榜 Top N。

| 参数 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `count` | `int` | 20 | 返回条数 |

- **数据源**: Redis ZSet `stock:rank:up`（score = `change_pct`，降序）+ MGET `stock:quote:*` 补全
- **返回值**: `ApiResponse<List<RankItemDTO>>`

#### `GET /api/stocks/top-down`

跌幅榜 Top N。

| 参数 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `count` | `int` | 20 | 返回条数 |

- **数据源**: Redis ZSet `stock:rank:down`（score = `change_pct`，升序）+ MGET 补全
- **返回值**: `ApiResponse<List<RankItemDTO>>`

#### `GET /api/stocks/top-amount`

成交额榜 Top N。

| 参数 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `count` | `int` | 20 | 返回条数 |

- **数据源**: Redis ZSet `stock:rank:amount`（score = `amount`，降序）+ MGET 补全
- **返回值**: `ApiResponse<List<RankItemDTO>>`

#### `GET /api/stocks/top-quant`

量化评分榜 Top N。

| 参数 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `count` | `int` | 20 | 返回条数 |

- **数据源**: Redis ZSet `stock:rank:quant`（score = `quant_score`，降序）+ MGET 补全
- **返回值**: `ApiResponse<List<RankItemDTO>>`

---

### 2.3 Alert — 预警消息

**Controller**: `AlertController.java`，基础路径 `/api/alerts`

#### `GET /api/alerts/latest`

获取最近 N 条预警消息。

| 参数 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `count` | `int` | 50 | 返回条数 |

- **数据源**: Redis List `stock:alert:latest`
- **返回值**: `ApiResponse<List<AlertDTO>>`

```json
{
  "code": 200, "message": "success",
  "data": [
    {
      "alert_type": "pct_up",
      "code": "sh600519",
      "name": "贵州茅台",
      "curr_value": 6.25,
      "threshold": 5.0,
      "event_time": "2026-07-06 14:30:00"
    }
  ]
}
```

---

### 2.4 Debug — 调试接口（仅 dev）

**Controller**: `DebugController.java`，基础路径 `/api/debug`
**生效条件**: Spring Profile = `dev`

#### `GET /api/debug/redis-keys`

Redis 诊断：扫描 Key、查看数据结构与内容预览。

| 参数 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `pattern` | `string` | `*` | Redis SCAN 匹配模式 |
| `sampleSize` | `int` | 20 | 采样数量 |

- **返回值**: `ApiResponse<Map<String, Object>>`

```json
{
  "code": 200, "message": "success",
  "data": {
    "connected": true,
    "totalKeys": 5234,
    "pattern": "*",
    "sampleCount": 5,
    "sampleKeys": ["stock:quote:sh600519", "stock:rank:up"],
    "keyDetails": [
      {
        "key": "stock:quote:sh600519",
        "type": "string",
        "valuePreview": "{\"price\":1194.96,...}"
      }
    ],
    "appPatterns": {
      "stock:quote:* (hits)": 5120,
      "stock:rank:* (hits)": 4,
      "stock:market:* (hits)": 1,
      "stock:alert:* (hits)": 1
    }
  }
}
```

---

## 3. WebSocket 实时推送

### 连接信息

| 项目 | 值 |
|------|-----|
| 协议 | STOMP over WebSocket (SockJS) |
| 端点 | `/ws` |
| 心跳 | 出/入 4000ms |
| 推送间隔 | `platform.push.interval`（默认 5000ms） |
| Broker 前缀 | `/topic` |
| 应用前缀 | `/app` |
| CORS | `http://localhost:*` |

### 推送 Topic

`MarketPushScheduler.java` 通过 `SimpMessagingTemplate` 定时推送以下 5 个 Topic：

| Topic | 数据类型 | 说明 |
|-------|----------|------|
| `/topic/market` | `MarketSummaryDTO` (JSON) | 市场概览全量 |
| `/topic/rank/up` | `List<RankItemDTO>` (JSON) | 涨幅榜 Top 20 |
| `/topic/rank/down` | `List<RankItemDTO>` (JSON) | 跌幅榜 Top 20 |
| `/topic/rank/amount` | `List<RankItemDTO>` (JSON) | 成交额榜 Top 20 |
| `/topic/time` | `string` (纯文本) | 最新交易时间 `yyyy-MM-dd HH:mm:ss` |

### 推送流程

```
@Scheduled(fixedDelayString="${platform.push.interval:5000}")
    │
    ├─ redisService.getMarketSummary()  → HGETALL stock:market:summary
    ├─ redisService.getTopUp(20)        → ZREVRANGE stock:rank:up
    ├─ redisService.getTopDown(20)      → ZRANGE stock:rank:down
    ├─ redisService.getTopAmount(20)    → ZREVRANGE stock:rank:amount
    ├─ redisService.getLatestTradeTime()
    │
    ├─ messagingTemplate.convertAndSend("/topic/market", summary)
    ├─ messagingTemplate.convertAndSend("/topic/rank/up", topUp)
    ├─ messagingTemplate.convertAndSend("/topic/rank/down", topDown)
    ├─ messagingTemplate.convertAndSend("/topic/rank/amount", topAmount)
    └─ messagingTemplate.convertAndSend("/topic/time", tradeTime)
```

---

## 4. 数据模型 (DTO)

### 4.1 StockLatestDTO — 个股实时行情

来源: `platform-web/backend/stock-api/src/main/java/com/stock/api/model/dto/StockLatestDTO.java`

| 字段 | 类型 | JSON Key | 说明 |
|------|------|----------|------|
| `code` | `String` | `code` | 股票代码（从 Redis Key 解析） |
| `name` | `String` | `name` | 股票名称（从 MySQL `dim_stock` 补全） |
| `bid` | `Double` | `bid` | 买一价 |
| `ask` | `Double` | `ask` | 卖一价 |
| `tradeDate` | `String` | `trade_date` | 交易日期 `yyyy-MM-dd` |
| `tradeTime` | `String` | `trade_time` | 交易时间 `HH:mm:ss` |
| `b1v` ~ `b5v` | `String` | `b1_v` ~ `b5_v` | 买一~买五量 |
| `b1p` ~ `b5p` | `String` | `b1_p` ~ `b5_p` | 买一~买五价 |
| `s1v` ~ `s5v` | `String` | `s1_v` ~ `s5_v` | 卖一~卖五量 |
| `s1p` ~ `s5p` | `String` | `s1_p` ~ `s5_p` | 卖一~卖五价 |
| `status` | `String` | `status` | "00"正常, "-1"停牌, "-2"跌停 |
| `price` | `Double` | `price` | 当前成交价（元） |
| `open` | `Double` | `open` | 开盘价 |
| `high` | `Double` | `high` | 最高价 |
| `low` | `Double` | `low` | 最低价 |
| `prevClose` | `Double` | `pre_close` | 昨收价 |
| `volume` | `Long` | `volume` | 成交量（手） |
| `amount` | `Long` | `amount` | 成交额（万元） |
| `changeAmt` | `Double` | `change` | 涨跌额（元） |
| `changePct` | `Double` | `change_pct` | 涨跌幅（%） |

### 4.2 RankItemDTO — 榜单条目

来源: `platform-web/backend/stock-api/src/main/java/com/stock/api/model/dto/RankItemDTO.java`

| 字段 | 类型 | 说明 |
|------|------|------|
| `code` | `String` | 股票代码 |
| `bid` | `Double` | 买一价 |
| `ask` | `Double` | 卖一价 |
| `tradeDate` | `String` | 交易日期 |
| `tradeTime` | `String` | 交易时间 |
| `score` | `Double` | ZSet score（涨跌幅 / 成交额 / 量化分） |
| `changePct` | `Double` | 涨跌幅 |
| `status` | `String` | 状态码 |

### 4.3 MarketSummaryDTO — 市场概览

来源: `platform-web/backend/stock-api/src/main/java/com/stock/api/model/dto/MarketSummaryDTO.java`

| 字段 | 类型 | JSON Key | 说明 |
|------|------|----------|------|
| `statTime` | `String` | `stat_time` | 统计时间 |
| `totalStocks` | `Integer` | `total_stocks` | 有效股票总数 |
| `upCount` | `Integer` | `up_count` | 上涨家数（`change_pct > 0`） |
| `downCount` | `Integer` | `down_count` | 下跌家数（`change_pct < 0`） |
| `flatCount` | `Integer` | `flat_count` | 平盘家数（`change_pct = 0`） |
| `avgChangePct` | `Double` | `avg_change_pct` | 平均涨跌幅（%） |
| `totalVolume` | `Long` | `total_volume` | 总成交量（手） |
| `totalAmount` | `Double` | `total_amount` | 总成交额（万元） |

### 4.4 AlertDTO — 预警消息

来源: `platform-web/backend/stock-api/src/main/java/com/stock/api/model/dto/AlertDTO.java`

| 字段 | 类型 | JSON Key | 说明 |
|------|------|----------|------|
| `alertType` | `String` | `alert_type` | `pct_up` / `pct_down` / `volume_surge` / `price_breakout` |
| `code` | `String` | `code` | 股票代码 |
| `name` | `String` | `name` | 股票名称 |
| `currValue` | `Double` | `curr_value` | 当前触发值 |
| `threshold` | `Double` | `threshold` | 触发阈值 |
| `eventTime` | `String` | `event_time` | 触发时间 `yyyy-MM-dd HH:mm:ss` |

---

## 5. Redis 数据源

本模块只读不写，数据由 Spark Streaming 写入。

| Redis Key | 类型 | 对应 DTO | 后端读取方式 |
|-----------|------|----------|-------------|
| `stock:quote:{code}` | String (JSON) | `StockLatestDTO` | `GET` + Jackson 反序列化 |
| `stock:rank:up` | ZSet | `RankItemDTO` | `ZREVRANGE` → 批量 `MGET quote:*` 补全 |
| `stock:rank:down` | ZSet | `RankItemDTO` | `ZRANGE` → 批量 `MGET quote:*` |
| `stock:rank:amount` | ZSet | `RankItemDTO` | `ZREVRANGE` → 批量 `MGET quote:*` |
| `stock:rank:quant` | ZSet | `RankItemDTO` | `ZREVRANGE` → 批量 `MGET quote:*` |
| `stock:market:summary` | Hash | `MarketSummaryDTO` | `HGETALL` |
| `stock:alert:latest` | List | `AlertDTO` | `LRANGE 0 N-1` |

> `code` 不在 `stock:quote:{code}` JSON 中，从 Redis Key 解析。`name` 从 MySQL `dim_stock` 补全。
