# platform-web — 可视化与后端模块

> 本模块负责 SpringBoot 后端 API 和 Vue 3 前端展示，聚合 Redis 实时数据和 MySQL 历史数据，为数据大屏和管理后台提供数据服务。

> Redis 实时数据契约见 [../docs/REDIS_SCHEMA.md](../docs/REDIS_SCHEMA.md)。

---

## 1. 模块职责

| 子模块 | 技术 | 职责 |
|--------|------|------|
| 后端 API | SpringBoot 3.x + MyBatis-Plus | 聚合 Redis + MySQL，提供 REST API + WebSocket 推送 |
| 前端大屏 | Vue 3 + Element Plus + ECharts 5 | 市场概览、实时榜单、K线图、量化评分 |
| 管理后台 | Vue 3 + Element Plus | 用户登录、自选股、预警规则、数据质量 |

---

## 2. 技术栈

| 层 | 选型 | 版本 | 选型理由 |
|----|------|------|---------|
| 前端框架 | Vue 3 | 3.4+ | 组合式 API，新项目直接用最新 |
| UI 组件库 | Element Plus | 2.x | Element UI 的 Vue 3 替代 |
| 图表 | ECharts | 5.x | K线图、分时图、柱状图 |
| 构建工具 | Vite | 5.x | 开发热更新快 |
| 状态管理 | Pinia | 2.x | Vue 3 官方推荐 |
| HTTP 客户端 | Axios | — | |
| 实时推送 | WebSocket + STOMP | — | 行情实时刷新，推送间隔可配置 |
| 语言 | JavaScript | — | 团队更熟悉 |
| 后端框架 | SpringBoot | 3.x (JDK 17) | 独立部署，不受集群 JDK 8 限制 |
| ORM | MyBatis-Plus | 3.5.x | 复杂 SQL 灵活可控 |
| Redis 客户端 | Lettuce | — | SpringBoot 3.x 默认，非阻塞 |
| 数据库连接池 | HikariCP | — | SpringBoot 默认 |

### 特定不采用的方案

| 方案 | 原因 |
|------|------|
| 若依框架 (RuoYi) | 基于 Vue 2，与 Vue 3 不兼容。登录页、菜单布局、用户管理均自己手写 |
| SSE 推送 | 单向通信不如 WebSocket 灵活 |
| TypeScript | 团队偏好 JavaScript |

---

## 3. 项目结构（规划）

```
platform-web/
├── README.md                          ← 本文档
│
├── backend/                           ← SpringBoot 后端
│   └── stock-api/
│       ├── pom.xml
│       └── src/main/
│           ├── java/com/stock/api/
│           │   ├── Application.java
│           │   ├── config/
│           │   │   ├── RedisConfig.java
│           │   │   └── WebSocketConfig.java
│           │   ├── controller/
│           │   │   ├── DashboardController.java
│           │   │   ├── StockController.java
│           │   │   └── AlertController.java
│           │   ├── service/
│           │   │   ├── StockService.java
│           │   │   └── RedisService.java
│           │   ├── mapper/
│           │   │   └── StockAdsMapper.java
│           │   ├── model/
│           │   │   ├── dto/
│           │   │   └── entity/
│           │   └── common/
│           └── resources/
│               └── application.yml
│
├── frontend/                          ← Vue 3 前端
│   └── stock-web/
│       ├── vite.config.js
│       ├── package.json
│       └── src/
│           ├── App.vue
│           ├── main.js
│           ├── router/
│           │   └── index.js           ← /dashboard + /admin 两路由
│           ├── views/
│           │   ├── Dashboard.vue      ← 数据大屏
│           │   └── Admin.vue          ← 管理后台
│           ├── components/
│           │   ├── MarketSummary.vue   ← 市场概览卡片
│           │   ├── RankPanel.vue       ← 榜单面板（涨/跌/额/量化）
│           │   ├── KLineChart.vue      ← K线图（ECharts）
│           │   ├── MinuteChart.vue     ← 分时图
│           │   ├── QuantRadar.vue      ← 量化评分雷达图
│           │   └── AlertTicker.vue     ← 预警滚动条
│           ├── stores/                 ← Pinia
│           │   └── stock.js
│           ├── api/                    ← Axios 封装 + WebSocket
│           │   ├── request.js
│           │   └── websocket.js
│           └── utils/
│
└── docs/                              ← 模块内部文档
    └── api-response-format.md         ← API 统一返回格式（待写）
```

---

## 4. 数据源

### 4.1 Redis（实时数据，Spark Streaming / Spark SQL 写入）

> Redis 数据格式的完整定义见 **[docs/REDIS_SCHEMA.md](../../docs/REDIS_SCHEMA.md)**，以下仅列出 key 清单和本模块的消费关系。

**全局约定：**
- 时间格式：`yyyy-MM-dd HH:mm:ss`（北京时间 UTC+8）
- 股票代码格式：市场前缀 + 数字，如 `sh600519`
- Redis 不存 `name`，前端需要名称时用 code 去 MySQL `dim_stock` 查

