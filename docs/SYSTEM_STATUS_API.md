# 系统运行状态 API — 前端对接文档

> **版本**: v1.0 | **更新**: 2026-07-07

---

## 1. 快速开始

读取全部状态：

```bash
redis-cli -a <pwd> -h <host> HGETALL stock:system:status
```

前端通过后端 API 调用，一次请求获取所有字段。

---

## 2. 字段说明

### 2.1 运行状态

| Field | 类型 | 示例 | 说明 |
|-------|------|------|------|
| `status` | string | `running` | 当前状态 |
| `mode` | string | `replay` | 运行模式 |
| `target_date` | string | `2026-07-06` | 目标日期 |
| `started_at` | string | `2026-07-07 09:22:00` | 启动时间 |
| `uptime_seconds` | int | `14400` | 已运行秒数 |
| `heartbeat_at` | string | `2026-07-07 14:07:00` | 心跳时间戳 |

**status 枚举值**：

| 值 | 含义 | 前端展示 |
|----|------|---------|
| `running` | 正常运行 | 绿色"运行中" |
| `flushing` | 正在写 MySQL | 黄色"归档中"，显示进度 |
| `error` | 发生错误 | 红色"异常"，查看 `last_error` |
| `idle` | 已停止 | 灰色"已停止" |

**mode 枚举值**：

| 值 | 含义 |
|----|------|
| `normal` | 正常消费模式 |
| `replay` | 回放/重跑模式 |

### 2.2 判断系统存活

```javascript
// 超过 30 秒未更新心跳 → 系统异常
const isAlive = (Date.now() - new Date(data.heartbeat_at).getTime()) < 30000;
```

> `heartbeat_at` 由独立后台线程每 10 秒更新一次，不受 batch 阻塞影响。

### 2.3 数据统计

| Field | 类型 | 示例 | 说明 |
|-------|------|------|------|
| `current_date` | string | `2026-07-06` | 当前处理到哪一天的行情 |
| `redis_keys` | int | `275995` | Redis 缓存 key 总数 |
| `minute_windows` | int | `34` | 当天积累的 5 分钟窗口数 |
| `ohlcv_codes` | int | `5205` | 已出现的股票代码数 |
| `batch_count` | long | `2847` | 累计处理的 batch 数 |
| `batch_ms` | int | `1120` | 最近一个 batch 耗时（毫秒） |
| `rank_up_count` | int | `3789` | 涨幅榜条目数 |
| `rank_amount_count` | int | `3789` | 成交额榜条目数 |

### 2.4 消费进度

| Field | 类型 | 示例 | 说明 |
|-------|------|------|------|
| `consumer_lag` | long | `429000` | Kafka 剩余未消费条数 |
| `consumer_pct` | int | `95` | 消费进度百分比（0-100） |

前端进度条示例：

```html
<div>消费进度: {consumer_pct}% (剩余 {consumer_lag} 条)</div>
<progress value="{consumer_pct}" max="100"></progress>
```

### 2.5 归档进度

| Field | 类型 | 示例 | 说明 |
|-------|------|------|------|
| `flush_windows_done` | int | `45` | 已完成归档的窗口数 |
| `flush_windows_total` | int | `52` | 待归档的总窗口数 |
| `flush_rows` | int | `755280` | 已写入 MySQL 的行数 |
| `flush_elapsed_sec` | int | `960` | 当前归档已用秒数 |

> 仅在 `status=flushing` 时有值。`status=running` 时全部为 0。

前端进度条示例：

```html
<!-- 仅在 status=flushing 时显示 -->
<div>归档中: {flush_windows_done}/{flush_windows_total} 窗口
     ({flush_rows} 行, 已用 {flush_elapsed_sec} 秒)</div>
<progress value="{flush_windows_done}" max="{flush_windows_total}"></progress>
```

### 2.6 事件

| Field | 类型 | 示例 | 说明 |
|-------|------|------|------|
| `last_flush_at` | string | `2026-07-07 13:33:03` | 最近归档时间 |
| `last_flush_type` | string | `day_reset` | 归档触发类型 |
| `last_error` | string | `Rank MySQL 写入失败` | 最近错误信息 |
| `last_error_at` | string | `2026-07-07 13:33:00` | 最近错误时间 |

