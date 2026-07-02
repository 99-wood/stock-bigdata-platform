<template>
  <div class="stock-detail">
    <!-- Top Bar -->
    <header class="detail-header">
      <el-button @click="$router.back()" text size="small">
        <el-icon><ArrowLeft /></el-icon> 返回
      </el-button>
      <span class="header-divider">|</span>
      <el-button @click="$router.push('/dashboard')" text size="small">
        <el-icon><DataBoard /></el-icon> 大盘
      </el-button>
      <el-button @click="$router.push('/stocks')" text size="small">
        <el-icon><List /></el-icon> 列表
      </el-button>
    </header>

    <!-- Loading -->
    <div v-if="loading" class="loading-box"><el-skeleton :rows="6" animated /></div>

    <!-- Not Found -->
    <div v-else-if="!stock" class="empty-box">
      <el-result icon="warning" title="股票未找到" sub-title="该股票代码暂无实时五档行情"></el-result>
    </div>

    <!-- Stock Level-2 Depth -->
    <template v-else>
      <!-- Header -->
      <div class="stock-hero">
        <div class="hero-left">
          <h1 class="stock-code-hero">{{ stock.code }}</h1>
          <el-tag v-if="isLimitUp" size="small" type="danger" effect="dark">涨停</el-tag>
          <el-tag v-else-if="isLimitDown" size="small" type="success" effect="dark">跌停</el-tag>
          <el-tag v-else-if="stock.status === '00'" size="small" type="success" effect="dark">交易中</el-tag>
          <el-tag v-else size="small" type="warning" effect="dark">{{ stock.status }}</el-tag>
        </div>
        <div class="hero-right">
          <span class="hero-time">{{ stock.trade_date }} {{ stock.trade_time }}</span>
        </div>
      </div>

      <!-- Bid / Ask Summary -->
      <div class="ba-summary">
        <div class="ba-card bid-card">
          <div class="ba-label">买一价</div>
          <div class="ba-value green">{{ fmt(stock.bid) }}</div>
        </div>
        <div class="ba-card spread-card">
          <div class="ba-label">价差</div>
          <div class="ba-value gray">{{ spreadVal }}</div>
        </div>
        <div class="ba-card ask-card">
          <div class="ba-label">卖一价</div>
          <div class="ba-value red">{{ fmt(stock.ask) }}</div>
        </div>
      </div>

      <!-- Depth Panels: Sell 5 | Buy 5 -->
      <div class="depth-row">
        <!-- Sell 5 Levels -->
        <div class="depth-panel sell-panel">
          <div class="panel-title-bar sell-title">
            {{ isLimitUp ? '🔴 涨停 — 无卖盘' : '📉 卖盘五档' }}
          </div>
          <div class="depth-table">
            <div class="depth-header">
              <span>档位</span><span>价格</span><span>手数</span>
            </div>
            <div v-for="(row, i) in sellLevels" :key="'s'+i" class="depth-row-ask">
              <span class="level-tag">卖{{ i + 1 }}</span>
              <span class="level-price">{{ isLimitUp ? '—' : fmtP(row.price) }}</span>
              <span class="level-vol">{{ isLimitUp ? '—' : fmtV(row.vol) }}</span>
            </div>
            <div v-if="sellLevels.length === 0" class="depth-empty">暂无卖盘</div>
          </div>
        </div>

        <!-- Buy 5 Levels -->
        <div class="depth-panel buy-panel">
          <div class="panel-title-bar buy-title">
            {{ isLimitDown ? '🟢 跌停 — 无买盘' : '📈 买盘五档' }}
          </div>
          <div class="depth-table">
            <div class="depth-header">
              <span>档位</span><span>价格</span><span>手数</span>
            </div>
            <div v-for="(row, i) in buyLevels" :key="'b'+i" class="depth-row-bid">
              <span class="level-tag">买{{ i + 1 }}</span>
              <span class="level-price">{{ isLimitDown ? '—' : fmtP(row.price) }}</span>
              <span class="level-vol">{{ isLimitDown ? '—' : fmtV(row.vol) }}</span>
            </div>
            <div v-if="buyLevels.length === 0" class="depth-empty">暂无买盘</div>
          </div>
        </div>
      </div>

      <!-- Raw Data (collapsible) -->
      <el-collapse class="raw-data">
        <el-collapse-item title="原始行情数据">
          <pre class="raw-json">{{ JSON.stringify(stock, null, 2) }}</pre>
        </el-collapse-item>
      </el-collapse>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { stockApi } from '@/api/request'
import { ArrowLeft, DataBoard, List } from '@element-plus/icons-vue'

const route = useRoute()
const stock = ref(null)
const loading = ref(true)

const sellLevels = computed(() => {
  if (!stock.value) return []
  const levels = []
  for (let i = 1; i <= 5; i++) {
    const p = stock.value['s' + i + '_p']
    const v = stock.value['s' + i + '_v']
    if (p != null) levels.push({ price: parseFloat(p), vol: parseInt(v) || 0 })
  }
  return levels
})

const buyLevels = computed(() => {
  if (!stock.value) return []
  const levels = []
  for (let i = 1; i <= 5; i++) {
    const p = stock.value['b' + i + '_p']
    const v = stock.value['b' + i + '_v']
    if (p != null) levels.push({ price: parseFloat(p), vol: parseInt(v) || 0 })
  }
  return levels
})

