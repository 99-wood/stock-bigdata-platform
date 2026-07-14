<template>
  <div class="sys-panel" v-if="status">
    <!-- Header: status dot + label -->
    <div class="sp-head">
      <span class="sb-dot" :class="statusClass"></span>
      <span class="sp-title">{{ statusText }}</span>
    </div>

    <div class="sp-body">
      <!-- Mode -->
      <div class="sp-row" v-if="status.mode">
        <span class="sp-k">模式</span>
        <span class="sp-v">{{ status.mode }}</span>
      </div>

      <!-- Data date -->
      <div class="sp-row" v-if="status.currentDate">
        <span class="sp-k">数据</span>
        <span class="sp-v">{{ status.currentDate }}</span>
      </div>

      <!-- Uptime -->
      <div class="sp-row" v-if="uptime">
        <span class="sp-k">运行</span>
        <span class="sp-v">{{ uptime }}</span>
      </div>

      <!-- Batch -->
      <div class="sp-row" v-if="status.batchCount != null">
        <span class="sp-k">批次</span>
        <span class="sp-v">#{{ fmt(status.batchCount) }}</span>
      </div>
      <div class="sp-row sp-sub" v-if="status.batchMs != null">
        <span class="sp-k"></span>
        <span class="sp-v">{{ batchSec }}s/批</span>
      </div>

      <!-- Stocks -->
      <div class="sp-row" v-if="status.ohlcvCodes != null">
        <span class="sp-k">股票</span>
        <span class="sp-v">{{ fmt(status.ohlcvCodes) }}</span>
      </div>

      <!-- Redis keys -->
      <div class="sp-row" v-if="status.redisKeys != null">
        <span class="sp-k">Redis</span>
        <span class="sp-v">{{ fmtKeys(status.redisKeys) }}</span>
      </div>

      <!-- Minute windows -->
      <div class="sp-row" v-if="status.minuteWindows != null">
        <span class="sp-k">窗口</span>
        <span class="sp-v">{{ status.minuteWindows }}</span>
      </div>

      <!-- Rank sizes -->
      <div class="sp-row" v-if="status.rankUpCount != null">
        <span class="sp-k">涨幅</span>
        <span class="sp-v">{{ fmt(status.rankUpCount) }}</span>
      </div>
      <div class="sp-row sp-sub" v-if="status.rankAmountCount != null">
        <span class="sp-k"></span>
        <span class="sp-v">成交 {{ fmt(status.rankAmountCount) }}</span>
      </div>

      <!-- Consumer progress -->
      <div class="sp-row" v-if="status.consumerPct != null && status.consumerPct > 0">
        <span class="sp-k">进度</span>
        <span class="sp-v">{{ status.consumerPct }}%</span>
      </div>

      <!-- Consumer lag -->
      <div class="sp-row" v-if="status.consumerLag != null && status.consumerLag > 0" :class="{ warn: status.consumerLag > 10000 }">
        <span class="sp-k">Lag</span>
        <span class="sp-v lag">{{ fmt(status.consumerLag) }}</span>
      </div>
    </div>

    <!-- Error -->
    <div class="sp-err" v-if="status.lastError" :title="status.lastError + ' @ ' + status.lastErrorAt">
      ⚠ {{ status.lastError.length > 20 ? status.lastError.substring(0, 20) + '…' : status.lastError }}
    </div>

    <!-- Update time -->
    <div class="sp-foot" v-if="status.updatedAt && status.updatedAt.length >= 19">
      {{ status.updatedAt.substring(11, 19) }}
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { dashboardApi } from '@/api/request'

const raw = ref(null)

// Normalize snake_case → camelCase for template use
const status = computed(() => {
  const s = raw.value
  if (!s) return null
  return {
    status: s.status,
    mode: s.mode,
    currentDate: s.current_date,
    uptimeSeconds: s.uptime_seconds,
    batchCount: s.batch_count,
    batchMs: s.batch_ms,
    ohlcvCodes: s.ohlcv_codes,
    redisKeys: s.redis_keys,
    minuteWindows: s.minute_windows,
    rankUpCount: s.rank_up_count,
    rankAmountCount: s.rank_amount_count,
    consumerLag: s.consumer_lag,
    consumerPct: s.consumer_pct,
    lastError: s.last_error,
    lastErrorAt: s.last_error_at,
    updatedAt: s.updated_at
  }
})

async function fetchStatus() {
  try {
    raw.value = await dashboardApi.getSystemStatus()
  } catch { /* consumer may be offline */ }
}

