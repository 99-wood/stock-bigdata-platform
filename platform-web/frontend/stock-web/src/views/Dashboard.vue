<template>
  <div class="dashboard">
    <!-- Header -->
    <header class="dashboard-header">
      <h1 class="app-title">股票大数据平台</h1>
      <div class="header-left-actions">
        <el-button size="small" @click="$router.push('/stocks')">
          <el-icon><List /></el-icon>
          个股列表
        </el-button>
      </div>
      <div class="header-search">
        <el-select
          v-model="searchCode"
          filterable
          remote
          clearable
          reserve-keyword
          :remote-method="onSearch"
          :loading="searchLoading"
          placeholder="输入股票代码或名称搜索..."
          size="default"
          popper-class="stock-search-dropdown"
          @change="onSearchSelect"
        >
          <el-option
            v-for="item in searchOptions"
            :key="item.code"
            :label="`${item.name} (${item.code})`"
            :value="item.code"
          >
            <div class="search-option">
              <span class="opt-name">{{ item.name }}</span>
              <span class="opt-code">{{ item.code }}</span>
              <span class="opt-price">{{ item.price?.toFixed(2) }}</span>
            </div>
          </el-option>
        </el-select>
      </div>
      <div class="header-right">
        <el-tag :type="store.wsConnected ? 'success' : 'danger'" size="small" effect="dark">
          {{ store.wsConnected ? '实时连接' : '未连接' }}
        </el-tag>
        <el-button size="small" @click="refreshAll" :loading="store.loading">
          <el-icon><Refresh /></el-icon>
          刷新
        </el-button>
      </div>
    </header>

    <!-- Unified 4-column grid: summary card + rank panel per column -->
    <div class="unified-grid">
      <!-- Col 1: 上涨家数 → 涨幅榜 -->
      <div class="grid-col">
        <div class="summary-card up">
          <div class="card-label">上涨家数</div>
          <div class="card-value up-color">
            <el-icon><CaretTop /></el-icon>
            {{ fmtNum(store.marketSummary.upCount) }}
          </div>
          <div class="card-ratio">占比 {{ store.upRatio }}%</div>
        </div>
        <RankPanel title="📈 涨幅榜 Top 20" :data="store.topUp" type="up" @select="onStockSelect" />
      </div>

      <!-- Col 2: 下跌家数 → 跌幅榜 -->
      <div class="grid-col">
        <div class="summary-card down">
          <div class="card-label">下跌家数</div>
          <div class="card-value down-color">
            <el-icon><CaretBottom /></el-icon>
            {{ fmtNum(store.marketSummary.downCount) }}
          </div>
          <div class="card-ratio">占比 {{ store.downRatio }}%</div>
        </div>
        <RankPanel title="📉 跌幅榜 Top 20" :data="store.topDown" type="down" @select="onStockSelect" />
      </div>

      <!-- Col 3: 平盘家数 → 成交额榜 -->
      <div class="grid-col">
        <div class="summary-card flat">
          <div class="card-label">平盘家数</div>
          <div class="card-value flat-color">{{ fmtNum(store.marketSummary.flatCount) }}</div>
          <div class="card-ratio">占比 {{ store.flatRatio }}%</div>
        </div>
        <RankPanel title="💰 成交额榜 Top 20" :data="store.topAmount" type="amount" @select="onStockSelect" />
      </div>

      <!-- Col 4: 平均涨跌幅 → 量化评分榜 -->
      <div class="grid-col">
        <div class="summary-card" :class="avgClass">
          <div class="card-label">平均涨跌幅</div>
          <div class="card-value" :class="avgClass + '-color'">{{ fmtPct(store.marketSummary.avgChangePct) }}</div>
          <div class="card-ratio">&nbsp;</div>
        </div>
        <RankPanel title="🎯 量化评分榜 Top 20" :data="store.topAmount.slice(0,10)" type="quant" @select="onStockSelect" />
      </div>
    </div>

    <!-- Alert Ticker -->
    <div class="alert-section">
      <AlertTicker :alerts="store.alerts" />
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, List, CaretTop, CaretBottom } from '@element-plus/icons-vue'
import { useStockStore } from '@/stores/stock'
import { connectWebSocket, disconnectWebSocket } from '@/api/websocket'
import { stockApi } from '@/api/request'
import RankPanel from '@/components/RankPanel.vue'
import AlertTicker from '@/components/AlertTicker.vue'

const store = useStockStore()
const router = useRouter()

// ---- Helpers ----
function fmtNum(n) {
  if (n == null) return '--'
  return n.toLocaleString()
}
function fmtPct(v) {
  if (v == null) return '--'
  const sign = v > 0 ? '+' : ''
  return `${sign}${v.toFixed(2)}%`
}
const avgClass = computed(() => {
  const v = store.marketSummary.avgChangePct
  if (v > 0) return 'up'
  if (v < 0) return 'down'
  return 'flat'
})

