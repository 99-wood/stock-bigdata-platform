<template>
  <div class="terminal-page">
    <!-- ===== LEFT PANEL: Stock List ===== -->
    <aside class="list-panel">
      <div class="panel-topbar">
        <div class="topbar-title">
          <span class="title-dot"></span>
          <h2>STOCK LIST</h2>
          <span class="count-chip">{{ filteredList.length }} / {{ allStocks.length }}</span>
        </div>
        <div class="topbar-search">
          <el-input
            v-model="searchText"
            placeholder="搜索代码..."
            clearable
            size="small"
            class="terminal-input"
          >
            <template #prefix>
              <span class="search-prompt">&gt;</span>
            </template>
          </el-input>
        </div>
      </div>

      <!-- Loading -->
      <div v-if="loading" class="panel-loading">
        <el-skeleton :rows="10" animated />
      </div>

      <!-- Empty -->
      <div v-else-if="allStocks.length === 0" class="panel-empty">
        <div class="empty-icon">!</div>
        <span>暂无股票行情数据</span>
      </div>

      <!-- List Body -->
      <div v-else class="list-body">
        <div
          v-for="(row, idx) in paginatedList"
          :key="row.code"
          class="list-row"
          :class="{ selected: selectedCode === row.code }"
          @click="selectStock(row)"
        >
          <span class="row-idx">{{ indexOffset + idx }}</span>
          <span class="row-code">{{ row.code }}</span>
          <span class="row-bid green">{{ fmtBidAsk(row, 'bid') }}</span>
          <span class="row-ask red">{{ fmtBidAsk(row, 'ask') }}</span>
          <span class="row-spread">{{ spread(row) }}</span>
        </div>
      </div>

      <!-- Pagination -->
      <div class="panel-footer" v-if="filteredList.length > pageSize">
        <div class="pagination-row">
          <button
            class="pg-btn"
            :disabled="currentPage <= 1"
            @click="currentPage--"
          >&laquo;</button>
          <span class="pg-info">{{ currentPage }} / {{ totalPages }}</span>
          <button
            class="pg-btn"
            :disabled="currentPage >= totalPages"
            @click="currentPage++"
          >&raquo;</button>
        </div>
      </div>
    </aside>

    <!-- ===== RIGHT PANEL: Stock Detail ===== -->
    <main class="detail-panel">
      <!-- Empty state -->
      <template v-if="!selectedStock">
        <div class="detail-placeholder">
          <div class="placeholder-icon">◈</div>
          <p class="placeholder-title">证券交互终端</p>
          <p class="placeholder-hint">从左侧列表中选择一只股票<br/>查看实时五档行情深度</p>
          <div class="placeholder-stats">
            <div class="ph-stat">
              <span class="ph-val">{{ allStocks.length }}</span>
              <span class="ph-lbl">标的</span>
            </div>
            <div class="ph-stat">
              <span class="ph-val">{{ statusSummary.up }}</span>
              <span class="ph-lbl">上涨</span>
            </div>
            <div class="ph-stat">
              <span class="ph-val">{{ statusSummary.down }}</span>
              <span class="ph-lbl">下跌</span>
            </div>
          </div>
        </div>
      </template>

      <!-- Detail content -->
      <template v-else>
        <!-- Hero -->
        <div class="detail-hero">
          <div class="hero-left">
            <h1 class="hero-code">{{ selectedStock.code }}</h1>
            <span class="hero-status" :class="statusClass(selectedStock.status)">
              {{ statusText(selectedStock.status) }}
            </span>
          </div>
          <div class="hero-right">
            <span class="hero-time">{{ selectedStock.trade_date }} {{ selectedStock.trade_time }}</span>
          </div>
        </div>

        <!-- Bid-Ask Summary -->
        <div class="ba-strip">
          <div class="ba-block bid-block">
            <span class="ba-label">买一 BID</span>
            <span class="ba-value green">{{ fmtBidAsk(selectedStock, 'bid') }}</span>
          </div>
          <div class="ba-block spread-block">
            <span class="ba-label">价差 SPRD</span>
            <span class="ba-value amber">{{ spreadVal }}</span>
          </div>
          <div class="ba-block ask-block">
            <span class="ba-label">卖一 ASK</span>
            <span class="ba-value red">{{ fmtBidAsk(selectedStock, 'ask') }}</span>
          </div>
        </div>

        <!-- Depth Ladder -->
        <div class="depth-ladder">
          <!-- Sell Side (Ask) -->
          <div class="depth-side sell-side">
            <div class="depth-label red">
              {{ isAllSellZero ? '🔴 涨停 — 无卖盘' : '卖盘 ASK' }}
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
              {{ isAllBuyZero ? '🟢 跌停 — 无买盘' : '买盘 BID' }}
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
          <pre class="raw-json">{{ JSON.stringify(selectedStock, null, 2) }}</pre>
        </details>
      </template>
    </main>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { stockApi } from '@/api/request'

