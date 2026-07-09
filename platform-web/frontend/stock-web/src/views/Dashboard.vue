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
      <!-- ═══ Left Column ═══ -->
      <div class="col-left">
        <!-- Market Breadth (top half) -->
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

          <!-- trend sparkline -->
          <div ref="trendRef" class="trend-chart"></div>

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

      <!-- 涨幅榜 + 跌幅榜 (bottom half) -->
      <section class="rank-dual">
        <div class="rank-half">
          <div class="rank-label up">涨幅榜</div>
          <RankPanel :data="store.topUp" type="up" :plain="true" />
        </div>
        <div class="rank-half">
          <div class="rank-label down">跌幅榜</div>
          <RankPanel :data="store.topDown" type="down" :plain="true" />
        </div>
      </section>
      </div><!-- /col-left -->

      <!-- ═══ Right Column ═══ -->
      <div class="col-right">
        <!-- Treemaps (top half) -->
        <div class="right-top">
          <MarketTreemap :up-data="treemapUp" :down-data="treemapDown" />
        </div>
        <!-- Anomaly (bottom half) -->
        <div class="right-bottom">
          <AnomalyPanel />
        </div>
      </div><!-- /col-right -->
    </div>

    <!-- ═══ Glass Alert Ticker ═══ -->
    <AlertTicker :alerts="store.alerts" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import { useStockStore } from '@/stores/stock'
import { connectWebSocket, disconnectWebSocket } from '@/api/websocket'
import { stockApi, dashboardApi } from '@/api/request'
import RankPanel from '@/components/RankPanel.vue'
import AlertTicker from '@/components/AlertTicker.vue'
import MarketTreemap from '@/components/MarketTreemap.vue'
import AnomalyPanel from '@/components/AnomalyPanel.vue'

// ---- 大盘趋势图（面积=涨跌比 + 线=平均涨跌幅） ----
const trendRef = ref(null)
const trendPoints = ref([])
const MAX_TREND = 120
const lastWsPct = ref(null)
let trendChart = null

async function loadTrendHistory() {
  try {
    const rows = await dashboardApi.getSummaryHistory(60) || []
    if (!rows.length) return
    trendPoints.value = rows.map(r => ({
      time: (r.stat_time || '').substring(11, 16),
      pct: r.avg_change_pct ?? r.avgChangePct ?? 0,
      up: r.up_count ?? r.upCount ?? 0,
      down: r.down_count ?? r.downCount ?? 0,
      flat: r.flat_count ?? r.flatCount ?? 0,
      total: r.total_stocks ?? r.totalStocks ?? 1
    }))
    redrawTrend()
  } catch {}
}

function pushWsPoint(data) {
  const pct = Math.round((data.avg_change_pct ?? data.avgChangePct ?? 0) * 100) / 100
  if (pct === lastWsPct.value) return
  lastWsPct.value = pct
  const now = new Date()
  trendPoints.value.push({
    time: String(now.getHours()).padStart(2, '0') + ':' + String(now.getMinutes()).padStart(2, '0'),
    pct,
    up: data.up_count ?? data.upCount ?? 0,
    down: data.down_count ?? data.downCount ?? 0,
    flat: data.flat_count ?? data.flatCount ?? 0,
    total: data.total_stocks ?? data.totalStocks ?? 1
  })
  if (trendPoints.value.length > MAX_TREND) trendPoints.value.shift()
  redrawTrend()
}

function redrawTrend() {
  if (!trendChart || !trendPoints.value.length) return
  const times = trendPoints.value.map(p => p.time)
  const pcts = trendPoints.value.map(p => p.pct)
  const upRatio = trendPoints.value.map(p => p.total ? +(p.up / p.total * 100).toFixed(1) : 0)
  const downRatio = trendPoints.value.map(p => p.total ? +(p.down / p.total * 100).toFixed(1) : 0)

  const pts = trendPoints.value
  trendChart.setOption({
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'axis',
      backgroundColor: '#13161D', borderColor: '#2D3340',
      textStyle: { color: '#E3E7EC', fontSize: 11, fontFamily: "'Fira Code',monospace" },
      formatter: (params) => {
        const p = pts[params[0].dataIndex]
        return `<span style="color:#8B949E">${p.time}</span><br/>
          涨跌比 <span style="color:#FF495B">${p.up}</span>/<span style="color:#3FB950">${p.down}</span>/<span style="color:#636D7E">${p.flat}</span><br/>
          均价涨幅 <b>${(p.pct >= 0 ? '+' : '') + p.pct.toFixed(2)}%</b>`
      }
    },
    grid: { left: 0, right: 0, top: 4, bottom: 0 },
    xAxis: { type: 'category', data: times, show: false, boundaryGap: false },
    yAxis: [
      { type: 'value', show: false },    // 左轴：涨跌比柱状
      { type: 'value', show: false }     // 右轴：均价涨幅线
    ],
    series: [
      // 涨跌比堆叠面积（左轴）：上绿(跌) + 下红(涨)
      { type: 'line', data: upRatio, symbol: 'none', stack: 'breadth', yAxisIndex: 0,
        lineStyle: { width: 0 }, areaStyle: { color: 'rgba(255,73,91,0.40)' } },
      { type: 'line', data: downRatio, symbol: 'none', stack: 'breadth', yAxisIndex: 0,
        lineStyle: { width: 0 }, areaStyle: { color: 'rgba(63,185,80,0.40)' } },
      // 均价涨幅折线（右轴）
      { type: 'line', data: pcts, symbol: 'none', yAxisIndex: 1,
        lineStyle: { width: 1.5, color: '#58A6FF' } }
    ]
  })
}

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

