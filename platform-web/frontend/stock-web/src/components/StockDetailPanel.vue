<template>
  <div class="root">
    <!-- ═══ Hero ═══ -->
    <section class="hero">
      <div class="hero-left">
        <div class="hero-name-row">
          <h1 class="hero-name" v-if="stock.name">{{ stock.name }}</h1>
          <span class="hero-status" :class="statusBadge(stock.status)">{{ statusLabel(stock.status) }}</span>
        </div>
        <div class="hero-code-row">
          <span class="hero-code" :class="codeClass">{{ stock.code }}</span>
          <span class="hero-price" :class="changeDir">{{ fmt(stock.price) }}</span>
          <span class="hero-change" v-if="stock.change_pct != null" :class="changeDir">
            {{ fmtChg(stock.change_pct) }}
          </span>
        </div>
      </div>
      <div class="hero-right">
        <span class="hero-time">{{ stock.trade_date }} {{ stock.trade_time }}</span>
      </div>
    </section>

    <!-- ═══ Bid-Ask Summary ═══ -->
    <section class="ba-strip">
      <div class="ba-block bid">
        <span class="ba-label">BID 买一</span>
        <span class="ba-val green">{{ fmtBA('bid') }}</span>
      </div>
      <div class="ba-block spread">
        <span class="ba-label">SPREAD 价差</span>
        <span class="ba-val amber">{{ spreadStr }}</span>
      </div>
      <div class="ba-block ask">
        <span class="ba-label">ASK 卖一</span>
        <span class="ba-val red">{{ fmtBA('ask') }}</span>
      </div>
    </section>

    <!-- ═══ OHLCV Grid ═══ -->
    <section v-if="hasOHLC" class="ohlc-grid">
      <div class="ohlc-cell"><span class="ohlc-lbl">成交价</span><span class="ohlc-val" :class="changeDir">{{ f(stock.price) }}</span></div>
      <div class="ohlc-cell"><span class="ohlc-lbl">开盘</span><span class="ohlc-val">{{ f(stock.open) }}</span></div>
      <div class="ohlc-cell"><span class="ohlc-lbl">最高</span><span class="ohlc-val up">{{ f(stock.high) }}</span></div>
      <div class="ohlc-cell"><span class="ohlc-lbl">最低</span><span class="ohlc-val down">{{ f(stock.low) }}</span></div>
      <div class="ohlc-cell"><span class="ohlc-lbl">昨收</span><span class="ohlc-val">{{ f(stock.pre_close || stock.prev_close) }}</span></div>
      <div class="ohlc-cell"><span class="ohlc-lbl">成交量</span><span class="ohlc-val muted">{{ fmtVol(stock.volume) }}</span></div>
      <div class="ohlc-cell"><span class="ohlc-lbl">成交额</span><span class="ohlc-val muted">{{ fmtAmt(stock.amount) }}</span></div>
      <div v-if="stock.change_pct != null" class="ohlc-cell"><span class="ohlc-lbl">涨跌幅</span><span class="ohlc-val" :class="changeDir">{{ fmtChg(stock.change_pct) }}</span></div>
    </section>

    <!-- ═══ Depth Ladder ═══ -->
    <section class="depth">
      <div class="depth-side">
        <div class="depth-head red"><span class="dh-dot"></span>{{ isAllSellZero ? '涨停 · 无卖盘' : '卖盘 ASK' }}</div>
        <div class="depth-rows">
          <div v-for="(r,i) in sells" :key="'s'+i" class="d-row ask-row">
            <span class="dr-lvl">S{{ i+1 }}</span>
            <span class="dr-price red">{{ isAllSellZero ? '—' : f(r.price) }}</span>
            <span class="dr-vol">{{ isAllSellZero ? '—' : fmtV(r.vol) }}</span>
            <span class="dr-bar" :style="{width:sellBar(r.vol)+'%'}"></span>
          </div>
          <div v-if="!sells.length" class="d-empty">--</div>
        </div>
      </div>
      <div class="depth-side">
        <div class="depth-head green"><span class="dh-dot"></span>{{ isAllBuyZero ? '跌停 · 无买盘' : '买盘 BID' }}</div>
        <div class="depth-rows">
          <div v-for="(r,i) in buys" :key="'b'+i" class="d-row bid-row">
            <span class="dr-lvl">B{{ i+1 }}</span>
            <span class="dr-price green">{{ isAllBuyZero ? '—' : f(r.price) }}</span>
            <span class="dr-vol">{{ isAllBuyZero ? '—' : fmtV(r.vol) }}</span>
            <span class="dr-bar" :style="{width:buyBar(r.vol)+'%'}"></span>
          </div>
          <div v-if="!buys.length" class="d-empty">--</div>
        </div>
      </div>
    </section>

    <!-- ═══ Raw Data ═══ -->
    <details class="raw">
      <summary class="raw-summary">RAW 原始数据</summary>
      <pre class="raw-body">{{ JSON.stringify(stock, null, 2) }}</pre>
    </details>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { getStockColorClass } from '@/utils/stockColor'