| Key | 类型 | 读取方 DTO | 说明 |
|-----|------|----------|------|
| `stock:quote:{code}` | String (JSON) | `StockLatestDTO` | 个股 Level-2 五档行情，无 TTL |
| `stock:market:summary` | Hash | `MarketSummaryDTO` | 市场概览 8 字段 |
| `stock:rank:up` | ZSet | `RankItemDTO` | 涨幅榜，score = `change_pct` |
| `stock:rank:down` | ZSet | `RankItemDTO` | 跌幅榜，score = `change_pct` |
| `stock:rank:amount` | ZSet | `RankItemDTO` | 成交额榜，score = `amount` |
| `stock:rank:quant` | ZSet | `RankItemDTO` | 量化评分榜，score = `quant_score` |
| `stock:alert:latest` | List | `AlertDTO` | 最新预警，LPUSH + LTRIM |
| `stock:hot:5m` | ZSet | （P2 暂不开发） | 用户关注热度，score = 热度指数 |

> `code` 不在 `stock:quote:{code}` 的 JSON 内，从 Redis key 提取。

---

### 4.2 MySQL stock_ads（历史数据，Spark SQL 写入，后端只读）

> DDL 来自 `cluster/sql/sqlinit.txt`，8 张表。P0 阶段全部不涉及，仅在代码中预留 Mapper，等 C 灌入数据后自动生效。

| 表 | 用途 | P0 需要？ | 状态 |
|----|------|:--:|------|
| `dim_stock` | 股票维表（code/name/market/industry） | 否 | ⏳ 待 C 初始化 |
| `dws_stock_day` | 日级 K 线 OHLCV | 否 | ⏳ 待 C |
| `dws_stock_minute` | 分钟级 K 线 | 否 | ⏳ 待 C |
| `ads_market_summary` | 市场概览历史快照（Redis 兜底） | 否 | ⏳ 待 C |
| `ads_stock_rank` | 榜单历史快照（Redis 兜底） | 否 | ⏳ 待 C |
| `ads_quant_score` | 量化评分 4 因子明细 | 否 | ⏳ 待 C |
| `ads_strategy_backtest` | 策略回测结果 | 否 | ⏳ 待 C |
| `ads_data_quality` | 数据质量记录 | 否 | ⏳ 待 C |

> 量化因子以 sqlinit.txt 为准（4 因子：momentum / volume_factor / volatility / relative_strength），权重见 `cluster/config/quant-weight.properties`。

---

## 5. API 清单与分阶段交付

### P0 — 实时展示（当前可做，纯 Redis，不依赖 MySQL）

| API | 方法 | 数据源 | 说明 |
|-----|------|--------|------|
| `/api/dashboard/summary` | GET | Redis `stock:market:summary` | 市场概览 8 字段 |
| `/api/stocks/top-up` | GET | Redis `stock:rank:up` + MGET 补全 | 涨幅榜 Top 20 |
| `/api/stocks/top-down` | GET | Redis `stock:rank:down` + MGET 补全 | 跌幅榜 Top 20 |
| `/api/stocks/top-amount` | GET | Redis `stock:rank:amount` + MGET 补全 | 成交额榜 Top 20 |
| `/api/stocks/{code}` | GET | Redis `stock:quote:{code}` | 个股实时行情（Level-2 五档），code 从 Key 解析 |
| `/api/alerts/latest` | GET | Redis `stock:alert:latest` | 最近 50 条预警 |

### P1 — 历史数据（依赖 C 离线数仓灌入 MySQL stock_ads）

| API | 方法 | 数据源 | 说明 |
|-----|------|--------|------|
| `/api/stocks/{code}/history?period=day` | GET | MySQL `dws_stock_day` | 日 K 线 |
| `/api/stocks/{code}/history?period=minute` | GET | MySQL `dws_stock_minute` | 分钟 K 线 |
| `/api/stocks/{code}/quant` | GET | MySQL `ads_quant_score` | 4 因子明细 + quant_score |
| `/api/stocks/top-quant` | GET | Redis `stock:rank:quant` + MGET 补全 | 量化评分榜 Top 20 |

### P2 — 增强（待定，不列入当前开发范围）

| API | 方法 | 说明 |
|-----|------|------|
| `/api/stocks/top-hot` | GET | 热度榜（Redis ZSet） |
| 策略回测 / 数据质量 | GET | 读 MySQL stock_ads |

---

## 6. 前端页面规划

### 6.1 数据大屏 `/dashboard`

