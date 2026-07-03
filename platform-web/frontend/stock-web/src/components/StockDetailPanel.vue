<template>
  <div class="detail-panel-root">
    <!-- Hero -->
    <div class="detail-hero">
      <div class="hero-left">
        <h1 class="hero-name" v-if="stock.name">{{ stock.name }}</h1>
        <h1 class="hero-code" :class="codeClass">{{ stock.code }}</h1>
        <span class="hero-status" :class="statusClass(stock.status)">
          {{ statusText(stock.status) }}
        </span>
      </div>
      <div class="hero-right">
        <span class="hero-time">{{ stock.trade_date }} {{ stock.trade_time }}</span>
      </div>
    </div>

    <!-- Bid-Ask Summary -->
    <div class="ba-strip">
      <div class="ba-block bid-block">
        <span class="ba-label">买一 BID</span>
        <span class="ba-value green">{{ fmtBidAsk('bid') }}</span>
      </div>
      <div class="ba-block spread-block">
        <span class="ba-label">价差 SPRD</span>
        <span class="ba-value amber">{{ spreadVal }}</span>
      </div>
      <div class="ba-block ask-block">
        <span class="ba-label">卖一 ASK</span>
        <span class="ba-value red">{{ fmtBidAsk('ask') }}</span>
      </div>
    </div>

    <!-- Depth Ladder -->
    <div class="depth-ladder">
      <!-- Sell Side (Ask) -->
      <div class="depth-side sell-side">
        <div class="depth-label red">
          {{ isAllSellZero ? '涨停 — 无卖盘' : '卖盘 ASK' }}
        </div>
        <div class="depth-rows">
          <div
            v-for="(row, i) in sellLevels"
            :key="'s'+i"
            class="depth-row ask-row"
          >
            <span class="d-level">S{{ i + 1 }}</span>
            <span class="d-price red">{{ isAllSellZero ? '—' : fmtP(row.price) }}</span>
            <span class="d-vol">{{ isAllSellZero ? '—' : fmtV(row.vol) }}</span>
            <span class="d-bar" :style="{ width: sellBarWidth(row.vol) + '%' }"></span>
          </div>
          <div v-if="sellLevels.length === 0" class="depth-empty">--</div>
        </div>
      </div>

      <!-- Buy Side (Bid) -->
      <div class="depth-side buy-side">
        <div class="depth-label green">
          {{ isAllBuyZero ? '跌停 — 无买盘' : '买盘 BID' }}
        </div>
        <div class="depth-rows">
          <div
            v-for="(row, i) in buyLevels"
            :key="'b'+i"
            class="depth-row bid-row"
          >
            <span class="d-level">B{{ i + 1 }}</span>
            <span class="d-price green">{{ isAllBuyZero ? '—' : fmtP(row.price) }}</span>
            <span class="d-vol">{{ isAllBuyZero ? '—' : fmtV(row.vol) }}</span>
            <span class="d-bar" :style="{ width: buyBarWidth(row.vol) + '%' }"></span>
          </div>
          <div v-if="buyLevels.length === 0" class="depth-empty">--</div>
        </div>
      </div>
    </div>

    <!-- Raw Data -->
    <details class="raw-section">
      <summary class="raw-summary">原始行情数据 RAW</summary>
      <pre class="raw-json">{{ JSON.stringify(stock, null, 2) }}</pre>
    </details>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { getStockColorClass } from '@/utils/stockColor'

const props = defineProps({
  stock: { type: Object, required: true }
})

const codeClass = computed(() => getStockColorClass(props.stock))

// ---- Formats ----
function fmt(v) {
  if (v == null || v === '') return '--'
  return Number(v).toFixed(2)
}

