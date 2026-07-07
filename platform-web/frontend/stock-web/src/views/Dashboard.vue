<template>
  <div class="dashboard">
    <!-- ═══ Sticky Header ═══ -->
    <header class="app-header">
      <div class="header-inner">
        <div class="header-brand">
          <router-link to="/" class="brand-link">
            <span class="brand-icon">◆</span>
            <h1 class="brand-title gradient-text">StockPulse</h1>
          </router-link>
        </div>

        <nav class="header-nav">
          <router-link to="/" class="nav-item" exact-active-class="nav-active">大盘</router-link>
          <router-link to="/stocks" class="nav-item" active-class="nav-active">扫描</router-link>
          <router-link to="/admin" class="nav-item" active-class="nav-active">管理</router-link>
        </nav>

        <div class="header-search">
          <el-select
            v-model="searchCode"
            filterable remote clearable reserve-keyword
            :remote-method="onSearch"
            :loading="searchLoading"
            placeholder="搜索股票..."
            size="default"
            popper-class="search-dropdown"
            @change="onSearchSelect"
          >
            <el-option v-for="s in searchOptions" :key="s.code" :label="`${s.name||s.code} (${s.code})`" :value="s.code">
              <div class="search-row">
                <span class="sr-name">{{ s.name || s.code }}</span>
                <span class="sr-code">{{ s.code }}</span>
                <span class="sr-price">{{ s.price?.toFixed(2) }}</span>
              </div>
            </el-option>
          </el-select>
        </div>

        <div class="header-status">
          <span class="live-dot" :class="{ on: store.wsConnected }"></span>
          <span class="live-text">{{ store.wsConnected ? 'LIVE' : 'OFFLINE' }}</span>
          <button class="icon-btn" @click="refreshAll" :disabled="store.loading" title="Refresh">
            <el-icon :class="{ spin: store.loading }"><Refresh /></el-icon>
          </button>
        </div>
      </div>
    </header>

    <div class="dashboard-content">
      <div class="content-left">
        <!-- ═══ Market Breadth ═══ -->
        <section class="kpi-strip">
          <div class="breadth-card">
          <!-- header with inline stats -->
          <div class="breadth-header">
            <span class="breadth-title">大盘</span>
            <div class="bh-stats">
              <span class="bhs-item"><em>{{ fmt(store.marketSummary.totalStocks) }}</em><span class="bhs-unit">家</span></span>
              <span class="bhs-sep"></span>
              <span class="bhs-item"><em>{{ volInfo.value }}</em><span class="bhs-unit">{{ volInfo.unit }}</span></span>
              <span class="bhs-sep"></span>
              <span class="bhs-item"><em>{{ amtInfo.value }}</em><span class="bhs-unit">{{ amtInfo.unit }}</span></span>
            </div>
            <span class="breadth-time" v-if="store.marketSummary.statTime">
              {{ store.marketSummary.statTime }}
            </span>
          </div>

          <!-- stacked proportion bar -->
          <div class="breadth-bar">
            <div class="bar-seg up"   :style="{ width: store.upRatio + '%' }"   v-if="store.upRatio > 0"></div>
            <div class="bar-seg down" :style="{ width: store.downRatio + '%' }" v-if="store.downRatio > 0"></div>
            <div class="bar-seg flat" :style="{ width: store.flatRatio + '%' }" v-if="store.flatRatio > 0"></div>
          </div>

          <!-- four compact metric columns (icon+value inline) -->
          <div class="breadth-metrics">
            <div class="bmetric up">
              <div class="bmetric-main">
                <span class="metric-arrow">▲</span>
                <span class="bmetric-val">{{ fmt(store.marketSummary.upCount) }}</span>
              </div>
              <span class="bmetric-sub">上涨 {{ store.upRatio }}%</span>
            </div>
            <div class="bmetric down">
              <div class="bmetric-main">
                <span class="metric-arrow">▼</span>
                <span class="bmetric-val">{{ fmt(store.marketSummary.downCount) }}</span>
              </div>
              <span class="bmetric-sub">下跌 {{ store.downRatio }}%</span>
            </div>
            <div class="bmetric flat">
              <div class="bmetric-main">
                <span class="metric-arrow">—</span>
                <span class="bmetric-val">{{ fmt(store.marketSummary.flatCount) }}</span>
              </div>
              <span class="bmetric-sub">平盘 {{ store.flatRatio }}%</span>
            </div>
            <div class="bmetric" :class="avgDirection">
              <div class="bmetric-main">
                <span class="bmetric-val" :class="avgDirection">{{ fmtPct(store.marketSummary.avgChangePct) }}</span>
              </div>
              <span class="bmetric-sub">平均涨跌幅</span>
            </div>
          </div>
        </div>

      </section>

      <!-- ═══ Tabbed Ranking (40% width) ═══ -->
      <section class="rank-block">
        <div class="rank-tabs">
          <button
            v-for="tab in rankTabs" :key="tab.key"
            class="rank-tab"
            :class="{ active: activeRank === tab.key }"
            @click="activeRank = tab.key"
          >
            <span class="tab-label">{{ tab.label }}</span>
          </button>
        </div>
        <div class="rank-body">
          <RankPanel
            :title="activeTab.label"
            :data="activeTab.data"
            :type="activeTab.type"
            plain
            @select="goStock"
          />
        </div>
      </section>
      </div><!-- /content-left -->

      <!-- ═══ Stock Detail Panel (right 60%) ═══ -->
      <div class="content-right" v-if="selectedStock">
        <button class="detail-close" @click="closeDetail" title="关闭">
          <el-icon><Close /></el-icon>
        </button>
        <StockDetailPanel :stock="selectedStock" />
      </div>
    </div>

    <!-- ═══ Glass Alert Ticker ═══ -->
    <AlertTicker :alerts="store.alerts" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, Close } from '@element-plus/icons-vue'
