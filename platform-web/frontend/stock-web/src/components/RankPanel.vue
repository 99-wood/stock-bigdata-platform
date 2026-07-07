<template>
  <div class="panel" :class="{ plain: plain }">
    <div class="panel-head" v-if="!plain">
      <h3 class="panel-title">{{ title }}</h3>
      <span class="panel-count">{{ data.length }}</span>
    </div>
    <div class="panel-body">
      <div class="rank-cols">
        <div class="rank-col">
          <TransitionGroup name="rank" tag="div">
            <div v-for="(item, idx) in leftCol" :key="item.code" class="row group" @click="$emit('select', item.code)">
              <span class="rank-badge" :class="rankBadge(idx)">{{ idx + 1 }}</span>
              <div class="stock-main">
                <span class="stock-name" v-if="item.name" :class="codeColor(item)">{{ item.name }}</span>
                <span class="stock-code" :class="codeColor(item)">{{ item.code }}</span>
              </div>
              <svg v-if="sparkData[item.code]" class="spark-svg" viewBox="0 0 90 20" :stroke="sparkColor(sparkData[item.code])">
                <path :d="sparkPath(sparkData[item.code])" fill="none" stroke-width="1.2" />
              </svg>
              <div class="stock-sides">
                <span class="ask">{{ side(item, 'ask') }}</span>
                <span class="bid">{{ side(item, 'bid') }}</span>
              </div>
            </div>
          </TransitionGroup>
        </div>
        <div class="rank-col">
          <TransitionGroup name="rank" tag="div">
            <div v-for="(item, idx) in rightCol" :key="item.code" class="row group" @click="$emit('select', item.code)">
              <span class="rank-badge" :class="rankBadge(idx + 10)">{{ idx + 11 }}</span>
              <div class="stock-main">
                <span class="stock-name" v-if="item.name" :class="codeColor(item)">{{ item.name }}</span>
                <span class="stock-code" :class="codeColor(item)">{{ item.code }}</span>
              </div>
              <svg v-if="sparkData[item.code]" class="spark-svg" viewBox="0 0 90 20" :stroke="sparkColor(sparkData[item.code])">
                <path :d="sparkPath(sparkData[item.code])" fill="none" stroke-width="1.2" />
              </svg>
              <div class="stock-sides">
                <span class="ask">{{ side(item, 'ask') }}</span>
                <span class="bid">{{ side(item, 'bid') }}</span>
              </div>
            </div>
          </TransitionGroup>
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

const leftCol = computed(() => props.data.slice(0, 10))
const rightCol = computed(() => props.data.slice(10, 20))

function side(item, which) {
  const v = which === 'bid' ? Number(item.bid) : Number(item.ask)
  return v != null ? v.toFixed(2) : '--'
}

// ---- Sparkline ----
const sparkData = ref({}) // { code: [close, close, ...] }
let sparkTimer = null

watch(() => props.data, (list) => {
  clearTimeout(sparkTimer)
  sparkTimer = setTimeout(async () => {
    const codes = list.map(i => i.code).filter(Boolean)
    if (!codes.length) return
    try { sparkData.value = await stockApi.getSparkBatch(codes) || {} } catch {}
  }, 300)
}, { immediate: true, deep: false })

function sparkPath(closes) {
  if (!closes || closes.length < 2) return ''
  const h = 20, pad = 2, xStep = 2 // 固定 2px 点距，从左向右生长
  const min = Math.min(...closes), max = Math.max(...closes), range = max - min || 1
  return closes.map((v, i) => {
    const x = pad + i * xStep
    const y = pad + (1 - (v - min) / range) * (h - pad * 2)
    return `${i === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`
  }).join(' ')
}

function sparkColor(closes) {
  if (!closes || closes.length < 2) return 'var(--text-muted)'
  return closes[closes.length - 1] >= closes[0] ? 'var(--stock-up)' : 'var(--stock-down)'
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
.rank-cols { display: flex; gap: 0; flex: 1; min-height: 0 }
.rank-col { flex: 1; min-width: 0; display: flex; flex-direction: column }
.rank-col > div { flex: 1; display: flex; flex-direction: column }
.rank-col > div > .row { flex: 1 }
.rank-col + .rank-col { border-left: 1px solid rgba(45,51,64,0.3) }

/* ---- Row ---- */
.row {
  display: flex; align-items: center; padding: 2px 2px; gap: 1px;
  cursor: pointer; transition: background .1s;
  border-bottom: 1px solid rgba(45,51,64,0.3);
}
.row:hover { background: var(--bg-hover) }
.row:last-child { border-bottom: none; margin-bottom: 0 }

.rank-badge {
  width: 16px; height: 16px; display: flex; align-items: center; justify-content: center;
  border-radius: 0; font-size: 10px; font-weight: 600;
  color: var(--text-muted); background: var(--bg-elevated); flex-shrink: 0;
}
.rank-badge.r1 { background: var(--stock-up); color: #fff }
.rank-badge.r2 { background: #e17341; color: #fff }
.rank-badge.r3 { background: var(--stock-warn); color: #1A1A1A }

.stock-main { flex: 1; overflow: hidden; line-height: 1.3 }
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
  font-size: 11px; font-family: var(--font-mono); flex-shrink: 0;
}
.bid { color: var(--stock-down) }
.ask { color: var(--stock-up) }

.spark-svg {
  width: 90px; height: 18px; flex-shrink: 0;
  margin: 0; opacity: 0.85;
}
.empty { padding: 32px; text-align: center; color: var(--text-muted); font-size: 13px }

/* ---- FLIP Transition ---- */
.rank-move,
.rank-enter-active,
.rank-leave-active {
  transition: all 0.5s ease;
}
.rank-enter-from,
.rank-leave-to {
  opacity: 0;
  transform: translateX(30px);
}
.rank-leave-active {
  position: absolute;
}
</style>