**flush_type 枚举值**：

| 值 | 含义 |
|----|------|
| `auto` | 日清自动触发 |
| `manual` | 手动执行 FlushJob |
| `shutdown` | 关闭时触发 |

### 2.7 时间戳

| Field | 类型 | 示例 | 说明 |
|-------|------|------|------|
| `updated_at` | string | `2026-07-07 13:49:00` | 此 Hash 最后更新时间 |

---

## 3. 典型使用场景

### 事项 1 — Dashboard 状态卡片

```javascript
async function fetchStatus() {
    const res = await fetch('/api/debug/system-status');  // HGETALL
    const data = await res.json();

    return {
        isAlive: isAlive(data.heartbeat_at),
        status: data.status,           // running | flushing | error | idle
        mode: data.mode,               // normal | replay
        progress: data.consumer_pct,   // 0-100
        currentDate: data.current_date,
        stocks: data.ohlcv_codes,
        uptime: formatUptime(data.uptime_seconds),
    };
}
```

### 事项 2 — 消费进度条

```javascript
function renderProgress(data) {
    return `
        <div class="progress-bar">
            <div class="fill" style="width:${data.consumer_pct}%"></div>
            <span>${data.consumer_pct}% (剩余 ${formatNumber(data.consumer_lag)} 条)</span>
        </div>
        <div class="meta">${data.current_date} | batch #${data.batch_count}</div>
    `;
}
```

### 事项 3 — 归档进度条 (status=flushing 时显示)

```javascript
if (data.status === 'flushing') {
    return `
        <div class="flush-progress">
            <div class="fill" style="width:${data.flush_windows_done/data.flush_windows_total*100}%"></div>
            <span>${data.flush_windows_done}/${data.flush_windows_total} 窗口 
                   (${data.flush_rows} 行, ${data.flush_elapsed_sec}s)</span>
        </div>
    `;
}
```

### 事项 4 — 错误告警

```javascript
if (data.status === 'error') {
    showAlert(`系统异常: ${data.last_error} (${data.last_error_at})`);
}
```

---

## 4. 后端接口（供前端同学调用）

| API | 方法 | 说明 |
|-----|------|------|
| `GET /api/debug/redis-keys?key=stock:system:status` | 读取全部字段 |
| `GET /api/dashboard/summary` | 市场概览（已有） |
| `GET /api/stocks/rank?type=up` | 涨幅榜 Top 20（已有） |

后端新增接口（需实现）：

```java
@GetMapping("/api/system/status")
public ApiResponse<Map<String, String>> systemStatus() {
    Map<String, String> map = redisTemplate.opsForHash()
        .entries("stock:system:status");
    return ApiResponse.ok(map);
}
```

---

## 5. 更新频率

| 字段 | 更新频率 | 更新方 |
|------|---------|--------|
| `heartbeat_at` | 每 10 秒 | 后台线程 |
| 数据统计字段 | 每 batch (~5 秒) | `SystemStatusWriter` |
| `consumer_lag` / `consumer_pct` | 每 30 秒 | 监控脚本 (`full-replay.sh`) |
| flush 进度字段 | flush 期间每窗口 | `MarketFlusher` |
| 事件字段 | 事件触发时 | 对应组件 |

---

## 6. 示例响应

```json
{
  "status": "running",
  "mode": "replay",
  "target_date": "all",
  "started_at": "2026-07-07 09:22:00",
  "uptime_seconds": "14400",
  "heartbeat_at": "2026-07-07 14:15:00",
  "current_date": "2026-07-06",
  "redis_keys": "275995",
  "minute_windows": "34",
  "ohlcv_codes": "5205",
  "rank_up_count": "3789",
  "rank_amount_count": "3789",
  "batch_count": "2847",
  "batch_ms": "1120",
  "consumer_lag": "429000",
  "consumer_pct": "95",
  "flush_windows_done": "0",
  "flush_windows_total": "0",
  "flush_rows": "0",
  "flush_elapsed_sec": "0",
  "last_flush_at": "",
  "last_flush_type": "",
  "last_error": "",
  "last_error_at": "",
  "updated_at": "2026-07-07 14:15:05"
}
```
