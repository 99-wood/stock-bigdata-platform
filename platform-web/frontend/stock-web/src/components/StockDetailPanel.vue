<template>
  <div class="root">
    <!-- ═══ Hero ═══ -->
    <section class="hero">
      <div class="hero-info">
        <div class="hero-top">
          <h1 class="hero-name" v-if="stock.name" :class="changeDir">{{ stock.name }}</h1>
          <span class="hero-status" :class="statusBadge(stock.status)">{{ statusLabel(stock.status) }}</span>
          <span class="hero-time">{{ stock.trade_date }} {{ stock.trade_time }}</span>
        </div>
        <div class="hero-mid">
          <span class="hero-code" :class="codeClass">{{ stock.code }}</span>
          <span class="hero-price" :class="changeDir">{{ fmt(stock.price) }}</span>
          <span class="hero-change" v-if="stock.change_pct != null" :class="changeDir">{{ fmtChg(stock.change_pct) }}</span>
        </div>
        <div class="hero-ba-row">
          <span class="hc-ba"><em class="hcb-label">买</em><em class="hcb-val green">{{ fmtBA('bid') }}</em></span>
          <span class="hc-sep"></span>
          <span class="hc-ba"><em class="hcb-label">价差</em><em class="hcb-val amber">{{ spreadStr }}</em></span>
          <span class="hc-sep"></span>
          <span class="hc-ba"><em class="hcb-label">卖</em><em class="hcb-val red">{{ fmtBA('ask') }}</em></span>
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
      <div class="depth-area">
        <section class="depth">
          <div class="depth-side">
            <div class="depth-head red"><span class="dh-dot"></span>{{ isAllSellZero ? '涨停 · 无卖盘' : '卖盘 ASK' }}</div>
            <div class="depth-rows">
              <div v-for="(r,i) in sells" :key="'s'+i" class="d-row ask-row">
                <span class="dr-bar" :style="{width:sellBar(r.vol)+'%'}"></span>
                <span class="dr-lvl">S{{ i+1 }}</span>
                <span class="dr-price red">{{ isAllSellZero ? '—' : f(r.price) }}</span>
                <span class="dr-vol">{{ isAllSellZero ? '—' : fmtV(r.vol) }}</span>
              </div>
              <div v-if="!sells.length" class="d-empty">--</div>
            </div>
          </div>
          <div class="depth-side">
            <div class="depth-head green"><span class="dh-dot"></span>{{ isAllBuyZero ? '跌停 · 无买盘' : '买盘 BID' }}</div>
            <div class="depth-rows">
              <div v-for="(r,i) in buys" :key="'b'+i" class="d-row bid-row">
                <span class="dr-bar" :style="{width:buyBar(r.vol)+'%'}"></span>
                <span class="dr-lvl">B{{ i+1 }}</span>
                <span class="dr-price green">{{ isAllBuyZero ? '—' : f(r.price) }}</span>
                <span class="dr-vol">{{ isAllBuyZero ? '—' : fmtV(r.vol) }}</span>
              </div>
              <div v-if="!buys.length" class="d-empty">--</div>
            </div>
          </div>
        </section>
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

function fmtBA(side) {
  const b = Number(p.stock.bid), a = Number(p.stock.ask)
  if (side === 'bid') {
    if (b > 0) return b.toFixed(2)
    if (b === 0 && a === 0) return '停牌'
    return '跌停'
  }
  if (a > 0) return a.toFixed(2)
  if (a === 0 && b === 0) return '停牌'
  return '涨停'
}

function fmtV(v) { return v != null ? (Number(v) / 100).toFixed(0) : '--' }
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

function statusLabel(s) { const m = { '00': '正常', '-1': '停牌', '-2': '退市', '-3': '停牌' }; return m[s] || (s || '--') }
function statusBadge(s) { if (s === '00') return 'ok'; if (s === '-2') return 'bad'; return 'warn' }

const sells = computed(() => {
  const ls = []
  for (let i = 1; i <= 5; i++) {
    const pp = p.stock['s' + i + '_p'], vv = p.stock['s' + i + '_v']
    if (pp != null) ls.push({ price: parseFloat(pp), vol: parseInt(vv) || 0 })
  }
  return ls
})
const buys = computed(() => {
  const ls = []
  for (let i = 1; i <= 5; i++) {
    const pp = p.stock['b' + i + '_p'], vv = p.stock['b' + i + '_v']
    if (pp != null) ls.push({ price: parseFloat(pp), vol: parseInt(vv) || 0 })
  }
  return ls
})

