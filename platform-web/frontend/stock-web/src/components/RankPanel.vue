<template>
  <div class="panel" :class="{ plain: plain }">
    <div class="panel-head" v-if="!plain">
      <h3 class="panel-title">{{ title }}</h3>
      <span class="panel-count">{{ data.length }}</span>
    </div>
    <div class="panel-body">
      <div class="scroll-viewport" v-if="data.length">
        <div class="scroll-track" :style="{ animationDuration: scrollDuration + 's' }">
          <template v-for="(item, idx) in data" :key="item.code">
            <div class="row group" @click="$emit('select', item.code)">
              <span class="rank-badge" :class="rankBadge(idx)">{{ idx + 1 }}</span>
              <div class="stock-main">
                <span class="stock-name" v-if="item.name" :class="codeColor(item)">{{ item.name }}</span>
                <span class="stock-code" :class="codeColor(item)">{{ item.code }}</span>
              </div>
              <span class="spark-wrap">
                <svg v-if="sparkData[item.code]" class="spark-svg" :viewBox="sparkViewBox()" preserveAspectRatio="none">
                  <path :d="sparkArea(sparkData[item.code])" :fill="sparkColor(item)" opacity="0.12" />
                  <path :d="sparkPath(sparkData[item.code])" fill="none" :stroke="sparkColor(item)" stroke-width="1.5" />
                </svg>
              </span>
              <div class="stock-sides">
                <span class="side-price" :class="codeColor(item)">{{ fmtPrice(item.price) }}</span>
                <span class="side-pct" :class="codeColor(item)">{{ fmtPct(item) }}</span>
              </div>
            </div>
          </template>
          <!-- 复制一份实现无缝循环 -->
          <template v-for="(item, idx) in data" :key="'d'+item.code">
            <div class="row group" @click="$emit('select', item.code)">
              <span class="rank-badge" :class="rankBadge(idx)">{{ idx + 1 }}</span>
              <div class="stock-main">
                <span class="stock-name" v-if="item.name" :class="codeColor(item)">{{ item.name }}</span>
                <span class="stock-code" :class="codeColor(item)">{{ item.code }}</span>
              </div>
              <span class="spark-wrap">
                <svg v-if="sparkData[item.code]" class="spark-svg" :viewBox="sparkViewBox()" preserveAspectRatio="none">
                  <path :d="sparkArea(sparkData[item.code])" :fill="sparkColor(item)" opacity="0.12" />
                  <path :d="sparkPath(sparkData[item.code])" fill="none" :stroke="sparkColor(item)" stroke-width="1.5" />
                </svg>
              </span>
              <div class="stock-sides">
                <span class="side-price" :class="codeColor(item)">{{ fmtPrice(item.price) }}</span>
                <span class="side-pct" :class="codeColor(item)">{{ fmtPct(item) }}</span>
              </div>
            </div>
          </template>
        </div>
      </div>
      <div v-if="!data.length" class="empty">暂无数据</div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, computed } from 'vue'
import { getStockColorClass } from '@/utils/stockColor'
import { stockApi } from '@/api/request'

const props = defineProps({
  title: { type: String, default: '' },
  data: { type: Array, default: () => [] },
  type: { type: String, default: 'up' },
  plain: { type: Boolean, default: false }
})

defineEmits(['select'])

function rankBadge(idx) {
  if (idx === 0) return 'r1'
  if (idx === 1) return 'r2'
  if (idx === 2) return 'r3'
  return ''
}

function codeColor(item) { return getStockColorClass(item) }

// 滚动速度：每项 2 秒
const scrollDuration = computed(() => Math.max(props.data.length * 2, 10))

function fmtPrice(v) {
  if (v == null) return '--'
  return Number(v).toFixed(2)
}
function fmtPct(item) {
  const pct = item.change_pct ?? item.changePct
  if (pct == null) return '--'
  return (pct >= 0 ? '+' : '') + Number(pct).toFixed(2) + '%'
}

// ---- Sparkline ----
const sparkData = ref({})   // { code: [close, close, ...] }
const sparkTimes = ref([])  // [0, 5, 10, ...] 距开盘分钟数
const MAX_MIN = 255         // 压缩后：9:30=0 → 15:00=255（午休压缩 75 分钟）
let sparkTimer = null

watch(() => props.data, (list) => {
  clearTimeout(sparkTimer)
  sparkTimer = setTimeout(async () => {
    const codes = list.map(i => i.code).filter(Boolean)
    if (!codes.length) return
    try {
      const res = await stockApi.getSparkBatch(codes)
      sparkTimes.value = (res?.times || []).map(Number)
      sparkData.value = res?.data || res || {}
    } catch {}
  }, 300)
}, { immediate: true, deep: false })

function sparkViewBox() {
  return `0 0 ${MAX_MIN + 4} 20`
}

