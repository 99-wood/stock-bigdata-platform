<template>
  <div class="root">
    <!-- ═══ Hero ═══ -->
    <section class="hero">
      <div class="hero-info">
        <div class="hero-top">
          <h1 class="hero-name" v-if="stock.name" :class="changeDir">{{ stock.name }}</h1>
          <span class="hero-time">{{ stock.trade_date }} {{ stock.trade_time }}</span>
        </div>
        <div class="hero-mid">
          <span class="hero-code" :class="codeClass">{{ stock.code }}</span>
          <span class="hero-price" :class="changeDir">{{ fmt(stock.price) }}</span>
          <span class="hero-change" v-if="stock.change_pct != null" :class="changeDir">{{ fmtChg(stock.change_pct) }}</span>
        </div>
        <div class="hero-ba-row" v-if="limitLabel">
          <span class="hero-limit" :class="limitDir">{{ limitLabel }}</span>
        </div>
      </div>
      <div v-if="hasOHLC" class="ho-grid">
        <span class="ho-item"><i>成交</i><b :class="changeDir">{{ f(stock.price) }}</b></span>
        <span class="ho-item"><i>量</i><b class="muted">{{ fmtVol(stock.volume) }}</b></span>
        <span class="ho-item"><i>开</i><b>{{ f(stock.open) }}</b></span>
        <span class="ho-item"><i>额</i><b class="muted">{{ fmtAmt(stock.amount) }}</b></span>
        <span class="ho-item"><i>高</i><b class="up">{{ f(stock.high) }}</b></span>
        <span class="ho-item"><i>收</i><b class="muted">{{ f(prevClose) }}</b></span>
        <span class="ho-item"><i>低</i><b class="down">{{ f(stock.low) }}</b></span>
        <span v-if="stock.change_pct != null" class="ho-item"><i>幅</i><b :class="changeDir">{{ fmtChg(stock.change_pct) }}</b></span>
      </div>
    </section>

    <!-- ═══ K-line + Depth ═══ -->
    <div class="main-split">
      <div class="kline-area">
        <div class="kl-tabs">
          <button class="kl-tab" :class="{ on: klineType === 'minute' }" @click="switchKline('minute')">分时</button>
          <button class="kl-tab" :class="{ on: klineType === 'daily' }" @click="switchKline('daily')">日K</button>
        </div>
        <KLineChart :data="klineData" />
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { getStockColorClass } from '@/utils/stockColor'
import { stockApi } from '@/api/request'
import KLineChart from '@/components/KLineChart.vue'

const p = defineProps({ stock: Object })

const codeClass = computed(() => getStockColorClass(p.stock))

const changeDir = computed(() => {
  const v = p.stock.change_pct
  if (v > 0) return 'up'
  if (v < 0) return 'down'
  return ''
})

const hasOHLC = computed(() => p.stock.price != null || p.stock.open != null)

const prevClose = computed(() => {
  const s = p.stock
  if (s.pre_close != null) return s.pre_close
  if (s.prev_close != null) return s.prev_close
  if (s.price != null && s.change != null) return s.price - s.change
  if (s.price != null && s.change_pct != null) return s.price / (1 + s.change_pct / 100)
  return null
})

function f(v) { return v != null ? Number(v).toFixed(2) : '--' }
function fmt(v) { return v != null ? Number(v).toFixed(2) : '--' }

const limitLabel = computed(() => {
  const b = Number(p.stock.bid), a = Number(p.stock.ask)
  if (b > 0 && a === 0) return '涨停'
  if (a > 0 && b === 0) return '跌停'
  return null
})
const limitDir = computed(() => limitLabel.value === '涨停' ? 'up' : 'down')

function fmtVol(v) {
  if (v == null) return '--'
  const hands = Number(v) / 100
  if (hands >= 10000) return (hands / 10000).toFixed(1) + '万手'
  return hands.toLocaleString() + '手'
}
function fmtAmt(v) {
  if (v == null) return '--'
  if (v >= 100000000) return (v / 100000000).toFixed(2) + '亿'
  return (v / 10000).toFixed(0) + '万'
}
function fmtChg(v) { return v != null ? (v > 0 ? '+' : '') + Number(v).toFixed(2) + '%' : '--' }

// K-line: 分时 / 日K 切换
const klineType = ref('minute')
const minuteData = ref([])
const dailyData = ref([])

const klineData = computed(() => klineType.value === 'minute' ? minuteData.value : dailyData.value)

