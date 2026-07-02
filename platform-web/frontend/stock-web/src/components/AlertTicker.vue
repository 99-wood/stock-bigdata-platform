<template>
  <div class="alert-ticker" v-if="alerts.length > 0">
    <div class="ticker-icon">
      <el-icon><Warning /></el-icon>
    </div>
    <div class="ticker-track">
      <div class="ticker-content" :style="{ animationDuration: duration + 's' }">
        <span
          v-for="(alert, index) in displayAlerts"
          :key="index"
          class="alert-item"
          :class="'alert-' + alert.alertType"
        >
          <span class="alert-tag">{{ getTypeLabel(alert.alertType) }}</span>
          <span class="alert-stock">{{ alert.name }}({{ alert.code }})</span>
          <span class="alert-value">{{ alert.currValue }}</span>
          <span class="alert-threshold">触发阈值: {{ alert.threshold }}</span>
          <span class="alert-time">{{ alert.eventTime }}</span>
        </span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { Warning } from '@element-plus/icons-vue'

const props = defineProps({
  alerts: { type: Array, default: () => [] }
})

// Duplicate alerts for seamless scrolling
const displayAlerts = computed(() => {
  return [...props.alerts, ...props.alerts]
})

const duration = computed(() => {
  return Math.max(props.alerts.length * 5, 15)
})

function getTypeLabel(type) {
  const map = {
    pct_up: '涨幅',
    pct_down: '跌幅',
    volume_surge: '放量',
    price_breakout: '突破'
  }
  return map[type] || type
}
</script>

<style scoped>
.alert-ticker {
  display: flex;
  align-items: center;
  background: linear-gradient(90deg, rgba(225, 82, 65, 0.1), rgba(225, 82, 65, 0.05));
  border: 1px solid rgba(225, 82, 65, 0.3);
  border-radius: 6px;
  padding: 8px 0;
  overflow: hidden;
  height: 40px;
}

.ticker-icon {
  display: flex;
  align-items: center;
  padding: 0 12px;
  color: #e15241;
  font-size: 18px;
  flex-shrink: 0;
  z-index: 1;
  background: inherit;
}

.ticker-track {
  flex: 1;
  overflow: hidden;
  position: relative;
}

.ticker-content {
  display: flex;
  gap: 48px;
  white-space: nowrap;
  animation: ticker-scroll linear infinite;
}

@keyframes ticker-scroll {
  0% { transform: translateX(0); }
  100% { transform: translateX(-50%); }
}

.alert-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  flex-shrink: 0;
}

.alert-tag {
  padding: 1px 6px;
  border-radius: 3px;
  font-size: 11px;
  font-weight: 600;
}

.alert-pct_up .alert-tag { background: rgba(225, 82, 65, 0.2); color: #e15241; }
.alert-pct_down .alert-tag { background: rgba(60, 179, 113, 0.2); color: #3cb371; }
.alert-volume_surge .alert-tag { background: rgba(224, 176, 64, 0.2); color: #e0b040; }
.alert-price_breakout .alert-tag { background: rgba(64, 158, 255, 0.2); color: #409eff; }

.alert-pct_up .alert-value { color: #e15241; font-weight: 600; }
.alert-pct_down .alert-value { color: #3cb371; font-weight: 600; }

.alert-stock { color: #e0e6ed; }
.alert-value { color: #c0c6d0; }
.alert-threshold { color: #909399; font-size: 11px; }
.alert-time { color: #606570; font-size: 11px; }
</style>