const p = defineProps({ stock: Object })

const codeClass = computed(() => getStockColorClass(p.stock))

const changeDir = computed(() => {
  const v = p.stock.change_pct
  if (v > 0) return 'up'
  if (v < 0) return 'down'
  return ''
})

const hasOHLC = computed(() => p.stock.price != null || p.stock.open != null)

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

function fmtV(v) { return v != null ? (Number(v)/100).toFixed(0) : '--' }
function fmtVol(v) { return v != null ? Number(v).toLocaleString()+'手' : '--' }
function fmtAmt(v) { return v != null ? (Number(v)/10000).toFixed(2)+'亿' : '--' }
function fmtChg(v) { return v != null ? (v>0?'+':'')+Number(v).toFixed(2)+'%' : '--' }

function statusLabel(s) {
  const m = {'00':'正常','-1':'停牌','-2':'退市','-3':'停牌'}
  return m[s] || (s||'--')
}
function statusBadge(s) {
  if (s === '00') return 'ok'
  if (s === '-2') return 'bad'
  return 'warn'
}

const sells = computed(() => {
  const ls = []
  for (let i=1;i<=5;i++) {
    const pp = p.stock['s'+i+'_p'], vv = p.stock['s'+i+'_v']
    if (pp != null) ls.push({price:parseFloat(pp),vol:parseInt(vv)||0})
  }
  return ls
})
const buys = computed(() => {
  const ls = []
  for (let i=1;i<=5;i++) {
    const pp = p.stock['b'+i+'_p'], vv = p.stock['b'+i+'_v']
    if (pp != null) ls.push({price:parseFloat(pp),vol:parseInt(vv)||0})
  }
  return ls
})

const spreadStr = computed(() => {
  const b=Number(p.stock.bid),a=Number(p.stock.ask)
  if (isNaN(b)||isNaN(a)) return '--'
  if (b>0 && a===0) return '涨停'
  if (a>0 && b===0) return '跌停'
  if (b===0 && a===0) return '停牌'
  return (a-b).toFixed(3)
})

const isAllSellZero = computed(() => sells.value.length>0 && sells.value.every(l=>l.price===0))
const isAllBuyZero = computed(() => buys.value.length>0 && buys.value.every(l=>l.price===0))

function sellBar(vol) { const m=Math.max(...sells.value.map(l=>l.vol),1); return Math.round(vol/m*100) }
function buyBar(vol) { const m=Math.max(...buys.value.map(l=>l.vol),1); return Math.round(vol/m*100) }
</script>

<style scoped>
.root { }

/* ── Hero ── */
.hero {
  display: flex; justify-content: space-between; align-items: flex-start;
  padding-bottom: 18px; border-bottom: 1px solid var(--border-subtle); margin-bottom: 20px;
}
.hero-left { display: flex; flex-direction: column; gap: 8px }
.hero-name-row { display: flex; align-items: center; gap: 12px }
.hero-name { font-size: 24px; font-weight: 700; color: var(--text-white); letter-spacing: -.02em }
.hero-code-row { display: flex; align-items: baseline; gap: 14px }

.hero-code {
  font-size: 16px; font-weight: 600; font-family: var(--font-mono); letter-spacing: .04em;
}

.hero-price { font-size: 28px; font-weight: 700; font-family: var(--font-mono); letter-spacing: -.02em }
.hero-price.up { color: var(--stock-up) }
.hero-price.down { color: var(--stock-down) }

.hero-change {
  font-size: 16px; font-weight: 600; font-family: var(--font-mono);
  padding: 3px 10px; border-radius: var(--radius-full);
}
.hero-change.up { color: var(--stock-up); background: var(--stock-up-bg) }
.hero-change.down { color: var(--stock-down); background: var(--stock-down-bg) }