import { useStockStore } from '@/stores/stock'
import { connectWebSocket, disconnectWebSocket } from '@/api/websocket'
import { stockApi } from '@/api/request'
import RankPanel from '@/components/RankPanel.vue'
import StockDetailPanel from '@/components/StockDetailPanel.vue'
import AlertTicker from '@/components/AlertTicker.vue'

const store = useStockStore()
const router = useRouter()

function fmt(n) { return n != null ? n.toLocaleString() : '--' }
// 成交量: 股→手，自适应万手/亿手
const volInfo = computed(() => {
  const v = store.marketSummary.totalVolume
  if (v == null) return { value: '--', unit: '' }
  const hands = Number(v) / 100
  if (hands >= 100000000) return { value: (hands / 100000000).toFixed(1), unit: '亿手' }
  if (hands >= 10000) return { value: (hands / 10000).toFixed(1), unit: '万手' }
  return { value: hands.toLocaleString(), unit: '手' }
})
// 成交额: 元→自适应万/亿
const amtInfo = computed(() => {
  const v = store.marketSummary.totalAmount
  if (v == null) return { value: '--', unit: '' }
  if (v >= 100000000) return { value: (v / 100000000).toFixed(1), unit: '亿' }
  return { value: (v / 10000).toFixed(0), unit: '万' }
})
function fmtPct(v) {
  if (v == null) return '--'
  return (v > 0 ? '+' : '') + v.toFixed(2) + '%'
}

const avgDirection = computed(() => {
  const v = store.marketSummary.avgChangePct
  if (v > 0) return 'up'
  if (v < 0) return 'down'
  return ''
})

const avgSign = computed(() => {
  const v = store.marketSummary.avgChangePct
  if (v > 0) return '▲'
  if (v < 0) return '▼'
  return '—'
})

const topQuant = computed(() => store.topAmount.slice(0, 10))

const activeRank = ref('up')
const rankTabs = computed(() => [
  { key: 'up',    label: '涨幅榜',     data: store.topUp,    type: 'up' },
  { key: 'down',  label: '跌幅榜',     data: store.topDown,  type: 'down' },
  { key: 'amount',label: '成交额榜',   data: store.topAmount,type: 'amount' },
  { key: 'quant', label: '量化评分榜', data: topQuant.value, type: 'quant' }
])
const activeTab = computed(() => rankTabs.value.find(t => t.key === activeRank.value) || rankTabs.value[0])

const searchCode = ref('')
const searchOptions = ref([])
const searchLoading = ref(false)

async function onSearch(kw) {
  if (!kw || kw.trim().length < 1) { searchOptions.value = []; return }
  searchLoading.value = true
  try { searchOptions.value = ((await stockApi.getStockList(kw.trim())) || []).slice(0, 20) }
  catch { searchOptions.value = [] }
  finally { searchLoading.value = false }
}

function onSearchSelect(code) { if (code) goStock(code) }

const selectedStock = ref(null)
const detailLoading = ref(false)

async function goStock(code) {
  detailLoading.value = true
  try {
    selectedStock.value = await stockApi.getStockDetail(code)
  } catch {
    selectedStock.value = null
  } finally {
    detailLoading.value = false
  }
}

function closeDetail() {
  selectedStock.value = null
}

