<template>
  <div class="anomaly-panel">
    <!-- 量化 -->
    <div class="ap-sec">
      <div class="ap-sec-head quant-hd">量化</div>
      <div class="ap-sec-body">
        <div class="ap-scroll-view">
          <div class="ap-scroll-track" :style="{ animationDuration: scrollDur(quant) + 's' }">
            <template v-for="(item, idx) in quant" :key="item.code">
              <div class="ap-row">
                <span class="apr-idx" :class="rankBadge(idx)">{{ idx + 1 }}</span>
                <span class="apr-name" :class="upDown(item)">{{ item.name || item.code }}</span>
                <span class="apr-val" :class="upDown(item)">{{ fmtScore(item.score) }}</span>
              </div>
            </template>
            <template v-for="(item, idx) in quant" :key="'dq'+item.code">
              <div class="ap-row">
                <span class="apr-idx" :class="rankBadge(idx)">{{ idx + 1 }}</span>
                <span class="apr-name" :class="upDown(item)">{{ item.name || item.code }}</span>
                <span class="apr-val" :class="upDown(item)">{{ fmtScore(item.score) }}</span>
              </div>
            </template>
          </div>
        </div>
        <div v-if="!quant.length" class="ap-empty">--</div>
      </div>
    </div>

    <!-- 振幅 -->
    <div class="ap-sec">
      <div class="ap-sec-head amp-hd">振幅</div>
      <div class="ap-sec-body">
        <div class="ap-scroll-view">
          <div class="ap-scroll-track" :style="{ animationDuration: scrollDur(amplitude) + 's' }">
            <template v-for="(item, idx) in amplitude" :key="item.code">
              <div class="ap-row">
                <span class="apr-idx" :class="rankBadge(idx)">{{ idx + 1 }}</span>
                <span class="apr-name" :class="upDown(item)">{{ item.name || item.code }}</span>
                <span class="apr-val" :class="ampColor(item)">{{ (item.anomaly_val ?? 0).toFixed(1) }}%</span>
              </div>
            </template>
            <template v-for="(item, idx) in amplitude" :key="'da'+item.code">
              <div class="ap-row">
                <span class="apr-idx" :class="rankBadge(idx)">{{ idx + 1 }}</span>
                <span class="apr-name" :class="upDown(item)">{{ item.name || item.code }}</span>
                <span class="apr-val" :class="ampColor(item)">{{ (item.anomaly_val ?? 0).toFixed(1) }}%</span>
              </div>
            </template>
          </div>
        </div>
        <div v-if="!amplitude.length" class="ap-empty">--</div>
      </div>
    </div>

    <!-- 放量 -->
    <div class="ap-sec">
      <div class="ap-sec-head spike-hd">放量</div>
      <div class="ap-sec-body">
        <div class="ap-scroll-view">
          <div class="ap-scroll-track" :style="{ animationDuration: scrollDur(spike) + 's' }">
            <template v-for="(item, idx) in spike" :key="item.code">
              <div class="ap-row">
                <span class="apr-idx" :class="rankBadge(idx)">{{ idx + 1 }}</span>
                <span class="apr-name" :class="upDown(item)">{{ item.name || item.code }}</span>
                <span class="apr-val" :class="spikeColor(item)">{{ (item.anomaly_val ?? 0).toFixed(1) }}x</span>
              </div>
            </template>
            <template v-for="(item, idx) in spike" :key="'ds'+item.code">
              <div class="ap-row">
                <span class="apr-idx" :class="rankBadge(idx)">{{ idx + 1 }}</span>
                <span class="apr-name" :class="upDown(item)">{{ item.name || item.code }}</span>
                <span class="apr-val" :class="spikeColor(item)">{{ (item.anomaly_val ?? 0).toFixed(1) }}x</span>
              </div>
            </template>
          </div>
        </div>
        <div v-if="!spike.length" class="ap-empty">--</div>
      </div>
    </div>

    <!-- 涨跌停 -->
    <div class="ap-sec">
      <div class="ap-sec-head limit-hd">涨跌停</div>
      <div class="ap-sec-body">
        <div class="ap-scroll-view">
          <div class="ap-scroll-track" :style="{ animationDuration: scrollDur(limits) + 's' }">
            <template v-for="(item, idx) in limits" :key="item.code">
              <div class="ap-row">
                <span class="apr-idx" :class="rankBadge(idx)">{{ idx + 1 }}</span>
                <span class="apr-name" :class="upDown(item)">{{ item.name || item.code }}</span>
                <span class="apr-val" :class="limitColor(item)">{{ fmtPct(item.anomaly_val) }}</span>
              </div>
            </template>
            <template v-for="(item, idx) in limits" :key="'dl'+item.code">
              <div class="ap-row">
                <span class="apr-idx" :class="rankBadge(idx)">{{ idx + 1 }}</span>
                <span class="apr-name" :class="upDown(item)">{{ item.name || item.code }}</span>
                <span class="apr-val" :class="limitColor(item)">{{ fmtPct(item.anomaly_val) }}</span>
              </div>
            </template>
          </div>
        </div>
        <div v-if="!limits.length" class="ap-empty">--</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { stockApi } from '@/api/request'