// 涨停检测：卖五档全部为 0，买盘有挂单
const isLimitUp = computed(() => {
  if (!stock.value) return false
  const bidOk = stock.value.bid > 0
  const allSellZero = sellLevels.value.length > 0 && sellLevels.value.every(l => l.price === 0)
  return bidOk && allSellZero
})
// 跌停检测：买五档全部为 0，卖盘有挂单
const isLimitDown = computed(() => {
  if (!stock.value) return false
  const askOk = stock.value.ask > 0
  const allBuyZero = buyLevels.value.length > 0 && buyLevels.value.every(l => l.price === 0)
  return askOk && allBuyZero
})

const spreadVal = computed(() => {
  if (!stock.value || stock.value.bid == null || stock.value.ask == null) return '--'
  if (isLimitUp.value) return '涨停'
  if (isLimitDown.value) return '跌停'
  return (stock.value.ask - stock.value.bid).toFixed(3)
})

function fmt(v) { return v != null ? v.toFixed(2) : '--' }
function fmtP(v) { return v != null ? v.toFixed(2) : '--' }
function fmtV(v) { return v != null ? (v / 100).toFixed(0) : '--' }

async function fetchStock(code) {
  loading.value = true
  try {
    stock.value = await stockApi.getStockDetail(code)
  } catch (e) {
    stock.value = null
  } finally {
    loading.value = false
  }
}

onMounted(() => fetchStock(route.params.code))
watch(() => route.params.code, c => { if (c) fetchStock(c) })
</script>

<style scoped>
.stock-detail { max-width: 900px; margin: 0 auto; padding: 16px 24px; min-height: 100vh; }

.detail-header { display: flex; align-items: center; gap: 8px; padding: 12px 0; margin-bottom: 20px; border-bottom: 1px solid #303a5c; }
.header-divider { color: #404660; }

.stock-hero { display: flex; justify-content: space-between; align-items: center; padding: 20px 24px; background: linear-gradient(135deg, #1a1f3a, #252b48); border: 1px solid #303a5c; border-radius: 10px; margin-bottom: 16px; }
.stock-code-hero { font-size: 28px; font-weight: 700; color: #e0e6ed; margin: 0; font-family: 'Courier New', monospace; }
.hero-left { display: flex; align-items: center; gap: 12px; }
.hero-right { color: #909399; font-size: 13px; }
.hero-time { font-size: 13px; }

.ba-summary { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 12px; margin-bottom: 16px; }
.ba-card { padding: 16px; text-align: center; border-radius: 8px; border: 1px solid #303a5c; background: linear-gradient(135deg, #1a1f3a, #252b48); }
.ba-label { font-size: 12px; color: #909399; margin-bottom: 6px; }
.ba-value { font-size: 24px; font-weight: 700; font-family: 'DIN Alternate', 'Courier New', monospace; }
.ba-value.green { color: #3cb371; }
.ba-value.red { color: #e15241; }
.ba-value.gray { color: #b0b8c0; font-size: 18px; }

.depth-row { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-bottom: 16px; }
.depth-panel { background: linear-gradient(135deg, #1a1f3a, #252b48); border: 1px solid #303a5c; border-radius: 8px; overflow: hidden; }
.panel-title-bar { padding: 10px 14px; font-size: 14px; font-weight: 600; border-bottom: 1px solid #303a5c; }
.sell-title { color: #e15241; background: rgba(225, 82, 65, 0.06); }
.buy-title { color: #3cb371; background: rgba(60, 179, 113, 0.06); }

.depth-table { padding: 0; }
.depth-header { display: flex; padding: 8px 14px; font-size: 12px; color: #606570; border-bottom: 1px solid rgba(48, 58, 92, 0.5); }
.depth-header span:nth-child(1) { width: 50px; }
.depth-header span:nth-child(2) { flex: 1; text-align: right; }
.depth-header span:nth-child(3) { width: 80px; text-align: right; }

.depth-row-bid, .depth-row-ask { display: flex; align-items: center; padding: 6px 14px; font-size: 13px; border-bottom: 1px solid rgba(48, 58, 92, 0.3); transition: background 0.15s; }
.depth-row-bid:hover { background: rgba(60, 179, 113, 0.06); }
.depth-row-ask:hover { background: rgba(225, 82, 65, 0.06); }
.depth-row-bid .level-price { color: #3cb371; }
.depth-row-ask .level-price { color: #e15241; }
.level-tag { width: 50px; font-size: 11px; color: #909399; }
.level-price { flex: 1; text-align: right; font-weight: 600; font-family: 'DIN Alternate', 'Courier New', monospace; }
.level-vol { width: 80px; text-align: right; color: #c0c6d0; }
.depth-empty { padding: 20px; text-align: center; color: #606570; font-size: 13px; }

.raw-data { margin-top: 16px; }
.raw-json { color: #909399; font-size: 12px; max-height: 300px; overflow-y: auto; background: #11152a; padding: 12px; border-radius: 6px; white-space: pre-wrap; word-break: break-all; }

.loading-box { padding: 40px 0; }
.empty-box { padding: 60px 0; }

@media (max-width: 768px) {
  .depth-row { grid-template-columns: 1fr; }
  .ba-summary { grid-template-columns: 1fr; }
}
</style>