async function refreshAll() {
  await store.fetchAll()
  ElMessage.success('数据已刷新')
}

onMounted(() => {
  store.fetchAll()
  connectWebSocket({
    onConnected: () => { store.wsConnected = true },
    onMarket: (d) => { store.updateMarketSummary(d) },
    onRankUp: (d) => { store.updateTopUp(d) },
    onRankDown: (d) => { store.updateTopDown(d) },
    onRankAmount: (d) => { store.updateTopAmount(d) },
    onError: () => { store.wsConnected = false }
  })
})

onUnmounted(() => { disconnectWebSocket(); store.wsConnected = false })
</script>

<style scoped>
.dashboard {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: var(--bg-root);
  overflow: hidden;
}

/* ═══ Header ═══ */
.app-header {
  position: sticky;
  top: 0;
  z-index: 50;
  height: 44px;
  background: var(--bg-elevated);
  border-bottom: 1px solid var(--border-subtle);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
}

.header-inner {
  max-width: 1600px;
  margin: 0 auto;
  height: 100%;
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 0 24px;
}

.header-brand { flex-shrink: 0 }

.brand-link {
  display: flex;
  align-items: center;
  gap: 8px;
  text-decoration: none;
}

.brand-icon {
  font-size: 18px;
  color: var(--accent);
}

.brand-title {
  font-size: 16px;
  font-weight: 700;
  letter-spacing: -0.02em;
}

.header-nav {
  display: flex;
  gap: 4px;
  flex-shrink: 0;
}

.nav-item {
  padding: 6px 14px;
  border-radius: var(--radius-md);
  font-size: 13px;
  font-weight: 500;
  color: var(--text-secondary);
  text-decoration: none;
  transition: all .15s;
}

.nav-item:hover { color: var(--text-primary); background: var(--bg-hover) }
.nav-active { color: var(--accent); background: var(--accent-bg) }

.header-search { flex: 1; max-width: 340px }

.header-status {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.live-dot {
  width: 7px; height: 7px;
  border-radius: 50%;
  background: var(--text-muted);
  transition: background .3s, box-shadow .3s;
}
.live-dot.on { background: var(--stock-down); box-shadow: 0 0 6px rgba(63,185,80,.5) }

.live-text {
  font-size: 10px; font-weight: 600;
  letter-spacing: .08em; color: var(--text-muted);
}

.icon-btn {
  display: flex; align-items: center; justify-content: center;
  width: 32px; height: 32px;
  background: none; border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md); color: var(--text-secondary);
  cursor: pointer; font-size: 15px; transition: all .15s;
}
.icon-btn:hover { border-color: var(--accent); color: var(--accent); background: var(--accent-bg) }
.icon-btn:disabled { opacity: .3; cursor: default }

.spin { animation: rot 1s linear infinite }
@keyframes rot { to { transform: rotate(360deg) } }

/* ═══ Content — flex row: left 40% + right 60% ═══ */
.dashboard-content {
  flex: 1;
  width: 100%;
  margin: 0;
  padding: 0;
  display: flex;
  gap: 0;
  align-items: stretch;
  min-height: 0;
}

.content-left {
  width: 30%;
  flex-shrink: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0;
}

.content-right {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.detail-close {
  position: sticky;
  top: 0;
  float: right;
  z-index: 5;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px; height: 28px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  background: var(--bg-elevated);
  color: var(--text-secondary);
  cursor: pointer;
  font-size: 14px;
  transition: all .15s;
}
.detail-close:hover { border-color: var(--accent); color: var(--accent); background: var(--accent-bg) }

/* ═══ Market Breadth ═══ */
.kpi-strip {
  display: grid;
  grid-template-columns: 1fr;
  margin-bottom: 0;
}

/* --- breadth card --- */
.breadth-card {
  padding: 8px 0 10px 0;
  background: var(--bg-surface);
  border: 1px solid var(--border-subtle);
  border-radius: 0;
  display: flex;
  flex-direction: column;
}

.breadth-header {
  display: flex;
  align-items: baseline;
  padding-left: 12px;
  gap: 12px;
}
.breadth-title {
  font-size: 14px;
  font-weight: 700;
  color: var(--text-primary);
  letter-spacing: -0.01em;
  flex-shrink: 0;
}

/* inline stats between label and time */
.bh-stats {
  display: flex;
  align-items: baseline;
  gap: 6px;
  flex: 1;
}

.bhs-item {
  font-size: 10px;
  color: var(--text-secondary);
  white-space: nowrap;
}
.bhs-unit { font-size: 9px; color: var(--text-muted); margin-left: 1px }
.bhs-item em {
  font-style: normal;
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--text-primary);
  font-weight: 500;
  margin-right: 2px;
}