function onSearchSelect(code) { if (code) router.push('/stock/' + code) }

// Treemap
const treemapUp = ref([]), treemapDown = ref([])
async function fetchTreemap() {
  try {
    const res = await dashboardApi.getTreemap()
    if (res) { treemapUp.value = res.up || []; treemapDown.value = res.down || [] }
  } catch {}
}
fetchTreemap()
setInterval(fetchTreemap, 30000)

async function refreshAll() {
  await store.fetchAll()
  ElMessage.success('数据已刷新')
}

onMounted(() => {
  store.fetchAll()
  nextTick(async () => {
    if (trendRef.value) {
      trendChart = echarts.init(trendRef.value, null, { renderer: 'canvas' })
      trendChart.setOption({
        backgroundColor: 'transparent',
        grid: { left: 0, right: 4, top: 2, bottom: 0 },
        xAxis: { type: 'category', data: [], show: false, boundaryGap: false },
        yAxis: { type: 'value', show: false },
        series: [{ type: 'line', data: [], symbol: 'none', lineStyle: { width: 1.5 } }]
      })
      await loadTrendHistory()
    }
  })
  window.addEventListener('resize', () => trendChart?.resize())
  connectWebSocket({
    onConnected: () => { store.wsConnected = true },
    onMarket: (d) => {
      store.updateMarketSummary(d)
      if (d != null) pushWsPoint(d)
    },
    onRankUp: (d) => { store.updateTopUp(d) },
    onRankDown: (d) => { store.updateTopDown(d) },
    onRankAmount: (d) => { store.updateTopAmount(d) },
    onError: () => { store.wsConnected = false }
  })
})

onUnmounted(() => {
  disconnectWebSocket()
  store.wsConnected = false
  trendChart?.dispose()
  trendChart = null
})
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

/* ═══ Content — left/right columns ═══ */
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

.col-left {
  width: 45%;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 0;
}

.col-right {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.right-top {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.right-bottom {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  border-top: 1px solid var(--border-subtle);
}

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

/* --- trend sparkline --- */
.trend-chart {
  height: 50px;
  margin: 0;
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

/* ═══ Rank Dual (涨幅+跌幅 side by side) ═══ */
.rank-dual {
  flex: 1;
  display: flex;
  gap: 0;
  min-height: 0;
  border: 1px solid var(--border-subtle);
  background: var(--bg-surface);
}

.rank-half {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  overflow: hidden;
}
.rank-half + .rank-half {
  border-left: 1px solid var(--border-subtle);
}

.rank-half > :deep(.panel) {
  flex: 1;
  border: none;
  background: transparent;
}

.rank-label {
  font-size: 10px; font-weight: 600;
  padding: 3px 8px;
  color: var(--text-muted);
  background: var(--accent-bg);
  letter-spacing: .04em;
  flex-shrink: 0;
  border-bottom: 1px solid var(--border-subtle);
}
.rank-label.up { color: var(--stock-up) }
.rank-label.down { color: var(--stock-down) }

/* ═══ Search dropdown ═══ */
.search-row { display: flex; align-items: center; justify-content: space-between; width: 100%; gap: 12px }
.sr-name { font-weight: 500 }
.sr-code { color: var(--text-muted); font-size: 12px; font-family: var(--font-mono) }
.sr-price { color: var(--accent); font-weight: 600; margin-left: auto }

@media (max-width: 860px) {
  .dashboard-content { flex-direction: column }
  .col-left { width: 100% }
  .col-right { flex: none; height: 60vh }
  .breadth-metrics { grid-template-columns: repeat(2, 1fr) }
}
@media (max-width: 768px) {
  .header-nav { display: none }
  .header-search { max-width: 100% }
}
</style>