const quant = ref([])
const amplitude = ref([])
const spike = ref([])
const limits = ref([])
let timer = null

function scrollDur(list) { return Math.max(list.length * 2, 10) }

async function fetchAll() {
  try {
    const [q, a, s, l] = await Promise.all([
      stockApi.getTopQuant(20),
      stockApi.getAnomaly('amplitude', 20),
      stockApi.getAnomaly('volume_spike', 20),
      stockApi.getAnomaly('limit_up_down', 20)
    ])
    quant.value = q || []
    amplitude.value = a || []
    spike.value = s || []
    limits.value = l || []
  } catch { /* silent */ }
}

function upDown(item) {
  const pct = item.change_pct ?? item.anomaly_val ?? 0
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

function rankBadge(idx) {
  if (idx === 0) return 'r1'
  if (idx === 1) return 'r2'
  if (idx === 2) return 'r3'
  return ''
}

function fmtScore(v) {
  if (v == null) return '--'
  return Number(v).toFixed(1)
}

onMounted(() => {
  fetchAll()
  timer = setInterval(fetchAll, 60000)
})

onUnmounted(() => clearInterval(timer))
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
.quant-hd { color: var(--accent) }
.amp-hd { color: var(--stock-warn) }
.spike-hd { color: #e17341 }
.limit-hd { color: var(--stock-warn) }

.ap-sec-body {
  flex: 1;
  overflow: hidden;
  min-height: 0;
}

/* ---- Scroll ---- */
.ap-scroll-view {
  height: 100%;
  overflow: hidden;
  mask-image: linear-gradient(to bottom, black 85%, transparent 100%);
  -webkit-mask-image: linear-gradient(to bottom, black 85%, transparent 100%);
}
.ap-scroll-track { animation: ap-scroll linear infinite }
.ap-scroll-track:hover { animation-play-state: paused }

@keyframes ap-scroll {
  0%   { transform: translateY(0) }
  100% { transform: translateY(-50%) }
}

.ap-row {
  display: flex; align-items: center; gap: 4px;
  padding: 2px 8px; height: 20px;
  border-bottom: 1px solid rgba(255,255,255,0.02);
  cursor: pointer;
  transition: background .1s;
  flex-shrink: 0;
}
.ap-row:nth-child(odd)  { background: transparent }
.ap-row:nth-child(even) { background: rgba(255,255,255,0.06) }
.ap-row:hover { background: var(--bg-hover) }

.apr-idx {
  width: 16px; height: 16px; display: flex; align-items: center; justify-content: center;
  font-size: 10px; font-weight: 600; border-radius: 0;
  color: var(--text-muted); background: var(--bg-elevated); flex-shrink: 0;
}
.apr-idx.r1 { background: var(--stock-up); color: #fff; box-shadow: 0 0 5px rgba(255,73,91,0.4); font-size: 11px }
.apr-idx.r2 { background: #e17341; color: #fff; box-shadow: 0 0 4px rgba(225,115,65,0.4); font-size: 11px }
.apr-idx.r3 { background: var(--stock-warn); color: #1A1A1A; box-shadow: 0 0 4px rgba(232,186,64,0.4); font-size: 11px }

.apr-name {
  flex: 1;
  font-size: 11px; font-weight: 500;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  min-width: 0;
}
.apr-name.up { color: var(--stock-up) }
.apr-name.down { color: var(--stock-down) }
.apr-name { color: var(--text-secondary) }

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
