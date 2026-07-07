# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

Stock big-data platform: Sina real-time quotes → Kafka → Spark Streaming → Redis + MySQL + HDFS → SpringBoot API → Vue 3 dashboard.

- **Data pipeline**: `core/` (Spark Streaming consumer, Lua scripts, HDFS writers)
- **API backend**: `platform-web/backend/stock-api/` (SpringBoot 3.2, JDK 17)
- **Frontend**: `platform-web/frontend/stock-web/` (Vue 3 + Element Plus + ECharts 5)
- **Data collector**: `data_gen/` (Sina Level-2 quote scraper → Redis `stock:quote:{code}`)
- **SQL & config**: `cluster/sql/`, `cluster/config/`

## Running locally

```bash
# Backend (Redis required at 192.168.137.210:6379, password in application-dev.yml)
cd platform-web/backend/stock-api
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# Backend with MySQL (adds dim_stock name lookup + history K-line)
mvn spring-boot:run -Dspring-boot.run.profiles=dev,mysql

# Frontend (Vite dev server on :5173, proxies /api → :8088)
cd platform-web/frontend/stock-web
npm run dev
```

## Redis data contract

**The single source of truth is `docs/REDIS_SCHEMA.md` v2.2.** The `platform-web/README.md` has merge conflicts and outdated schema — do NOT rely on it for Redis key formats.

Key inventory (all under `stock:` namespace):

| Key | Type | Contains |
|-----|------|---------|
| `stock:quote:ohlcv:{code}` | String JSON | name, price, open/high/low, volume, amount, change, change_pct, trade_date/time |
| `stock:quote:ohlcv:codes` | Set | All active stock codes — use this instead of `KEYS *` or SCAN |
| `stock:quote:{code}` | String JSON | Level-2 5-level depth (bid/ask, b1-b5, s1-s5, status) |
| `stock:market:summary` | Hash | stat_time, total_stocks, up/down/flat_count, avg_change_pct, total_volume/amount |
| `stock:rank:up` | ZSet | score=change_pct. 跌幅榜 = ZRANGE from same key (no separate `stock:rank:down`) |
| `stock:rank:amount` | ZSet | score=amount (成交额 元) |
| `stock:rank:quant` | ZSet | score=quant_score. ❌ 待开发 |
| `stock:minute:{code}:{minuteTime}` | Hash | open/high/low/close/last_vol (raw, cumulative). Minute data for K-line |
| `stock:minute:windows` | Set | All active minute windows ("yyyy-MM-dd HH:mm:00"). Multi-day, cleaned by daily flush |
| `stock:alert:latest` | List | ❌ 待开发 |

**Important**: `code` is NOT in JSON bodies — extract from the Redis key. `stock:quote:ohlcv:{code}` has `name` built-in; `stock:quote:{code}` does NOT.

## Backend architecture

### Core data layer

- **`RedisService`** — The central data access class. All Redis reads go through here. Uses `StringRedisTemplate`. Methods: `getAllStocks()` (SMEMBERS + MGET), `getStockLatest()` (merges OHLCV + Level-2), `getMarketSummary()`, `getTopUp/Down/Amount/Quant()`, `getStockMinutes()`, `getSparkBatch()` (in-memory cached), `getTreemap()`.
- **`HistoryService`** — MySQL historical K-line queries via `JdbcTemplate`. Only created with `@Profile("mysql")`.
- **`StockNameService`** — Cached dim_stock name lookup. Only with `@Profile("mysql")`. Refresh every 5 minutes.
- **`MarketPushScheduler`** — `@Scheduled` WebSocket push to `/topic/market`, `/topic/rank/*`.

### Controllers and their endpoints

| Controller | Endpoints |
|-----------|----------|
| `DashboardController` | `/api/dashboard/summary`, `/api/dashboard/treemap` |
| `StockController` | `/api/stocks` (list + search), `/api/stocks/{code}`, `/api/stocks/{code}/minutes`, `/api/stocks/{code}/history`, `/api/stocks/top-*`, `/api/stocks/spark-batch` (POST) |
| `AlertController` | `/api/alerts/latest` |
| `DebugController` | `/api/debug/redis-keys` (Redis diagnostic) |

### Spring profiles

- **Default** (no profile active): Datasource auto-config excluded. Pure Redis mode.
- **`dev`**: Redis config from `application-dev.yml` (host 192.168.137.210).
- **`mysql`**: Activates `DataSourceAutoConfiguration`, creates `HistoryService` + `StockNameService`. Run as `dev,mysql`.