const spreadStr = computed(() => {
  const b = Number(p.stock.bid), a = Number(p.stock.ask)
  if (isNaN(b) || isNaN(a)) return '--'
  if (b > 0 && a === 0) return '涨停'
  if (a > 0 && b === 0) return '跌停'
  if (b === 0 && a === 0) return '停牌'
  return (a - b).toFixed(3)
})

const isAllSellZero = computed(() => sells.value.length > 0 && sells.value.every(l => l.price === 0))
const isAllBuyZero = computed(() => buys.value.length > 0 && buys.value.every(l => l.price === 0))

function sellBar(vol) { const m = Math.max(...sells.value.map(l => l.vol), 1); return Math.round(vol / m * 100) }
function buyBar(vol) { const m = Math.max(...buys.value.map(l => l.vol), 1); return Math.round(vol / m * 100) }

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

.hc-sep { width: 1px; height: 16px; background: var(--border-subtle); align-self: center }
.hc-ba { display: flex; gap: 4px; align-items: baseline }
.hcb-label { font-size: 10px; color: var(--text-muted); font-style: normal }
.hcb-val { font-size: 13px; font-weight: 600; font-family: var(--font-mono); font-style: normal }
.hcb-val.green { color: var(--stock-down) }
.hcb-val.red { color: var(--stock-up) }
.hcb-val.amber { color: var(--stock-warn) }

.hero-time { font-size: 11px; color: var(--accent); font-family: var(--font-mono); flex-shrink: 0 }
.hero-status { font-size: 10px; font-weight: 600; padding: 2px 8px; border-radius: var(--radius-sm) }
.hero-status.ok { background: rgba(63, 185, 80, 0.12); color: var(--stock-down); border: 1px solid rgba(63, 185, 80, 0.25) }
.hero-status.warn { background: rgba(210, 153, 34, 0.12); color: var(--stock-warn); border: 1px solid rgba(210, 153, 34, 0.25) }
.hero-status.bad { background: rgba(248, 81, 73, 0.12); color: var(--stock-up); border: 1px solid rgba(248, 81, 73, 0.25) }

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
.main-split { flex: 1; display: grid; grid-template-columns: 1fr 260px; gap: 0; min-height: 0 }
.kline-area { min-height: 0; overflow: hidden; display: flex; flex-direction: column }

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

/* ── Depth ── */
.depth-area { min-width: 0 }
.depth { display: flex; flex-direction: column; gap: 0; height: 100% }
.depth-side { flex: 1; background: var(--bg-surface); border: none; overflow: hidden }
.depth-side + .depth-side { border-top: 1px solid var(--border-subtle) }
.depth-head { padding: 6px 12px; font-size: 10px; font-weight: 600; letter-spacing: .06em; border-bottom: 1px solid var(--border-subtle) }
.depth-head.red { color: var(--stock-up) }
.depth-head.green { color: var(--stock-down) }
.dh-dot { display: inline-block; width: 4px; height: 4px; border-radius: 50%; background: currentColor; margin-right: 6px }
.depth-rows { flex: 1; display: flex; flex-direction: column }
.d-row { display: flex; align-items: center; flex: 1; padding: 0 12px; gap: 8px; position: relative; font-family: var(--font-mono); font-size: 11px }
.d-row + .d-row { border-top: 1px solid rgba(45,51,64,0.2) }
.dr-lvl { width: 18px; font-size: 9px; color: var(--text-muted); flex-shrink: 0 }
.dr-price { flex: 1; text-align: right; font-weight: 600; z-index: 1 }
.dr-price.red { color: var(--stock-up) }
.dr-price.green { color: var(--stock-down) }
.dr-vol { width: 50px; text-align: right; color: var(--text-secondary); font-size: 10px; z-index: 1 }
.dr-bar { position: absolute; right: 0; top: 0; height: 100%; opacity: .08; pointer-events: none }
.ask-row .dr-bar { background: var(--stock-up) }
.bid-row .dr-bar { background: var(--stock-down) }
.d-empty { padding: 20px; text-align: center; color: var(--text-muted); font-size: 11px }

@media (max-width: 768px) {
  .main-split { grid-template-columns: 1fr }
  .hero { flex-direction: column; gap: 8px }
}
</style>
