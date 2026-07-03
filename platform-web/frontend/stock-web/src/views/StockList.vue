<template>
  <div class="terminal-page">
    <!-- ===== LEFT PANEL: Stock List ===== -->
    <aside class="list-panel">
      <div class="panel-topbar">
        <div class="topbar-title">
          <span class="title-dot"></span>
          <button class="back-btn" @click="router.push('/dashboard')" title="返回大盘">
            <el-icon><ArrowLeft /></el-icon>
          </button>
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
          <span class="row-name">{{ row.name || row.code }}</span>
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
      <StockDetailPanel v-else :stock="selectedStock" />
    </main>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft } from '@element-plus/icons-vue'
import { stockApi } from '@/api/request'
import StockDetailPanel from '@/components/StockDetailPanel.vue'
import { getStockColorClass } from '@/utils/stockColor'

const router = useRouter()

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
function codeClass(r) {
  return getStockColorClass(r)
}
function fmtBidAsk(r, side) {
  const b = Number(r.bid), a = Number(r.ask)
  if (side === 'bid') {
    if (b > 0) return b.toFixed(2)
    if (a > 0 && b === 0) return '跌停'
    return '停牌'
  }
  if (a > 0) return a.toFixed(2)
  if (b > 0 && a === 0) return '涨停'
  return '停牌'
}
function spread(r) {
  if (r.bid == null || r.ask == null) return '--'
  const b = Number(r.bid), a = Number(r.ask)
  if (b > 0 && a === 0) return '涨停'
  if (a > 0 && b === 0) return '跌停'
  if (b === 0 && a === 0) return '停牌'
  return (a - b).toFixed(3)
}

// --- Selection ---
function selectStock(row) {
  selectedStock.value = row
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
  flex-shrink: 0;
}

.back-btn {
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: none;
  border: 1px solid #2D3340;
  border-radius: 3px;
  color: #8892A0;
  cursor: pointer;
  flex-shrink: 0;
  padding: 0;
  transition: all 0.1s;
}

.back-btn:hover {
  border-color: #58A6FF;
  color: #58A6FF;
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
  scrollbar-width: thin;
  scrollbar-color: #2D3340 transparent;
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

.row-name {
  flex: 1;
  font-weight: 500;
  font-size: 12px;
  color: #C9D1D9;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
}

.row-code {
  width: 90px;
  flex-shrink: 0;
  font-weight: 600;
  font-size: 11px;
  letter-spacing: 0.02em;
  font-family: 'Fira Code', 'Cascadia Code', 'Consolas', monospace;
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
  scrollbar-width: thin;
  scrollbar-color: #2D3340 transparent;
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