function sparkArea(closes) {
  if (!closes || closes.length < 2) return ''
  const h = 20, padY = 2, padX = 2, times = sparkTimes.value
  const min = Math.min(...closes), max = Math.max(...closes), range = max - min
  const midY = h / 2
  const xScale = (MAX_MIN - padX * 2) / MAX_MIN
  const segments = []
  let seg = []
  for (let i = 0; i < closes.length; i++) {
    const gap = i > 0 && (times[i] - times[i - 1] >= 10)
    if (gap && seg.length) { segments.push(seg); seg = [] }
    const rawX = times[i] != null ? times[i] : (i / (closes.length - 1)) * MAX_MIN
    const x = padX + rawX * xScale
    const y = range === 0 ? midY : padY + (1 - (closes[i] - min) / range) * (h - padY * 2)
    seg.push({ x, y })
  }
  if (seg.length) segments.push(seg)
  return segments.map(s => {
    const top = s.map((p, j) => `${j === 0 ? 'M' : 'L'}${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' ')
    const lastX = s[s.length - 1].x, firstX = s[0].x
    return top + ` L${lastX.toFixed(1)},20 L${firstX.toFixed(1)},20 Z`
  }).join(' ')
}

function sparkPath(closes) {
  if (!closes || closes.length < 2) return ''
  const h = 20, padY = 2, padX = 2, times = sparkTimes.value
  const min = Math.min(...closes), max = Math.max(...closes), range = max - min
  const midY = h / 2
  const xScale = (MAX_MIN - padX * 2) / MAX_MIN
  return closes.map((v, i) => {
    const rawX = times[i] != null ? times[i] : (i / (closes.length - 1)) * MAX_MIN
    const x = padX + rawX * xScale
    const y = range === 0 ? midY : padY + (1 - (v - min) / range) * (h - padY * 2)
    // 时间跳跃 > 10 分钟（如午休）断开路径，不连斜线
    const gap = i > 0 && (times[i] - times[i - 1] >= 10)
    const cmd = (i === 0 || gap) ? 'M' : 'L'
    return `${cmd}${x.toFixed(1)},${y.toFixed(1)}`
  }).join(' ')
}

function sparkColor(item) {
  // 用 changePct（基于昨收计算）决定颜色，和个股名称/代码颜色一致
  const pct = item.change_pct ?? item.changePct
  if (pct == null) return 'var(--text-muted)'
  if (pct > 0) return 'var(--stock-up)'
  if (pct < 0) return 'var(--stock-down)'
  return 'var(--text-muted)'
}
</script>

<style scoped>
.panel {
  background: var(--bg-card); border: 1px solid var(--border-subtle);
  border-radius: 0; overflow: hidden;
  height: 100%; display: flex; flex-direction: column;
}
.panel.plain { background: transparent; border: none; border-radius: 0; height: 100%; display: flex; flex-direction: column; }

.panel-head {
  display: flex; justify-content: space-between; align-items: center;
  padding: 12px 16px; border-bottom: 1px solid var(--border-subtle);
  background: var(--accent-bg);
}
.panel-title { font-size: 14px; font-weight: 600; color: var(--text-primary); margin: 0 }
.panel-count { font-size: 11px; color: var(--text-muted); background: var(--bg-elevated); padding: 2px 8px; border-radius: var(--radius-sm) }

.panel-body { flex: 1; overflow: hidden; display: flex; flex-direction: column }

/* ---- Scroll viewport & track ---- */
.scroll-viewport {
  flex: 1; overflow: hidden; min-height: 0;
  mask-image: linear-gradient(to bottom, black 85%, transparent 100%);
  -webkit-mask-image: linear-gradient(to bottom, black 85%, transparent 100%);
}
.scroll-track { animation: rank-scroll linear infinite }
.scroll-track:hover { animation-play-state: paused }

@keyframes rank-scroll {
  0%   { transform: translateY(0) }
  100% { transform: translateY(-50%) }
}

/* ---- Row ---- */
.row {
  display: flex; align-items: center; padding: 3px 4px; gap: 2px;
  cursor: pointer; transition: background .1s;
  border-bottom: 1px solid rgba(45,51,64,0.3);
  height: 36px; flex-shrink: 0;
}
.row:nth-child(odd)  { background: transparent }
.row:nth-child(even) { background: rgba(255,255,255,0.06) }
.row:hover { background: var(--bg-hover) }

.rank-badge {
  width: 16px; height: 16px; display: flex; align-items: center; justify-content: center;
  border-radius: 0; font-size: 10px; font-weight: 600;
  color: var(--text-muted); background: var(--bg-elevated); flex-shrink: 0;
}
.rank-badge.r1 { background: var(--stock-up); color: #fff; box-shadow: 0 0 6px rgba(255,73,91,0.5); font-size: 11px }
.rank-badge.r2 { background: #e17341; color: #fff; box-shadow: 0 0 5px rgba(225,115,65,0.4); font-size: 11px }
.rank-badge.r3 { background: var(--stock-warn); color: #1A1A1A; box-shadow: 0 0 5px rgba(232,186,64,0.4); font-size: 11px }

.stock-main { overflow: hidden; line-height: 1.3; flex-shrink: 0 }
.stock-name {
  display: block;
  font-size: 12px; font-weight: 500; color: var(--text-primary);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.stock-code {
  font-size: 11px; font-family: var(--font-mono); font-weight: 400;
  letter-spacing: .02em; color: var(--text-muted);
}

.stock-sides {
  display: flex; flex-direction: column; align-items: flex-end; gap: 1px;
  font-size: 10px; font-family: var(--font-mono); flex-shrink: 0;
}
.side-price { color: var(--text-secondary) }
.side-pct { font-weight: 600 }

.spark-wrap {
  flex: 1; height: 18px;
  display: flex; align-items: center;
  min-width: 40px;
}
.spark-svg {
  width: 100%; height: 100%;
  opacity: 0.85;
}
.empty { padding: 32px; text-align: center; color: var(--text-muted); font-size: 13px }
</style>
