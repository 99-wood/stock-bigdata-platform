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
      <!-- ═══ KPI Strip ═══ -->
      <section class="kpi-strip">
        <div class="kpi-card">
          <span class="kpi-label">上涨家数</span>
          <span class="kpi-value up">
            <el-icon class="kpi-arrow"><CaretTop /></el-icon>
            {{ fmt(store.marketSummary.upCount) }}
          </span>
          <span class="kpi-sub">{{ store.upRatio }}%</span>
        </div>
        <div class="kpi-card">
          <span class="kpi-label">下跌家数</span>
          <span class="kpi-value down">
            <el-icon class="kpi-arrow"><CaretBottom /></el-icon>
            {{ fmt(store.marketSummary.downCount) }}
          </span>
          <span class="kpi-sub">{{ store.downRatio }}%</span>
        </div>
        <div class="kpi-card">
          <span class="kpi-label">平盘家数</span>
          <span class="kpi-value flat">{{ fmt(store.marketSummary.flatCount) }}</span>
          <span class="kpi-sub">{{ store.flatRatio }}%</span>
        </div>
        <div class="kpi-card" :class="avgDirection">
          <span class="kpi-label">平均涨跌幅</span>
          <span class="kpi-value" :class="avgDirection">{{ fmtPct(store.marketSummary.avgChangePct) }}</span>
        </div>
      </section>

      <!-- ═══ 4-Column Rank Grid ═══ -->
      <section class="rank-grid">
        <RankPanel title="涨幅榜" :data="store.topUp" type="up" @select="goStock" />
        <RankPanel title="跌幅榜" :data="store.topDown" type="down" @select="goStock" />
        <RankPanel title="成交额榜" :data="store.topAmount" type="amount" @select="goStock" />
        <RankPanel title="量化评分榜" :data="topQuant" type="quant" @select="goStock" />
      </section>

      <!-- ═══ Stats Footer ═══ -->
      <footer class="stats-bar" v-if="store.marketSummary.statTime">
        <div class="stat">
          <span class="stat-label">统计时间</span>
          <span class="stat-value accent">{{ store.marketSummary.statTime }}</span>
        </div>
        <div class="stat">
          <span class="stat-label">标的数量</span>
          <span class="stat-value">{{ fmt(store.marketSummary.totalStocks) }} 只</span>
        </div>
        <div class="stat">
          <span class="stat-label">总成交量</span>
          <span class="stat-value">{{ fmt(store.marketSummary.totalVolume) }} 手</span>
        </div>
        <div class="stat">
          <span class="stat-label">总成交额</span>
          <span class="stat-value">{{ fmt(store.marketSummary.totalAmount) }} 万元</span>
        </div>
      </footer>
    </div>

    <!-- ═══ Glass Alert Ticker ═══ -->
    <AlertTicker :alerts="store.alerts" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, CaretTop, CaretBottom } from '@element-plus/icons-vue'
import { useStockStore } from '@/stores/stock'
import { connectWebSocket, disconnectWebSocket } from '@/api/websocket'
import { stockApi } from '@/api/request'
import RankPanel from '@/components/RankPanel.vue'
import AlertTicker from '@/components/AlertTicker.vue'

const store = useStockStore()
const router = useRouter()

function fmt(n) { return n != null ? n.toLocaleString() : '--' }
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

const topQuant = computed(() => store.topAmount.slice(0, 10))

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
function goStock(code) { router.push(`/stock/${code}`) }

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
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background: var(--bg-root);
}

/* ═══ Header ═══ */
.app-header {
  position: sticky;
  top: 0;
  z-index: 50;
  height: 56px;
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

/* ═══ Content ═══ */
.dashboard-content {
  flex: 1;
  max-width: 1600px;
  width: 100%;
  margin: 0 auto;
  padding: 24px 24px 0;
}

/* ═══ KPI Strip ═══ */
.kpi-strip {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 18px;
}

.kpi-card {
  padding: 18px 20px;
  background: var(--bg-surface);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg);
  transition: border-color .2s;
}
.kpi-card:hover { border-color: var(--border-strong) }

.kpi-label { /* inherits global .kpi-label */ }

.kpi-value {
  font-size: 30px;
  font-weight: 700;
  font-family: var(--font-mono);
  letter-spacing: -0.03em;
  line-height: 1.15;
  display: flex;
  align-items: center;
  gap: 4px;
}
.kpi-value.up   { color: var(--stock-up) }
.kpi-value.down { color: var(--stock-down) }
.kpi-value.flat { color: var(--text-secondary) }

.kpi-arrow { font-size: 20px }

.kpi-sub {
  display: block;
  font-size: 11px;
  color: var(--text-muted);
  margin-top: 2px;
}

.kpi-card.up   { border-left: 2px solid var(--stock-up) }
.kpi-card.down { border-left: 2px solid var(--stock-down) }

/* ═══ Rank Grid ═══ */
.rank-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
  margin-bottom: 18px;
}

/* ═══ Stats Footer ═══ */
.stats-bar {
  display: flex;
  gap: 36px;
  padding: 14px 20px;
  background: var(--bg-surface);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg);
  margin-bottom: 24px;
  flex-wrap: wrap;
}

.stat { display: flex; flex-direction: column; gap: 2px }
.stat-label { font-size: 10px; font-weight: 600; color: var(--text-muted); text-transform: uppercase; letter-spacing: .08em }
.stat-value { font-size: 13px; font-weight: 500; color: var(--text-primary) }
.stat-value.accent { color: var(--accent); font-family: var(--font-mono); font-size: 12px }

/* ═══ Search dropdown ═══ */
.search-row { display: flex; align-items: center; justify-content: space-between; width: 100%; gap: 12px }
.sr-name { font-weight: 500 }
.sr-code { color: var(--text-muted); font-size: 12px; font-family: var(--font-mono) }
.sr-price { color: var(--accent); font-weight: 600; margin-left: auto }

@media (max-width: 1400px) {
  .kpi-strip, .rank-grid { grid-template-columns: repeat(2, 1fr) }
}
@media (max-width: 768px) {
  .kpi-strip, .rank-grid { grid-template-columns: 1fr }
  .header-nav { display: none }
  .header-search { max-width: 100% }
}
</style>