// ---- Search ----
const searchCode = ref('')
const searchOptions = ref([])
const searchLoading = ref(false)

async function onSearch(keyword) {
  if (!keyword || keyword.trim().length < 1) {
    searchOptions.value = []
    return
  }
  searchLoading.value = true
  try {
    const list = await stockApi.getStockList(keyword.trim())
    searchOptions.value = (list || []).slice(0, 50)
  } catch (e) {
    console.error('Search error:', e)
    searchOptions.value = []
  } finally {
    searchLoading.value = false
  }
}

function onSearchSelect(code) {
  if (code) {
    router.push(`/stock/${code}`)
  }
}

// ---- Stock Select ----
function onStockSelect(code) {
  router.push(`/stock/${code}`)
}

// ---- Refresh ----
async function refreshAll() {
  await store.fetchAll()
  ElMessage.success('数据已刷新')
}

onMounted(() => {
  store.fetchAll()
  connectWebSocket({
    onConnected: () => { store.wsConnected = true },
    onMarket: (data) => { store.updateMarketSummary(data) },
    onRankUp: (data) => { store.updateTopUp(data) },
    onRankDown: (data) => { store.updateTopDown(data) },
    onRankAmount: (data) => { store.updateTopAmount(data) },
    onError: () => { store.wsConnected = false }
  })
})

onUnmounted(() => {
  disconnectWebSocket()
  store.wsConnected = false
})
</script>

<style scoped>
.dashboard {
  max-width: 1600px;
  margin: 0 auto;
  padding: 16px 24px;
  min-height: 100vh;
}

.dashboard-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 0;
  margin-bottom: 16px;
  border-bottom: 1px solid #303a5c;
  gap: 16px;
}

.app-title {
  font-size: 26px;
  font-weight: 700;
  background: linear-gradient(90deg, #409eff, #67c23a);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
  flex-shrink: 0;
}

.header-search {
  flex: 1;
  max-width: 400px;
}

.header-left-actions {
  flex-shrink: 0;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
}

/* ---- Unified 4-Column Grid ---- */
.unified-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin: 16px 0 20px;
}

.grid-col {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* ---- Inline Summary Card ---- */
.summary-card {
  padding: 16px;
  border-radius: 6px;
  background: linear-gradient(135deg, #1a1f3a 0%, #252b48 100%);
  border: 1px solid #303a5c;
}

.summary-card.up   { border-top: 3px solid #e15241; }
.summary-card.down { border-top: 3px solid #3cb371; }
.summary-card.flat { border-top: 3px solid #909399; }

.card-label {
  font-size: 12px;
  color: #909399;
  margin-bottom: 6px;
}

.card-value {
  font-size: 24px;
  font-weight: 700;
  display: flex;
  align-items: center;
  gap: 4px;
}

.card-value.up-color   { color: #e15241; }
.card-value.down-color { color: #3cb371; }
.card-value.flat-color { color: #909399; }

.card-ratio {
  font-size: 11px;
  color: #909399;
  margin-top: 2px;
}

.alert-section {
  margin-top: 20px;
  position: sticky;
  bottom: 0;
  z-index: 100;
}

/* Search option styling */
.search-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: 12px;
}

.opt-name { font-weight: 500; color: #303133; }
.opt-code { color: #909399; font-size: 12px; font-family: monospace; }
.opt-price { color: #409eff; font-weight: 600; margin-left: auto; }

@media (max-width: 1400px) {
  .unified-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 768px) {
  .unified-grid {
    grid-template-columns: 1fr;
  }
  .dashboard-header {
    flex-wrap: wrap;
  }
  .header-search {
    max-width: 100%;
    order: 3;
    width: 100%;
  }
}
</style>

<!-- Global style for dark-themed dropdown -->
<style>
.stock-search-dropdown {
  background: #1a1f3a !important;
  border: 1px solid #303a5c !important;
}
.stock-search-dropdown .el-select-dropdown__item {
  color: #e0e6ed !important;
}
.stock-search-dropdown .el-select-dropdown__item.hover,
.stock-search-dropdown .el-select-dropdown__item:hover {
  background: rgba(64, 158, 255, 0.15) !important;
}
.stock-search-dropdown .el-select-dropdown__item .opt-name,
.stock-search-dropdown .el-select-dropdown__item .opt-code {
  color: #e0e6ed !important;
}
.stock-search-dropdown .el-select-dropdown__item .opt-price {
  color: #409eff !important;
}
.stock-search-dropdown .el-popper__arrow::before {
  background: #1a1f3a !important;
  border: 1px solid #303a5c !important;
}
</style>
