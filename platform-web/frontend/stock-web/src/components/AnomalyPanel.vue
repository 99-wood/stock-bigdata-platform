<template>
  <div class="anomaly-panel">
    <!-- 振幅 -->
    <div class="ap-sec" ref="secRef">
      <div class="ap-sec-head amp-hd">振幅</div>
      <div class="ap-sec-body">
        <div v-for="(item, idx) in showedAmp" :key="item.code" class="ap-row">
          <span class="apr-idx">{{ idx + 1 }}</span>
          <span class="apr-name" :class="upDown(item)">{{ item.name || item.code }}</span>
          <span class="apr-val" :class="ampColor(item)">{{ (item.anomaly_val ?? 0).toFixed(1) }}%</span>
        </div>
        <div v-if="!amplitude.length" class="ap-empty">--</div>
      </div>
    </div>

    <!-- 放量 -->
    <div class="ap-sec">
      <div class="ap-sec-head spike-hd">放量</div>
      <div class="ap-sec-body">
        <div v-for="(item, idx) in showedSpike" :key="item.code" class="ap-row">
          <span class="apr-idx">{{ idx + 1 }}</span>
          <span class="apr-name" :class="upDown(item)">{{ item.name || item.code }}</span>
          <span class="apr-val" :class="spikeColor(item)">{{ (item.anomaly_val ?? 0).toFixed(1) }}x</span>
        </div>
        <div v-if="!spike.length" class="ap-empty">--</div>
      </div>
    </div>

    <!-- 涨跌停 -->
    <div class="ap-sec">
      <div class="ap-sec-head limit-hd">涨跌停</div>
      <div class="ap-sec-body">
        <div v-for="(item, idx) in showedLimits" :key="item.code" class="ap-row">
          <span class="apr-idx">{{ idx + 1 }}</span>
          <span class="apr-name" :class="upDown(item)">{{ item.name || item.code }}</span>
          <span class="apr-val" :class="limitColor(item)">{{ fmtPct(item.anomaly_val) }}</span>
        </div>
        <div v-if="!limits.length" class="ap-empty">--</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { stockApi } from '@/api/request'

const amplitude = ref([])
const spike = ref([])
const limits = ref([])
const secRef = ref(null)
const secHeight = ref(0)
let timer = null
let observer = null

const ROW_H = 20  // ap-row 固定高度
const HEAD_H = 22 // ap-sec-head 固定高度

// 用外层 ap-sec 高度（flex 决定，不受内容影响）计算
const fitCount = computed(() => Math.max(1, Math.floor((secHeight.value - HEAD_H) / ROW_H)))

const showedAmp = computed(() => amplitude.value.slice(0, fitCount.value))
const showedSpike = computed(() => spike.value.slice(0, fitCount.value))
const showedLimits = computed(() => limits.value.slice(0, fitCount.value))

async function fetchAll() {
  try {
    const [a, s, l] = await Promise.all([
      stockApi.getAnomaly('amplitude', 20),
      stockApi.getAnomaly('volume_spike', 20),
      stockApi.getAnomaly('limit_up_down', 20)
    ])
    amplitude.value = a || []
    spike.value = s || []
    limits.value = l || []
  } catch { /* silent */ }
}

function upDown(item) {
  const pct = item.change_pct ?? 0
  if (pct > 0) return 'up'
  if (pct < 0) return 'down'
  return ''
}

function ampColor(item) {
  const v = item.anomaly_val ?? 0
  if (v > 8) return 'hot'
  if (v > 5) return 'warn'
  return ''
}

function spikeColor(item) {
  const v = item.anomaly_val ?? 0
  if (v > 3) return 'hot'
  if (v > 2) return 'warn'
  return ''
}

function limitColor(item) {
  return (item.anomaly_val ?? 0) >= 0 ? 'up' : 'down'
}

function fmtPct(v) {
  if (v == null) return '--'
  return (v >= 0 ? '+' : '') + Number(v).toFixed(1) + '%'
}

onMounted(() => {
  fetchAll()
  timer = setInterval(fetchAll, 60000)
  if (secRef.value) {
    observer = new ResizeObserver(([entry]) => {
      secHeight.value = entry.contentRect.height
    })
    observer.observe(secRef.value)
  }
})

onUnmounted(() => {
  clearInterval(timer)
  observer?.disconnect()
})
</script>

<style scoped>
.anomaly-panel {
  height: 100%;
  display: flex; flex-direction: row;
  overflow: hidden;
}

.ap-sec {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  overflow: hidden;
}
.ap-sec + .ap-sec {
  border-left: 1px solid var(--border-subtle);
}

.ap-sec-head {
  font-size: 10px; font-weight: 600;
  padding: 3px 8px; height: 22px;
  color: var(--text-muted);
  background: var(--accent-bg);
  letter-spacing: .04em;
  flex-shrink: 0;
}
.amp-hd { color: var(--stock-warn) }
.spike-hd { color: #e17341 }
.limit-hd { color: var(--stock-warn) }

.ap-sec-body {
  flex: 1;
  overflow: hidden;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.ap-row {
  display: flex; align-items: center; gap: 4px;
  padding: 2px 8px; height: 20px;
  border-bottom: 1px solid rgba(255,255,255,0.02);
  cursor: pointer;
  transition: background .1s;
  flex-shrink: 0;
}
.ap-row:hover { background: var(--bg-hover) }

.apr-idx {
  width: 14px; text-align: center;
  font-size: 10px; font-weight: 600;
  color: var(--text-muted); flex-shrink: 0;
}
.ap-row:nth-child(1) .apr-idx { color: var(--stock-up) }
.ap-row:nth-child(2) .apr-idx { color: #e17341 }
.ap-row:nth-child(3) .apr-idx { color: var(--stock-warn) }

.apr-name {
  flex: 1;
  font-size: 11px; font-weight: 500;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  min-width: 0;
}
.apr-name.up { color: var(--stock-up) }
.apr-name.down { color: var(--stock-down) }

.apr-val {
  font-size: 10px; font-weight: 600;
  font-family: var(--font-mono);
  color: var(--text-primary);
  flex-shrink: 0;
}
.apr-val.up { color: var(--stock-up) }
.apr-val.down { color: var(--stock-down) }
.apr-val.hot { color: var(--stock-up) }
.apr-val.warn { color: var(--stock-warn) }

.ap-empty {
  padding: 12px 8px; text-align: center;
  color: var(--text-muted); font-size: 11px;
}
</style>