async function loadMinute(code) {
  try { minuteData.value = await stockApi.getStockMinutes(code, '') || [] } catch { minuteData.value = [] }
}
async function loadDaily(code) {
  try {
    const rows = await stockApi.getStockHistory(code, 120) || []
    // 转换日K格式: trade_date→time, 保持open/high/low/close, volume→vol
    dailyData.value = rows.map(r => ({
      time: (r.trade_date || '').substring(5), // "MM-DD"
      open: r.open, high: r.high, low: r.low, close: r.close,
      vol: r.volume || 0
    }))
  } catch { dailyData.value = [] }
}

async function switchKline(type) {
  klineType.value = type
  const code = p.stock?.code
  if (!code) return
  if (type === 'daily' && !dailyData.value.length) await loadDaily(code)
}

watch(() => p.stock?.code, async (code) => {
  if (!code) return
  dailyData.value = []
  if (klineType.value === 'daily') {
    await loadDaily(code)
  } else {
    await loadMinute(code)
  }
}, { immediate: true })
</script>

<style scoped>
.root { height: 100%; display: flex; flex-direction: column; overflow: hidden }

/* ── Hero ── */
.hero {
  display: flex; gap: 0;
  padding-bottom: 4px; border-bottom: 1px solid var(--border-subtle); margin-bottom: 4px;
}
.hero-info { display: flex; flex-direction: column; gap: 2px }
.hero-top { display: flex; align-items: center; gap: 6px }
.hero-mid { display: flex; align-items: baseline; gap: 6px }
.hero-ba-row { display: flex; align-items: center; gap: 4px }
.hero-name { font-size: 20px; font-weight: 700; letter-spacing: -.02em }
.hero-name.up { color: var(--stock-up) }
.hero-name.down { color: var(--stock-down) }
.hero-code { font-size: 13px; font-weight: 600; font-family: var(--font-mono); letter-spacing: .04em }
.hero-price { font-size: 24px; font-weight: 700; font-family: var(--font-mono); letter-spacing: -.02em }
.hero-price.up { color: var(--stock-up) }
.hero-price.down { color: var(--stock-down) }

.hero-change { font-size: 14px; font-weight: 600; padding: 2px 8px; border-radius: var(--radius-sm) }
.hero-change.up { color: var(--stock-up); background: var(--stock-up-bg) }
.hero-change.down { color: var(--stock-down); background: var(--stock-down-bg) }

.hero-limit { font-size: 13px; font-weight: 700 }
.hero-limit.up { color: var(--stock-up) }
.hero-limit.down { color: var(--stock-down) }

.hero-time { font-size: 11px; color: var(--accent); font-family: var(--font-mono); flex-shrink: 0 }
/* ── OHLCV grid ── */
.ho-grid {
  display: grid; grid-template-columns: auto auto; gap: 1px 8px;
  flex-shrink: 0;
}
.ho-item { display: flex; gap: 1px; align-items: baseline; white-space: nowrap }
.ho-item i { font-size: 9px; color: var(--text-muted); font-style: normal }
.ho-item b { font-size: 12px; font-weight: 600; font-family: var(--font-mono); color: var(--text-primary) }
.ho-item b.up { color: var(--stock-up) }
.ho-item b.down { color: var(--stock-down) }
.ho-item b.muted { color: var(--text-secondary) }

/* ── K-line + Depth Split ── */
.main-split { flex: 1; display: flex; min-height: 0 }
.kline-area { flex: 1; min-height: 0; overflow: hidden; display: flex; flex-direction: column }

.kl-tabs {
  display: flex; gap: 0; flex-shrink: 0;
  margin-bottom: 4px;
}
.kl-tab {
  padding: 3px 14px; font-size: 11px; font-weight: 600;
  background: transparent; border: 1px solid var(--border-subtle);
  color: var(--text-muted); cursor: pointer;
  font-family: var(--font-mono); transition: all .15s;
}
.kl-tab:first-child { border-radius: var(--radius-sm) 0 0 var(--radius-sm) }
.kl-tab:last-child  { border-radius: 0 var(--radius-sm) var(--radius-sm) 0 }
.kl-tab.on { background: var(--accent-bg); color: var(--accent); border-color: var(--accent) }
.kl-tab:hover:not(.on) { color: var(--text-primary) }

@media (max-width: 768px) {
  .main-split { grid-template-columns: 1fr }
  .hero { flex-direction: column; gap: 8px }
}
</style>