const statusClass = computed(() => {
  const s = status.value?.status
  if (s === 'running') return 'on'     // 绿 — 正常
  if (s === 'flushing') return 'warn'  // 琥珀 — 归档中
  if (s === 'starting') return 'warn'  // 琥珀 — 启动中
  if (s === 'error') return 'err'      // 红 — 故障
  return 'off'                          // 灰 — idle / 离线
})

const statusText = computed(() => {
  const s = status.value?.status
  if (s === 'running') return 'RUNNING'
  if (s === 'flushing') return 'FLUSH'
  if (s === 'starting') return 'START'
  if (s === 'error') return 'ERROR'
  if (s === 'idle') return 'IDLE'
  return s?.toUpperCase() || 'OFFLINE'
})

const uptime = computed(() => {
  const sec = status.value?.uptimeSeconds
  if (sec == null) return null
  if (sec < 60) return sec + 's'
  if (sec < 3600) return Math.floor(sec / 60) + 'm'
  const h = Math.floor(sec / 3600)
  const m = Math.floor((sec % 3600) / 60)
  return h + 'h' + (m > 0 ? ' ' + m + 'm' : '')
})

const batchSec = computed(() => {
  const ms = status.value?.batchMs
  if (ms == null) return '--'
  return (ms / 1000).toFixed(1)
})

function fmt(v) {
  if (v == null) return '--'
  if (v >= 10000) return (v / 10000).toFixed(1) + 'w'
  if (v >= 1000) return (v / 1000).toFixed(1) + 'k'
  return String(v)
}

function fmtKeys(v) {
  if (v >= 100000) return (v / 10000).toFixed(1) + 'w'
  if (v >= 10000) return (v / 10000).toFixed(2) + 'w'
  return String(v)
}

let timer = null

onMounted(() => {
  fetchStatus()
  timer = setInterval(fetchStatus, 30000)
})

onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.sys-panel {
  height: 100%;
  display: flex; flex-direction: column;
  font-size: 10px; font-family: var(--font-mono);
  background: var(--bg-card);
  color: var(--text-secondary);
  overflow: hidden;
}

/* ---- Header ---- */
.sp-head {
  display: flex; align-items: center; gap: 5px;
  padding: 6px 8px;
  background: var(--accent-bg);
  border-bottom: 1px solid var(--border-subtle);
  flex-shrink: 0;
}
.sp-title { font-weight: 600; letter-spacing: .04em; font-size: 10px; color: var(--text-primary) }

/* Status dot */
.sb-dot {
  width: 6px; height: 6px; border-radius: 50%; flex-shrink: 0;
  background: var(--text-muted);
}
.sb-dot.on  { background: #3FB950; box-shadow: 0 0 5px rgba(63,185,80,0.5) }   /* 绿 — 正常运行 */
.sb-dot.warn { background: #E8BA40; box-shadow: 0 0 5px rgba(232,186,64,0.5) }  /* 琥珀 — 启动/归档中 */
.sb-dot.err  { background: #F85149; box-shadow: 0 0 5px rgba(248,81,73,0.5) }   /* 红 — 故障 */
.sb-dot.off  { background: #636D7E }                                              /* 灰 — 离线/空闲 */

/* ---- Body rows ---- */
.sp-body {
  flex: 1;
  padding: 4px 8px;
  display: flex; flex-direction: column;
  gap: 3px;
  overflow: hidden;
}
.sp-row {
  display: flex; align-items: baseline; gap: 4px;
  line-height: 1.4;
}
.sp-k { color: var(--text-muted); flex-shrink: 0; min-width: 24px; font-size: 9px }
.sp-v { color: var(--text-secondary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap }
.sp-sub { padding-left: 28px }
.sp-row.warn .sp-v { color: var(--stock-warn) }
.sp-v.lag { color: var(--stock-up) }
.sp-row.warn .sp-v.lag { color: var(--stock-warn) }

/* ---- Error ---- */
.sp-err {
  padding: 3px 8px;
  color: #F85149;
  font-size: 9px;
  border-top: 1px solid rgba(248,81,73,0.2);
  background: rgba(248,81,73,0.06);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  cursor: help;
  flex-shrink: 0;
}

/* ---- Footer ---- */
.sp-foot {
  padding: 3px 8px;
  font-size: 9px; color: var(--text-muted);
  border-top: 1px solid var(--border-subtle);
  text-align: right;
  flex-shrink: 0;
}
</style>
