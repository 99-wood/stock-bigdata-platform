<template>
  <div class="page">
    <header class="topbar">
      <button class="tb-btn" @click="router.back()"><el-icon><ArrowLeft /></el-icon></button>
      <span class="tb-div"></span>
      <router-link to="/" class="tb-btn"><el-icon><DataBoard /></el-icon><span>大盘</span></router-link>
      <span class="tb-div"></span>
      <router-link to="/stocks" class="tb-btn"><el-icon><List /></el-icon><span>扫描</span></router-link>
      <span class="tb-div"></span>
      <span class="tb-label">STOCK DETAIL</span>
    </header>

    <div v-if="loading" class="loading"><el-skeleton :rows="6" animated /></div>

    <div v-else-if="!stock" class="not-found">
      <div class="nf-icon">!</div>
      <p class="nf-title">股票未找到</p>
      <p class="nf-hint">该股票代码暂无实时行情数据</p>
    </div>

    <StockDetailPanel v-else :stock="stock" />
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, DataBoard, List } from '@element-plus/icons-vue'
import { stockApi } from '@/api/request'
import StockDetailPanel from '@/components/StockDetailPanel.vue'

const route = useRoute(); const router = useRouter()
const stock = ref(null); const loading = ref(true)

async function fetchStock(code) {
  loading.value = true
  try { stock.value = await stockApi.getStockDetail(code) }
  catch { stock.value = null }
  finally { loading.value = false }
}

onMounted(() => fetchStock(route.params.code))
watch(() => route.params.code, c => { if (c) fetchStock(c) })
</script>

<style scoped>
.page {
  max-width: 960px; margin: 0 auto; padding: 20px 24px 48px;
  min-height: 100vh; background: var(--bg-root);
}

.topbar {
  display: flex; align-items: center; gap: 10px;
  padding-bottom: 14px; margin-bottom: 20px;
  border-bottom: 1px solid var(--border-subtle);
}

.tb-btn {
  display: flex; align-items: center; gap: 5px;
  background: var(--bg-elevated); border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md); color: var(--text-secondary);
  cursor: pointer; font-size: 12px; font-weight: 500;
  padding: 6px 12px; text-decoration: none; font-family: inherit;
  transition: all .15s;
}
.tb-btn:hover { border-color: var(--accent); color: var(--accent); background: var(--accent-bg) }

.tb-div { width: 1px; height: 16px; background: var(--border-default) }

.tb-label { font-size: 10px; font-weight: 600; color: var(--text-muted); letter-spacing: .1em }

.loading { padding: 40px 0 }

.not-found {
  display: flex; flex-direction: column; align-items: center;
  justify-content: center; padding: 100px 0; gap: 8px;
}
.nf-icon {
  width: 44px; height: 44px; border: 1px solid var(--stock-warn);
  color: var(--stock-warn); display: flex; align-items: center;
  justify-content: center; border-radius: 50%; font-weight: 700; font-size: 20px;
  margin-bottom: 8px;
}
.nf-title { font-size: 17px; font-weight: 600; color: var(--text-secondary) }
.nf-hint  { font-size: 13px; color: var(--text-muted) }
</style>