```
┌────────────────────────────────────────────────────┐
│  市场概览                            [自动刷新 5s]  │
│  ┌────────┐ ┌────────┐ ┌────────┐ ┌────────────┐  │
│  │ 上涨   │ │ 下跌   │ │ 平盘   │ │ 平均涨跌幅  │  │
│  │ 2340 ▲│ │ 2500 ▼│ │  280  │ │  +0.35%    │  │
│  └────────┘ └────────┘ └────────┘ └────────────┘  │
│                                                    │
│  ┌──────────────┐ ┌──────────────┐                │
│  │  涨幅榜 Top10 │ │  跌幅榜 Top10 │                │
│  │  (柱状图)     │ │  (柱状图)     │                │
│  └──────────────┘ └──────────────┘                │
│                                                    │
│  ┌──────────────┐ ┌──────────────┐                │
│  │  成交额榜     │ │  量化评分榜   │ ⏳             │
│  │  Top10       │ │  Top10       │                │
│  └──────────────┘ └──────────────┘                │
│                                                    │
│  ┌─────────────────────────────────────────────┐   │
│  │  预警滚动条                                  │   │
│  └─────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────┘
```

### 6.2 个股详情页（从榜单点击跳转）

```
┌────────────────────────────────────────────────────┐
│  贵州茅台 sh600519  ¥1194.96  +2.25%  +26.33       │
│  开 1169.00  高 1215.00  低 1151.01  昨收 1168.63   │
│  量 66,878手  额 794,924万元                      │
│  [★ 加入自选]                                      │
│                                                    │
│  ┌──────────────────────────────────────────────┐  │
│  │              日 K 线图 (ECharts)              │ ⏳ │
│  └──────────────────────────────────────────────┘  │
│                                                    │
│  ┌──────────────────┐ ┌──────────────────────┐     │
│  │  量化评分雷达图   │ │  因子明细            │ ⏳  │
│  │  动量 量能       │ │  动量: 72.5          │     │
│  │  波动 强度       │ │  量能: 65.3          │     │
│  └──────────────────┘ └──────────────────────┘     │
└────────────────────────────────────────────────────┘
```

### 6.3 管理后台 `/admin`（后续扩展，P0 不做）

- 用户登录/注册
- 自选股管理
- 预警规则管理
- 数据质量监控面板

---

## 7. WebSocket 实时推送

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| 推送间隔 | 5000 ms | `application.yml` 中 `platform.push.interval` 可配 |
| 推送内容 | 市场概览 + 榜单 Top 20 | 增量推送 |
| 技术 | STOMP over WebSocket | `/topic/market`、`/topic/rank` |

```
SpringBoot                    Vue 前端
    │                            │
    │  @Scheduled(fixedDelayString="${platform.push.interval}")
    │  读 Redis → 组装数据       │
    │                            │
    │──── STOMP /topic/market ──→│  更新 MarketSummary 组件
    │                            │
```

---

## 8. API 统一返回格式

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
| `code` | Integer | 业务状态码，200 成功 |
| `message` | String | 提示信息 |
| `data` | Object/Array/null | 返回数据 |
| `timestamp` | Long | 响应时间戳 (ms) |

错误码规划：

| code | 含义 |
|------|------|
| 200 | 成功 |
| 401 | 未登录 / Token 过期 |
| 403 | 无权限 |
| 404 | 资源不存在 |
| 500 | 服务器内部错误 |

---

## 9. 开发阶段

| 阶段 | 内容 | 前置条件 |
|------|------|---------|
| ① 骨架搭建 | SpringBoot 项目初始化 + Vue 项目初始化 + 依赖配置 | 无 |
| ② P0 实时接口 | Dashboard / Top 榜 / 个股详情 / 预警 6 个 API | Redis 有数据（B 启动 Streaming） |
| ③ 大屏前端 | Dashboard 页面：MarketSummary + RankPanel + AlertTicker | ② 完成 |
| ④ WebSocket | 实时推送市场概览和榜单 | ② 完成 |
| ⑤ P1 历史接口 | K线 / 量化 API（Mapper 提前写好，数据就绪即生效） | C 灌入 MySQL |
| ⑥ P1 前端 | KLineChart + QuantRadar 组件 + 个股详情页 | ⑤ 完成 |
| ⑦ 管理后台 | 用户登录、自选股、预警管理（需先建 stock_app 库） | 后续扩展 |

---

## 10. 对其他成员的依赖

| 依赖项 | 负责成员 | 本模块需要什么 | 当前状态 |
|--------|---------|---------------|---------|
| Redis 实时数据 | B（实时计算） | `stock:quote:*`、`stock:rank:*`、`stock:market:summary`、`stock:alert:latest` | 已确认 Redis 格式契约 |
| MySQL stock_ads | C（离线数仓） | DDL 已就绪（sqlinit.txt），待灌入日级/分钟级/量化评分数据 | P1 需要 |
| Docker Compose | D（自己） | 本地开发环境的 Redis + MySQL | 待配置 |

---

## 11. 环境配置（待定）

```yaml
# application.yml 核心配置项
spring:
  redis:
    host: ${REDIS_HOST:mid}
    port: 6379
  datasource:
    url: jdbc:mysql://${MYSQL_HOST:mid}:3306/stock_ads  # P0 不访问，P1 启用

platform:
  push:
    interval: 5000           # WebSocket 推送间隔 ms
```

> 本地开发可用 Docker Compose 启动 Redis + MySQL，见项目根目录 `docker-compose.yml`。

---