function fmtBidAsk(side) {
  const b = Number(props.stock.bid), a = Number(props.stock.ask)
  if (side === 'bid') {
    // 跌停：卖盘有价但买盘为 0（无人接盘）
    if (b > 0) return b.toFixed(2)
    if (b === 0 && a === 0) return '停牌'
    return '跌停'
  }
  // ask side
  // 涨停：买盘有价但卖盘为 0（无人卖出）
  if (a > 0) return a.toFixed(2)
  if (a === 0 && b === 0) return '停牌'
  return '涨停'
}

function fmtP(v) {
  if (v == null) return '--'
  return Number(v).toFixed(2)
}

function fmtV(v) {
  if (v == null) return '--'
  return (Number(v) / 100).toFixed(0)
}

function statusText(s) {
  const map = { '00': '正常', '-1': '停牌', '-2': '退市', '-3': '停牌' }
  return map[s] || (s || '--')
}

function statusClass(s) {
  if (s === '00') return 'ok'
  if (s === '-2') return 'bad'
  return 'warn'
}

// ---- Depth levels ----
const sellLevels = computed(() => {
  const levels = []
  for (let i = 1; i <= 5; i++) {
    const p = props.stock['s' + i + '_p']
    const v = props.stock['s' + i + '_v']
    if (p != null) levels.push({ price: parseFloat(p), vol: parseInt(v) || 0 })
  }
  return levels
})

const buyLevels = computed(() => {
  const levels = []
  for (let i = 1; i <= 5; i++) {
    const p = props.stock['b' + i + '_p']
    const v = props.stock['b' + i + '_v']
    if (p != null) levels.push({ price: parseFloat(p), vol: parseInt(v) || 0 })
  }
  return levels
})

const spreadVal = computed(() => {
  const b = Number(props.stock.bid), a = Number(props.stock.ask)
  if (isNaN(b) || isNaN(a)) return '--'
  if (b > 0 && a === 0) return '涨停'
  if (a > 0 && b === 0) return '跌停'
  if (b === 0 && a === 0) return '停牌'
  return (a - b).toFixed(3)
})

// 涨停：所有卖盘价格为 0（市场封涨停，无人卖出）
const isAllSellZero = computed(() =>
  sellLevels.value.length > 0 && sellLevels.value.every(l => l.price === 0)
)

// 跌停：所有买盘价格为 0（市场封跌停，无人接盘）
const isAllBuyZero = computed(() =>
  buyLevels.value.length > 0 && buyLevels.value.every(l => l.price === 0)
)

// Bar width relative to max volume in the same side
function sellBarWidth(vol) {
  const maxV = Math.max(...sellLevels.value.map(l => l.vol), 1)
  return Math.round((vol / maxV) * 100)
}

function buyBarWidth(vol) {
  const maxV = Math.max(...buyLevels.value.map(l => l.vol), 1)
  return Math.round((vol / maxV) * 100)
}
</script>

<style scoped>
.detail-panel-root {
  /* component assumes it lives in a scroll container */
}

/* Hero */
.detail-hero {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 0 20px;
  border-bottom: 1px solid #2D3340;
  margin-bottom: 20px;
}

.hero-left {
  display: flex;
  align-items: center;
  gap: 14px;
}

.hero-name {
  font-size: 24px;
  font-weight: 700;
  color: #E3E7EC;
  margin: 0 0 4px;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
}

.hero-code {
  font-size: 20px;
  font-weight: 600;
  font-family: 'Fira Code', 'Cascadia Code', 'Consolas', monospace;
  color: #8892A0;
  letter-spacing: 0.04em;
  margin: 0;
}

.hero-status {
  font-size: 11px;
  font-weight: 600;
  padding: 3px 10px;
  border-radius: 3px;
  letter-spacing: 0.04em;
}

.hero-status.ok {
  background: rgba(63, 185, 80, 0.12);
  color: #3FB950;
  border: 1px solid rgba(63, 185, 80, 0.25);
}

.hero-status.warn {
  background: rgba(210, 153, 34, 0.12);
  color: #D29922;
  border: 1px solid rgba(210, 153, 34, 0.25);
}