### DTOs and JSON mapping

Jackson configured with `PropertyNamingStrategies.SNAKE_CASE` globally. Most JSON fields map automatically. Explicit `@JsonProperty` is used for fields where the snake_case name differs from the default mapping.

- `StockLatestDTO` — Handles BOTH OHLCV fields (name, price, open/high/low, change_pct, volume, amount) AND Level-2 fields (bid/ask, b1-b5, s1-s5, status). `@JsonProperty` on `trade_date`, `trade_time`, `change`, `change_pct`, `pre_close`.
- `MarketSummaryDTO` — `totalAmount` is `Double` (not Long) to match data source precision.
- `RankItemDTO` — Has `score` from ZSet AND `changePct` from OHLCV JSON.

### Pattern: reading stock list (no MySQL)

```
SMEMBERS stock:quote:ohlcv:codes → codes list
  → batch MGET stock:quote:ohlcv:{code} → deserialize StockLatestDTO
  → name comes from OHLCV JSON directly (no MySQL required)
```

### Pattern: stock detail (with Level-2)

```
GET stock:quote:ohlcv:{code} → primary data (name, OHLCV)
GET stock:quote:{code} → merge Level-2 fields (bid/ask, depth, status)
```

### Pattern: spark data caching

Spark batch (minute close arrays) uses JVM in-memory `volatile Map` with 30s TTL. First request triggers Pipeline HGET across all codes×windows. Subsequent requests hit cache. Never writes to Redis.

## Frontend architecture

### Router

- `/` → redirects to `/dashboard`
- `/dashboard` → main dashboard (market summary + rank panels + treemap or stock detail)
- `/stocks` → split-panel stock list with inline detail
- `/stock/:code` → standalone stock detail page

### Key components

| Component | Role |
|-----------|------|
| `Dashboard.vue` | Main dashboard. Left 30%: market breadth + tabbed rank panels. Right 70%: `StockDetailPanel` (when stock selected) or `MarketTreemap` (default). |
| `RankPanel.vue` | Ranked stock list. Two-column layout (1-10 left, 11-20 right). Sparkline mini charts, FLIP transition animation. |
| `StockDetailPanel.vue` | Inline stock detail. Hero (name/code/price/bid-ask) + OHLCV grid + KLineChart + depth ladder. Fetches Level-2 on select. |
| `KLineChart.vue` | ECharts candlestick + volume bar. Minute K-line from Redis. |
| `MarketTreemap.vue` | ECharts treemap. Top 100 by amount, color = change_pct (red/green), size = amount. |
| `AlertTicker.vue` | Scrolling alert bar at page bottom. |

### State management (Pinia)

`stores/stock.js` — `marketSummary`, `topUp`, `topDown`, `topAmount`, `topQuant`, `alerts`, `wsConnected`. Normalize function handles both `snake_case` and `camelCase` from API responses. HTTP fetch on mount, WebSocket updates live.

### Color utility

`utils/stockColor.js` — `getStockDirection(stock)` checks `change_pct ?? changePct` then `bid/ask` for limit detection, `status` for suspended/delisted. Returns CSS class suffix. CSS variables: `--stock-up` (red #e15241), `--stock-down` (green #3cb371), `--stock-warn` (amber).

### Unit conventions

- Volume: raw value is 股 (shares). Display: ÷100 → 手. Auto-scale to 万手.
- Amount: raw value is 元 (yuan). Display: auto-scale to 万/亿.
- `prevClose`: computed as `price - change` when `pre_close` not in JSON.

## Common pitfalls

- **Do NOT write to Redis** from platform-web. All Redis writes are done by Spark Streaming / data_gen.
- **Use `SMEMBERS stock:quote:ohlcv:codes`** to enumerate stocks, never `KEYS *` or `SCAN`.
- **JSON uses snake_case**, frontend accesses it as snake_case (e.g., `row.trade_date`, not `row.tradeDate`). The normalize function in the store handles this for market summary.
- **`stock:rank:down` does not exist**. 跌幅榜 = `ZRANGE stock:rank:up 0 19` (ascending, most negative first).
- **MyBatis was removed** — use `JdbcTemplate` for MySQL queries.
- **The `dev` profile is split** into `application-dev.yml` for Redis config. Main `application.yml` excludes datasource auto-config by default.

## Branch

Current feature branch: `feature/fang-kline-v2`. Main branch: `main`. Always branch from main for new features.
