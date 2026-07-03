<template>
  <div class="stock-detail-page">
    <!-- Top Bar -->
    <header class="page-header">
      <button class="nav-btn" @click="router.back()" title="返回">
        <el-icon><ArrowLeft /></el-icon>
      </button>
      <span class="header-divider">|</span>
      <button class="nav-btn" @click="router.push('/dashboard')" title="大盘">
        <el-icon><DataBoard /></el-icon>
        大盘
      </button>
      <span class="header-divider">|</span>
      <button class="nav-btn" @click="router.push('/stocks')" title="列表">
        <el-icon><List /></el-icon>
        列表
      </button>
      <span class="header-divider">|</span>
      <span class="header-label">STOCK DETAIL</span>
    </header>

    <!-- Loading -->
    <div v-if="loading" class="loading-box"><el-skeleton :rows="6" animated /></div>

    <!-- Not Found -->
    <div v-else-if="!stock" class="empty-box">
      <div class="empty-icon">!</div>
      <p class="empty-title">股票未找到</p>
      <p class="empty-hint">该股票代码暂无实时五档行情</p>
    </div>

    <!-- Stock Detail Panel -->
    <StockDetailPanel v-else :stock="stock" />
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, DataBoard, List } from '@element-plus/icons-vue'
import { stockApi } from '@/api/request'
import StockDetailPanel from '@/components/StockDetailPanel.vue'

const route = useRoute()
const router = useRouter()
const stock = ref(null)
const loading = ref(true)

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
.stock-detail-page {
  max-width: 900px;
  margin: 0 auto;
  padding: 20px 24px;
  min-height: 100vh;
  background: #0D1117;
}

.page-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 0 16px;
  margin-bottom: 20px;
  border-bottom: 1px solid #2D3340;
}

.nav-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  background: none;
  border: 1px solid #2D3340;
  border-radius: 3px;
  color: #8892A0;
  cursor: pointer;
  font-size: 12px;
  padding: 5px 10px;
  transition: all 0.1s;
  font-family: inherit;
}

.nav-btn:hover {
  border-color: #58A6FF;
  color: #58A6FF;
}

.header-divider {
  color: #2D3340;
}

.header-label {
  font-size: 11px;
  color: #636D7E;
  letter-spacing: 0.08em;
  font-weight: 600;
}

.loading-box { padding: 40px 0; }

.empty-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px 0;
  gap: 8px;
}

.empty-icon {
  width: 36px;
  height: 36px;
  border: 1px solid #D29922;
  color: #D29922;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  font-weight: 700;
  font-size: 18px;
  margin-bottom: 8px;
}

.empty-title { font-size: 16px; color: #8892A0; margin: 0; }
.empty-hint { font-size: 13px; color: #636D7E; margin: 0; }
</style>
