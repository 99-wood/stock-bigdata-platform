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

    <!-- Market Summary -->
    <MarketSummary />

    <!-- Rank Panels -->
    <div class="rank-grid">
      <RankPanel title="📈 涨幅榜 Top 20" :data="store.topUp" type="up" @select="onStockSelect" />
      <RankPanel title="📉 跌幅榜 Top 20" :data="store.topDown" type="down" @select="onStockSelect" />
      <RankPanel title="💰 成交额榜 Top 20" :data="store.topAmount" type="amount" @select="onStockSelect" />
      <RankPanel title="🎯 量化评分榜 Top 20" :data="store.topAmount.slice(0,10)" type="quant" @select="onStockSelect" />
    </div>

    <!-- Alert Ticker -->
    <div class="alert-section">
      <AlertTicker :alerts="store.alerts" />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, List } from '@element-plus/icons-vue'
import { useStockStore } from '@/stores/stock'
import { connectWebSocket, disconnectWebSocket } from '@/api/websocket'
import { stockApi } from '@/api/request'
import MarketSummary from '@/components/MarketSummary.vue'
import RankPanel from '@/components/RankPanel.vue'
import AlertTicker from '@/components/AlertTicker.vue'

const store = useStockStore()
const router = useRouter()

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

.rank-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  margin: 20px 0;
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

@media (max-width: 1200px) {
  .rank-grid {
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