.bhs-sep {
  width: 1px;
  height: 10px;
  background: rgba(255,255,255,0.08);
  flex-shrink: 0;
  align-self: center;
}

.breadth-time {
  font-size: 11px;
  color: var(--accent);
  font-family: var(--font-mono);
  flex-shrink: 0;
}

/* --- stacked proportion bar --- */
.breadth-bar {
  display: flex;
  height: 4px;
  border-radius: 2px;
  overflow: hidden;
  background: rgba(255,255,255,0.06);
  margin: 8px 0;
  gap: 1px;
}

.bar-seg {
  height: 100%;
  transition: width .45s cubic-bezier(.4,0,.2,1);
}

.bar-seg.up   { background: var(--stock-up) }
.bar-seg.down { background: var(--stock-down) }
.bar-seg.flat { background: var(--text-muted) }

/* --- four metric columns (icon+value inline, sub below) --- */
.breadth-metrics {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 0;
  padding: 0 12px;
}

.bmetric {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  padding: 3px 4px;
  border-radius: 0;
  background: transparent;
}

.bmetric-main {
  display: flex;
  align-items: baseline;
  justify-content: center;
  gap: 3px;
}

.metric-arrow {
  font-size: 14px; font-weight: 700; line-height: 1; flex-shrink: 0;
}
.bmetric.up   .metric-arrow { color: var(--stock-up) }
.bmetric.down .metric-arrow { color: var(--stock-down) }
.bmetric.flat .metric-arrow,
.avgDirection:not(.up):not(.down) .metric-arrow { color: var(--text-muted) }

.bmetric-val {
  font-size: 18px;
  font-weight: 700;
  font-family: var(--font-mono);
  letter-spacing: -0.02em;
  line-height: 1.2;
}
.bmetric.up   .bmetric-val { color: var(--stock-up) }
.bmetric.down .bmetric-val { color: var(--stock-down) }
.bmetric.flat .bmetric-val { color: var(--text-secondary) }

.bmetric-sub {
  font-size: 10px;
  color: var(--text-secondary);
  margin-top: 1px;
  white-space: nowrap;
}

/* ═══ Tabbed Ranking Block ═══ */
.rank-block {
  flex: 1;
  width: 100%;
  background: var(--bg-surface);
  border: 1px solid var(--border-subtle);
  border-radius: 0;
  overflow: hidden;
  margin-bottom: 0;
}

/* --- tab bar --- */
.rank-tabs {
  display: flex;
  gap: 2px;
  padding: 0;
  background: rgba(255,255,255,0.02);
  border-bottom: 1px solid var(--border-subtle);
}

.rank-tab {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 5px 12px;
  border: none;
  border-radius: 0;
  background: transparent;
  color: var(--text-secondary);
  font-size: 13px;
  font-weight: 500;
  font-family: inherit;
  cursor: pointer;
  transition: background .18s, color .18s;
}
.rank-tab:hover { color: var(--text-primary); background: rgba(255,255,255,0.04) }

.rank-tab.active {
  background: var(--accent-bg);
  color: var(--accent);
}

.tab-label { white-space: nowrap }

.tab-badge {
  font-size: 10px;
  font-weight: 600;
  font-family: var(--font-mono);
  padding: 1px 6px;
  border-radius: var(--radius-full);
  background: rgba(255,255,255,0.06);
}
.rank-tab.active .tab-badge {
  background: var(--accent-bg-hover);
}

/* --- rank body --- */
.rank-body {
  max-height: 540px;
  overflow-y: auto;
}
.rank-body :deep(.panel-body) {
  max-height: none;
}

/* ═══ Search dropdown ═══ */
.search-row { display: flex; align-items: center; justify-content: space-between; width: 100%; gap: 12px }
.sr-name { font-weight: 500 }
.sr-code { color: var(--text-muted); font-size: 12px; font-family: var(--font-mono) }
.sr-price { color: var(--accent); font-weight: 600; margin-left: auto }

@media (max-width: 860px) {
  .dashboard-content { flex-direction: column }
  .content-left { width: 100% }
  .content-right { position: static; max-height: none; flex: none }
  .breadth-metrics { grid-template-columns: repeat(2, 1fr) }
  .rank-tabs { flex-wrap: wrap; gap: 4px }
  .rank-tab { padding: 5px 12px; font-size: 12px }
}
@media (max-width: 768px) {
  .header-nav { display: none }
  .header-search { max-width: 100% }
}
</style>
