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
| `DashboardController` | `/api/dashboard/summary`, `/api/dashboard/treemap`, `/api/dashboard/summary-history` |
| `StockController` | `/api/stocks` (list + search), `/api/stocks/{code}`, `/api/stocks/{code}/minutes`, `/api/stocks/{code}/history`, `/api/stocks/top-*`, `/api/stocks/spark-batch` (POST), `/api/stocks/anomaly` |
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

### Pattern: MySQL fallback (Redis archiving)

When Redis is flushed/archived, `RedisService.getStockLatest()` falls back to `HistoryService.getDailyHistory(code, 1)` to load latest daily row from MySQL `dws_stock_day`. Only active with `@Profile("mysql")`.

### Pattern: Treemap API

`GET /api/dashboard/treemap` returns `{up: [...], down: [...]}` — top 20 gainers and losers by amount, separately. Uses SMEMBERS + MGET across all OHLCV codes.

### Pattern: Anomaly detection (MySQL)

`GET /api/stocks/anomaly?type=amplitude|volume_spike|limit_up_down&limit=N` queries `dws_stock_day` with `dim_stock` join. Amplitude = `(high-low)/open`, volume spike = `today_vol / yesterday_vol`, limit up/down = `change_pct >= ±9.9` (主板) or `>= ±19.9` (科创板 688xxx). Only active with `@Profile("mysql")`.

### Pattern: Trend chart (MySQL + WebSocket hybrid)

Trend chart in Dashboard uses MySQL `ads_market_summary` history for initial line, then WebSocket `onMarket` pushes new points only when `avg_change_pct` value changes (de-duped). Dual yAxis: left for up/down ratio stacked area, right for avg change_pct line. Max 120 points sliding window.

## Frontend architecture

### Router

- `/` → redirects to `/dashboard`
- `/dashboard` → 2-row layout: top=大盘+Treemap(绿跌\|红涨横排), bottom=涨跌双列+异动(振幅\|放量\|涨跌停横排). Left column 45%, right column flex. Read-only. Trend chart with up/down area + avg line.
- `/stocks` → split-panel stock list with inline detail (click to view Level-2 depth)
- `/stock/:code` → standalone stock detail page
- `/admin` → copy of dashboard, reserved for future customization

### Key components

| Component | Role |
|-----------|------|
| `Dashboard.vue` | 2-row: top=大盘(左)+Treemap绿\|红横排(右), bottom=涨跌双列(左)+异动振幅\|放量\|涨跌停横排(右). col-left 45%, col-right flex. Trend chart dual yAxis (up/down area + avg line). Static display. |
| `RankPanel.vue` | Ranked stock list: two-column (1-10 left, 11-20 right). Row=[badge\|name(natural width)\|sparkline(flex:1)\|bid/ask(right)]. Sparkline: `preserveAspectRatio="xMinYMid meet"`, pad=0, xStep=2. FLIP/TransitionGroup animation. bid/ask → `--` if Level-2 missing. |
| `StockDetailPanel.vue` | Stock detail: hero section (name/code/price/bid-ask + 8-field OHLCV inline grid) + `KLineChart` + depth ladder (25% right). Fetches Redis OHLCV + Level-2 merge. Name color follows change_pct. Time uses accent color (11px). |
| `KLineChart.vue` | ECharts candlestick + volume bar. `boundaryGap: false` + `barMaxWidth: 8` prevents single-candle stretch. Grid: K-line 70% height, volume 26%. Minute K-line from Redis. |
| `MarketTreemap.vue` | Dual ECharts treemap: Top 20 gainers (red) + Top 20 losers (green) by amount, horizontal side-by-side (绿左红右). Slice layout (`squareRatio: 1.5`). Color: `rgba(255,73,91,opacity)` / `rgba(63,185,80,opacity)`, opacity 0.5→1.0 linear by |change_pct| capped at ±10%. |
| `AnomalyPanel.vue` | Three-column horizontal anomaly monitor: 振幅(amplitude) \| 放量异动(volume spike) \| 涨跌停(limit up/down). 60s auto-refresh. Color-coded values (hot/warn). Static display (overflow: hidden). |
| `AlertTicker.vue` | Scrolling alert bar at page bottom. |

### State management (Pinia)

`stores/stock.js` — `marketSummary`, `topUp`, `topDown`, `topAmount`, `topQuant`, `alerts`, `wsConnected`. Normalize function handles both `snake_case` and `camelCase` from API responses. HTTP fetch on mount, WebSocket updates live.

### Color utility

`utils/stockColor.js` — `getStockDirection(stock)` checks `change_pct ?? changePct` then `bid/ask` for limit detection, `status` for suspended/delisted. Returns CSS class suffix. CSS variables: `--stock-up` (red #FF495B), `--stock-down` (green #3FB950), `--stock-warn` (amber #D29922). Ensure all components use these exact values for consistency.

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
- **Dashboard is read-only** — rank items have no `@select` handler. Search still navigates to `/stock/:code`.
- **Treemap color must match CSS variables**: `#FF495B` (up) and `#3FB950` (down). Hardcoded values break visual consistency.
- **Sparkline uses fixed 2px x-step** — data grows rightward, not proportionally scaled. SVG viewBox 90×20.
- **Level-2 data (`stock:quote:{code}`) may be nil** — bid/ask will show `--`. DTO uses `@JsonInclude(NON_NULL)`, frontend must check `== null` not just falsy.
- **Anomaly panel is static** — `overflow: hidden`, no scrolling. Items that don't fit are hidden.
- **Trend chart dedupes WebSocket points** — only pushes when `avg_change_pct` actually changes, preventing flat lines.
- **A股涨跌停阈值** — 主板 ±9.9%, 科创板(688xxx) ±19.9%. Query uses `code LIKE 'sh688%'` to differentiate.

## Branch

Current feature branch: `feature/fang-kline-v2`. Main branch: `main`. Always branch from main for new features.
