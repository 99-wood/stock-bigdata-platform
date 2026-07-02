<template>
  <div class="rank-panel">
    <div class="panel-header">
      <h3 class="panel-title">{{ title }}</h3>
      <span class="panel-count">{{ data.length }} 只</span>
    </div>
    <div class="panel-body">
      <div
        v-for="(item, index) in data"
        :key="item.code"
        class="rank-row"
        @click="$emit('select', item.code)"
      >
        <span class="rank-num" :class="getRankClass(index)">{{ index + 1 }}</span>
        <div class="stock-info">
          <span class="stock-code-only">{{ item.code }}</span>
        </div>
        <div class="stock-data">
          <span class="stock-bid">买 {{ fmt(item.bid) }}</span>
          <span class="stock-ask">卖 {{ fmt(item.ask) }}</span>
        </div>
      </div>
      <div v-if="data.length === 0" class="empty-state">
        暂无数据
      </div>
    </div>
  </div>
</template>

<script setup>
defineProps({
  title: { type: String, required: true },
  data: { type: Array, default: () => [] },
  type: { type: String, default: 'up' }
})

defineEmits(['select'])

function getRankClass(index) {
  if (index === 0) return 'top1'
  if (index === 1) return 'top2'
  if (index === 2) return 'top3'
  return ''
}

function fmt(v) {
  if (v == null) return '--'
  return v.toFixed(2)
}
</script>

<style scoped>
.rank-panel {
  background: linear-gradient(135deg, #1a1f3a 0%, #252b48 100%);
  border: 1px solid #303a5c;
  border-radius: 8px;
  overflow: hidden;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 16px;
  border-bottom: 1px solid #303a5c;
  background: rgba(64, 158, 255, 0.05);
}

.panel-title {
  font-size: 16px;
  font-weight: 600;
  color: #e0e6ed;
}

.panel-count {
  font-size: 12px;
  color: #909399;
}

.panel-body {
  max-height: 480px;
  overflow-y: auto;
}

.rank-row {
  display: flex;
  align-items: center;
  padding: 10px 16px;
  border-bottom: 1px solid rgba(48, 58, 92, 0.5);
  cursor: pointer;
  transition: background 0.2s;
}

.rank-row:hover {
  background: rgba(64, 158, 255, 0.08);
}

.rank-num {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
  font-size: 13px;
  font-weight: 600;
  color: #909399;
  background: rgba(255, 255, 255, 0.05);
  margin-right: 12px;
  flex-shrink: 0;
}

.rank-num.top1 { background: #e15241; color: #fff; }
.rank-num.top2 { background: #e17341; color: #fff; }
.rank-num.top3 { background: #e1a041; color: #fff; }

.stock-info {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
  overflow: hidden;
}

.stock-code-only {
  font-size: 13px;
  color: #e0e6ed;
  font-family: 'Courier New', monospace;
}

.stock-data {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
  flex-shrink: 0;
  font-size: 12px;
}

.stock-bid { color: #3cb371; }
.stock-ask { color: #e15241; }

.empty-state {
  padding: 40px;
  text-align: center;
  color: #909399;
  font-size: 14px;
}
</style>