.hero-status {
  font-size: 10px; font-weight: 600; letter-spacing: .06em;
  padding: 2px 10px; border-radius: var(--radius-full);
}
.hero-status.ok  { color: var(--stock-down); background: var(--stock-down-bg); border: 1px solid var(--stock-down-border) }
.hero-status.warn{ color: var(--stock-warn); background: var(--stock-warn-bg); border: 1px solid var(--stock-warn-border) }
.hero-status.bad { color: var(--stock-up); background: var(--stock-up-bg); border: 1px solid var(--stock-up-border) }

.hero-right { flex-shrink: 0 }
.hero-time { font-size: 12px; color: var(--text-muted); font-family: var(--font-mono) }

/* ── BA Strip ── */
.ba-strip {
  display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 2px;
  background: var(--border-default); border: 1px solid var(--border-default);
  border-radius: var(--radius-lg); overflow: hidden; margin-bottom: 20px;
}
.ba-block { background: var(--bg-surface); padding: 20px 16px; text-align: center }
.ba-label { display: block; font-size: 10px; font-weight: 600; color: var(--text-muted); letter-spacing: .08em; margin-bottom: 6px }
.ba-val { font-size: 30px; font-weight: 700; font-family: var(--font-mono); letter-spacing: -.03em }
.ba-val.green { color: var(--stock-down) }
.ba-val.red   { color: var(--stock-up) }
.ba-val.amber { color: var(--stock-warn) }

/* ── OHLCV Grid ── */
.ohlc-grid { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 18px }

.ohlc-cell {
  flex: 1; min-width: 68px; padding: 10px 8px;
  background: var(--bg-surface); border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md); text-align: center;
}
.ohlc-lbl { display: block; font-size: 10px; font-weight: 600; color: var(--text-muted); letter-spacing: .06em; margin-bottom: 4px }
.ohlc-val { font-size: 14px; font-weight: 600; color: var(--text-primary); font-family: var(--font-mono) }
.ohlc-val.up { color: var(--stock-up) }
.ohlc-val.down { color: var(--stock-down) }
.ohlc-val.muted { font-size: 12px; color: var(--text-secondary) }

/* ── Depth ── */
.depth { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; margin-bottom: 20px }

.depth-side {
  background: var(--bg-surface); border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg); overflow: hidden;
}

.depth-head {
  display: flex; align-items: center; gap: 8px;
  padding: 10px 16px; font-size: 10px; font-weight: 600; letter-spacing: .08em;
  border-bottom: 1px solid var(--border-subtle); background: var(--bg-elevated);
}
.depth-head.red { color: var(--stock-up) }
.depth-head.green { color: var(--stock-down) }
.dh-dot { width: 5px; height: 5px; border-radius: 50% }
.depth-head.red .dh-dot { background: var(--stock-up) }
.depth-head.green .dh-dot { background: var(--stock-down) }

.d-row {
  display: flex; align-items: center; height: 34px; padding: 0 16px; gap: 12px;
  border-bottom: 1px solid rgba(255,255,255,.03); position: relative;
  font-family: var(--font-mono); font-size: 12px;
}
.d-row:last-child { border-bottom: none }

.dr-lvl { width: 20px; font-size: 10px; color: var(--text-muted); flex-shrink: 0 }
.dr-price { flex: 1; text-align: right; font-weight: 600; position: relative; z-index: 1 }
.dr-price.red { color: var(--stock-up) }
.dr-price.green { color: var(--stock-down) }
.dr-vol { width: 52px; text-align: right; color: var(--text-secondary); font-size: 11px; position: relative; z-index: 1 }

.dr-bar {
  position: absolute; right: 0; top: 2px; bottom: 2px;
  opacity: .10; border-radius: 0 2px 2px 0;
  pointer-events: none; transition: width .3s;
}
.ask-row .dr-bar { background: var(--stock-up) }
.bid-row .dr-bar { background: var(--stock-down) }

.d-empty { padding: 28px; text-align: center; color: var(--text-muted); font-size: 12px }

/* ── Raw ── */
.raw { border: 1px solid var(--border-subtle); border-radius: var(--radius-md); overflow: hidden }
.raw-summary {
  padding: 10px 16px; font-size: 10px; font-weight: 600; color: var(--text-muted);
  letter-spacing: .08em; cursor: pointer; background: var(--bg-surface); user-select: none;
}
.raw-body {
  max-height: 200px; overflow-y: auto; background: var(--bg-root);
  color: var(--text-secondary); font-size: 11px; padding: 14px 16px; margin: 0;
  font-family: var(--font-mono); white-space: pre-wrap; word-break: break-all; line-height: 1.5;
}

@media (max-width: 768px) {
  .depth { grid-template-columns: 1fr }
  .ba-strip { grid-template-columns: 1fr }
}
</style>
