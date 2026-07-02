<template>
  <span class="trade-time" v-if="store.tradeTime">
    <el-icon><Clock /></el-icon>
    行情时间 {{ store.tradeTime }}
  </span>
  <span class="trade-time no-data" v-else>
    等待行情数据...
  </span>
</template>

<script setup>
import { Clock } from '@element-plus/icons-vue'
import { useStockStore } from '@/stores/stock'

const store = useStockStore()
</script>

<style scoped>
.trade-time {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: #e0b040;
  font-weight: 500;
}

.trade-time.no-data {
  color: #909399;
}
</style>