.hero-status.bad {
  background: rgba(248, 81, 73, 0.12);
  color: #F85149;
  border: 1px solid rgba(248, 81, 73, 0.25);
}

.hero-time {
  font-size: 12px;
  color: #636D7E;
  font-family: 'Fira Code', monospace;
}

/* Bid-Ask Strip */
.ba-strip {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: 1px;
  background: #2D3340;
  border: 1px solid #2D3340;
  border-radius: 6px;
  overflow: hidden;
  margin-bottom: 20px;
}

.ba-block {
  background: #13161D;
  padding: 18px 20px;
  text-align: center;
}

.ba-label {
  display: block;
  font-size: 10px;
  color: #636D7E;
  letter-spacing: 0.06em;
  margin-bottom: 6px;
}

.ba-value {
  font-size: 26px;
  font-weight: 700;
  font-family: 'Fira Code', 'Cascadia Code', 'Consolas', monospace;
}

.ba-value.green { color: #3FB950; }
.ba-value.red { color: #F85149; }
.ba-value.amber { color: #D29922; }

/* Depth Ladder */
.depth-ladder {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  margin-bottom: 20px;
}

.depth-side {
  background: #13161D;
  border: 1px solid #2D3340;
  border-radius: 6px;
  overflow: hidden;
}

.depth-label {
  padding: 10px 16px;
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.08em;
  border-bottom: 1px solid #2D3340;
  background: rgba(0,0,0,0.15);
}

.depth-label.red { color: #F85149; }
.depth-label.green { color: #3FB950; }

.depth-rows {
  padding: 0;
}

.depth-row {
  display: flex;
  align-items: center;
  height: 34px;
  padding: 0 16px;
  gap: 12px;
  border-bottom: 1px solid rgba(45, 51, 64, 0.3);
  position: relative;
  font-family: 'Fira Code', 'Cascadia Code', 'Consolas', monospace;
  font-size: 12px;
}

.depth-row:last-child {
  border-bottom: none;
}

.d-level {
  width: 24px;
  font-size: 10px;
  color: #4D5563;
  flex-shrink: 0;
}

.d-price {
  flex: 1;
  text-align: right;
  font-weight: 600;
  position: relative;
  z-index: 1;
}

.d-price.red { color: #F85149; }
.d-price.green { color: #3FB950; }

.d-vol {
  width: 60px;
  text-align: right;
  color: #8892A0;
  font-size: 11px;
  position: relative;
  z-index: 1;
}

.d-bar {
  position: absolute;
  right: 0;
  top: 0;
  height: 100%;
  opacity: 0.20;
  border-radius: 0 2px 2px 0;
  pointer-events: none;
  transition: width 0.3s ease;
}

.ask-row .d-bar { background: #F85149; }
.bid-row .d-bar { background: #3FB950; }

.depth-empty {
  padding: 24px;
  text-align: center;
  color: #4D5563;
  font-size: 12px;
}

/* Raw Data */
.raw-section {
  border: 1px solid #2D3340;
  border-radius: 6px;
  overflow: hidden;
}

.raw-summary {
  padding: 10px 16px;
  font-size: 10px;
  color: #636D7E;
  letter-spacing: 0.08em;
  cursor: pointer;
  background: #13161D;
  user-select: none;
}

.raw-json {
  max-height: 200px;
  overflow-y: auto;
  background: #0A0C10;
  color: #8892A0;
  font-size: 11px;
  padding: 14px 16px;
  margin: 0;
  font-family: 'Fira Code', 'Cascadia Code', 'Consolas', monospace;
  white-space: pre-wrap;
  word-break: break-all;
  line-height: 1.5;
}

@media (max-width: 768px) {
  .depth-ladder {
    grid-template-columns: 1fr;
  }

  .ba-strip {
    grid-template-columns: 1fr;
  }
}
</style>
