<template>
  <div class="market-summary">
    <div class="summary-header">
      <h2 class="section-title">市场概览</h2>
      <span class="update-time" v-if="store.marketSummary.statTime">
        更新于 {{ store.marketSummary.statTime }}
      </span>
    </div>
    <div class="summary-cards">
      <div class="summary-card up">
        <div class="card-label">上涨家数</div>
        <div class="card-value">
          <el-icon><CaretTop /></el-icon>
          {{ formatNumber(store.marketSummary.upCount) }}
        </div>
        <div class="card-ratio">占比 {{ store.upRatio }}%</div>
      </div>
      <div class="summary-card down">
        <div class="card-label">下跌家数</div>
        <div class="card-value">
          <el-icon><CaretBottom /></el-icon>
          {{ formatNumber(store.marketSummary.downCount) }}
        </div>
        <div class="card-ratio">占比 {{ store.downRatio }}%</div>
      </div>
      <div class="summary-card flat">
        <div class="card-label">平盘家数</div>
        <div class="card-value">{{ formatNumber(store.marketSummary.flatCount) }}</div>
      </div>
      <div class="summary-card" :class="avgChangeClass">
        <div class="card-label">平均涨跌幅</div>
        <div class="card-value">{{ formatPct(store.marketSummary.avgChangePct) }}</div>
      </div>
    </div>
    <div class="summary-footer">
      <span>总成交量: {{ formatVolume(store.marketSummary.totalVolume) }} 手</span>
      <span>总成交额: {{ formatAmount(store.marketSummary.totalAmount) }} 万元</span>
      <span>有效股票: {{ store.marketSummary.totalStocks }} 只</span>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useStockStore } from '@/stores/stock'
import { CaretTop, CaretBottom } from '@element-plus/icons-vue'

const store = useStockStore()

const avgChangeClass = computed(() => {
  const v = store.marketSummary.avgChangePct
  if (v > 0) return 'up'
  if (v < 0) return 'down'
  return 'flat'
})

function formatNumber(n) {
  if (n == null) return '--'
  return n.toLocaleString()
}

function formatPct(v) {
  if (v == null) return '--'
  const sign = v > 0 ? '+' : ''
  return `${sign}${v.toFixed(2)}%`
}

function formatVolume(v) {
  if (v == null) return '--'
  return v.toLocaleString()
}

function formatAmount(v) {
  if (v == null) return '--'
  return v.toLocaleString()
}
</script>

<style scoped>
.market-summary {
  /* no extra padding — aligns with rank-grid edges */
}

.summary-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.section-title {
  font-size: 20px;
  font-weight: 600;
  color: #e0e6ed;
  border-left: 3px solid #409eff;
  padding-left: 12px;
}

.update-time {
  font-size: 13px;
  color: #909399;
}

.summary-cards {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
}

.summary-card {
  flex: 1;
  min-width: 180px;
  padding: 20px;
  border-radius: 8px;
  background: linear-gradient(135deg, #1a1f3a 0%, #252b48 100%);
  border: 1px solid #303a5c;
}

.summary-card.up {
  border-top: 3px solid #e15241;
}
.summary-card.up .card-value {
  color: #e15241;
}

.summary-card.down {
  border-top: 3px solid #3cb371;
}
.summary-card.down .card-value {
  color: #3cb371;
}

.summary-card.flat {
  border-top: 3px solid #909399;
}
.summary-card.flat .card-value {
  color: #909399;
}

.card-label {
  font-size: 13px;
  color: #909399;
  margin-bottom: 8px;
}

.card-value {
  font-size: 28px;
  font-weight: 700;
  display: flex;
  align-items: center;
  gap: 4px;
}

.card-ratio {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

.summary-footer {
  display: flex;
  gap: 32px;
  margin-top: 16px;
  padding: 12px 16px;
  background: rgba(255, 255, 255, 0.03);
  border-radius: 6px;
  font-size: 13px;
  color: #a0a7b8;
  flex-wrap: wrap;
}
</style>