const allStocks = ref([])
const loading = ref(true)
const searchText = ref('')
const currentPage = ref(1)
const pageSize = 25
const selectedStock = ref(null)
const selectedCode = computed(() => selectedStock.value?.code ?? null)

// --- Filter & Paginate ---
const filteredList = computed(() => {
  const kw = searchText.value.trim().toLowerCase()
  if (!kw) return allStocks.value
  return allStocks.value.filter(s => s.code && s.code.toLowerCase().includes(kw))
})

const totalPages = computed(() => Math.max(1, Math.ceil(filteredList.value.length / pageSize)))

const paginatedList = computed(() => {
  const start = (currentPage.value - 1) * pageSize
  return filteredList.value.slice(start, start + pageSize)
})

const indexOffset = computed(() => (currentPage.value - 1) * pageSize + 1)

// --- Formats ---
function fmt(v) {
  if (v == null || v === '') return '--'
  return Number(v).toFixed(2)
}

// 涨停跌停显示逻辑
function fmtBidAsk(r, side) {
  const b = Number(r.bid), a = Number(r.ask)
  if (side === 'bid') {
    if (b > 0) return b.toFixed(2)
    if (a > 0 && b === 0) return '跌停'
    return '停牌'
  }
  // ask side
  if (a > 0) return a.toFixed(2)
  if (b > 0 && a === 0) return '涨停'
  return '停牌'
}
function fmtP(v) {
  if (v == null) return '--'
  return Number(v).toFixed(2)
}
function fmtV(v) {
  if (v == null) return '--'
  return (Number(v) / 100).toFixed(0)
}
function spread(r) {
  if (r.bid == null || r.ask == null) return '--'
  const b = Number(r.bid), a = Number(r.ask)
  if (b > 0 && a === 0) return '涨停'
  if (a > 0 && b === 0) return '跌停'
  if (b === 0 && a === 0) return '停牌'
  return (a - b).toFixed(3)
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

// --- Selection ---
function selectStock(row) {
  selectedStock.value = row
}

// --- Depth levels ---
const sellLevels = computed(() => {
  if (!selectedStock.value) return []
  const levels = []
  for (let i = 1; i <= 5; i++) {
    const p = selectedStock.value['s' + i + '_p']
    const v = selectedStock.value['s' + i + '_v']
    if (p != null) levels.push({ price: parseFloat(p), vol: parseInt(v) || 0 })
  }
  return levels
})

const buyLevels = computed(() => {
  if (!selectedStock.value) return []
  const levels = []
  for (let i = 1; i <= 5; i++) {
    const p = selectedStock.value['b' + i + '_p']
    const v = selectedStock.value['b' + i + '_v']
    if (p != null) levels.push({ price: parseFloat(p), vol: parseInt(v) || 0 })
  }
  return levels
})

const spreadVal = computed(() => {
  const s = selectedStock.value
  if (!s || s.bid == null || s.ask == null) return '--'
  const b = Number(s.bid), a = Number(s.ask)
  if (b > 0 && a === 0) return '涨停'
  if (a > 0 && b === 0) return '跌停'
  if (b === 0 && a === 0) return '停牌'
  return (a - b).toFixed(3)
})

// 涨停/跌停检测
const isAllSellZero = computed(() =>
  sellLevels.value.length > 0 && sellLevels.value.every(l => l.price === 0)
)
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

// --- Placeholder stats ---
const statusSummary = computed(() => {
  let up = 0, down = 0
  for (const s of allStocks.value) {
    if (s.status === '00') up++
    else if (s.status === '-2') down++
  }
  return { up, down }
})

// --- Init ---
onMounted(async () => {
  try {
    const data = await stockApi.getStockList() || []
    allStocks.value = data
    // Auto-select first stock
    if (data.length > 0) {
      selectedStock.value = data[0]
    }
  } catch (e) {
    console.error('Failed to fetch stock list:', e)
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
/* =============================================
   Terminal Split-Panel Layout
   ============================================= */
.terminal-page {
  display: flex;
  height: 100vh;
  width: 100%;
  background: #0A0C10;
  color: #E3E7EC;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  overflow: hidden;
}

/* ---- LEFT PANEL ---- */
.list-panel {
  width: 35%;
  min-width: 340px;
  max-width: 460px;
  display: flex;
  flex-direction: column;
  border-right: 1px solid #2D3340;
  background: #0A0C10;
}

.panel-topbar {
  padding: 16px 16px 12px;
  border-bottom: 1px solid #2D3340;
  flex-shrink: 0;
}

.topbar-title {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.title-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #3FB950;
  box-shadow: 0 0 6px rgba(63, 185, 80, 0.5);
}

.topbar-title h2 {
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.08em;
  color: #8892A0;
  margin: 0;
  flex: 1;
}

.count-chip {
  font-size: 11px;
  color: #636D7E;
  background: #13161D;
  padding: 2px 8px;
  border-radius: 3px;
  font-family: 'Fira Code', 'Cascadia Code', 'Consolas', monospace;
}

.topbar-search {
  width: 100%;
}

.search-prompt {
  color: #58A6FF;
  font-family: 'Fira Code', 'Cascadia Code', 'Consolas', monospace;
  font-size: 13px;
  font-weight: 600;
}

/* List body */
.list-body {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
}

.list-body::-webkit-scrollbar {
  width: 4px;
}

.list-body::-webkit-scrollbar-thumb {
  background: #2D3340;
  border-radius: 2px;
}

.list-row {
  display: flex;
  align-items: center;
  height: 36px;
  padding: 0 16px;
  gap: 10px;
  border-bottom: 1px solid rgba(45, 51, 64, 0.4);
  cursor: pointer;
  transition: background 0.08s ease;
  font-family: 'Fira Code', 'Cascadia Code', 'Consolas', monospace;
  font-size: 12px;
}

.list-row:hover {
  background: #13161D;
}

.list-row.selected {
  background: #1A1E27;
  border-left: 2px solid #58A6FF;
  padding-left: 14px;
}

.row-idx {
  width: 28px;
  color: #4D5563;
  font-size: 11px;
  flex-shrink: 0;
  text-align: right;
}

.row-code {
  flex: 1;
  font-weight: 600;
  color: #C9D1D9;
  letter-spacing: 0.02em;
}

.row-bid, .row-ask {
  width: 68px;
  text-align: right;
  font-weight: 500;
  font-size: 11px;
}

.row-bid.green { color: #3FB950; }
.row-ask.red { color: #F85149; }

.row-spread {
  width: 52px;
  text-align: right;
  color: #636D7E;
  font-size: 10px;
}

/* Loading / Empty */
.panel-loading {
  padding: 24px 16px;
}

.panel-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #636D7E;
  font-size: 13px;
}

.empty-icon {
  width: 32px;
  height: 32px;
  border: 1px solid #D29922;
  color: #D29922;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  font-weight: 700;
  font-size: 16px;
}

/* Pagination */
.panel-footer {
  padding: 10px 16px;
  border-top: 1px solid #2D3340;
  flex-shrink: 0;
}

.pagination-row {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
}

.pg-btn {
  background: #13161D;
  border: 1px solid #2D3340;
  color: #8892A0;
  width: 28px;
  height: 28px;
  border-radius: 3px;
  cursor: pointer;
  font-size: 13px;
  font-family: 'Fira Code', monospace;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.1s;
}

.pg-btn:hover:not(:disabled) {
  border-color: #58A6FF;
  color: #58A6FF;
}

.pg-btn:disabled {
  opacity: 0.3;
  cursor: default;
}

.pg-info {
  font-size: 11px;
  color: #636D7E;
  font-family: 'Fira Code', monospace;
  min-width: 60px;
  text-align: center;
}

/* ---- RIGHT PANEL ---- */
.detail-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
  padding: 24px 32px;
  background: #0D1117;
}

.detail-panel::-webkit-scrollbar {
  width: 4px;
}

.detail-panel::-webkit-scrollbar-thumb {
  background: #2D3340;
  border-radius: 2px;
}

/* Placeholder */
.detail-placeholder {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
}

.placeholder-icon {
  font-size: 48px;
  color: #2D3340;
}

.placeholder-title {
  font-size: 18px;
  font-weight: 600;
  color: #636D7E;
  letter-spacing: 0.06em;
  margin: 0;
}

.placeholder-hint {
  font-size: 13px;
  color: #4D5563;
  text-align: center;
  line-height: 1.6;
  margin: 0;
}

.placeholder-stats {
  display: flex;
  gap: 24px;
  margin-top: 16px;
}

.ph-stat {
  text-align: center;
}

.ph-val {
  display: block;
  font-size: 22px;
  font-weight: 700;
  color: #8892A0;
  font-family: 'Fira Code', monospace;
}

.ph-lbl {
  font-size: 11px;
  color: #4D5563;
  letter-spacing: 0.04em;
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

.hero-code {
  font-size: 32px;
  font-weight: 700;
  font-family: 'Fira Code', 'Cascadia Code', 'Consolas', monospace;
  color: #E3E7EC;
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

/* ---- Responsive ---- */
@media (max-width: 860px) {
  .terminal-page {
    flex-direction: column;
  }

  .list-panel {
    width: 100%;
    max-width: 100%;
    height: 45vh;
    border-right: none;
    border-bottom: 1px solid #2D3340;
  }

  .detail-panel {
    padding: 16px;
  }

  .depth-ladder {
    grid-template-columns: 1fr;
  }

  .ba-strip {
    grid-template-columns: 1fr;
  }
}
</style>

<!-- =============================================
     Global style overrides for Element Plus inside this view
     ============================================= -->
<style>
.terminal-page .el-input--small {
  --el-input-bg-color: #13161D;
  --el-input-border-color: #2D3340;
  --el-input-hover-border-color: #58A6FF;
  --el-input-focus-border-color: #58A6FF;
  --el-input-text-color: #C9D1D9;
  --el-input-placeholder-color: #4D5563;
  font-family: 'Fira Code', 'Consolas', monospace;
  font-size: 12px;
}

.terminal-page .el-input__wrapper {
  background: #13161D !important;
  border-color: #2D3340 !important;
  box-shadow: none !important;
  border-radius: 4px !important;
}

.terminal-page .el-input__inner {
  color: #C9D1D9 !important;
  font-family: 'Fira Code', 'Consolas', monospace !important;
  font-size: 12px !important;
}

.terminal-page .el-input__inner::placeholder {
  color: #4D5563 !important;
}

.terminal-page .el-skeleton {
  --el-skeleton-color: #1A1E27;
}

.terminal-page .el-skeleton__item {
  background: #1A1E27 !important;
}
</style>
