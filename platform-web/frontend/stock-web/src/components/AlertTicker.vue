<template>
  <div class="ticker-bar" v-if="alerts.length">
    <div class="ticker-inner">
      <div class="ticker-badge">
        <el-icon><Bell /></el-icon>
        <span>ALERTS</span>
      </div>
      <div class="ticker-track">
        <div class="ticker-scroll" :style="{ animationDuration: dur + 's' }">
          <span v-for="(a, i) in dup" :key="i" class="alert-item" :class="a.alertType">
            <span class="alert-tag">{{ typeLabel(a.alertType) }}</span>
            <span class="alert-name">{{ a.name || a.code }}({{ a.code }})</span>
            <span class="alert-val">{{ a.currValue }}</span>
            <span class="alert-th">触发 {{ a.threshold }}</span>
            <span class="alert-time">{{ a.eventTime }}</span>
          </span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { Bell } from '@element-plus/icons-vue'

const props = defineProps({ alerts: Array })

const dup = computed(() => [...props.alerts, ...props.alerts])
const dur = computed(() => Math.max(props.alerts.length * 5, 15))

function typeLabel(t) {
  const m = { pct_up: '涨幅', pct_down: '跌幅', volume_surge: '放量', price_breakout: '突破' }
  return m[t] || t
}
</script>

<style scoped>
.ticker-bar {
  position: fixed; bottom: 12px; left: 50%; transform: translateX(-50%);
  z-index: 100; width: calc(100% - 48px); max-width: 1552px;
}

.ticker-inner {
  display: flex; align-items: center;
  background: rgba(13,13,13,.82);
  backdrop-filter: blur(20px); -webkit-backdrop-filter: blur(20px);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg);
  overflow: hidden; height: 42px;
  box-shadow: var(--shadow-glass);
}

.ticker-badge {
  display: flex; align-items: center; gap: 6px;
  padding: 0 14px; flex-shrink: 0; height: 100%;
  background: var(--stock-warn-bg);
  color: var(--stock-warn);
  font-size: 10px; font-weight: 700; letter-spacing: .08em;
  border-right: 1px solid var(--border-subtle);
}

.ticker-track { flex: 1; overflow: hidden; position: relative }

.ticker-scroll {
  display: flex; gap: 48px; white-space: nowrap;
  animation: scroll linear infinite;
}
@keyframes scroll { 0%{transform:translateX(0)} 100%{transform:translateX(-50%)} }

.alert-item {
  display: flex; align-items: center; gap: 8px;
  font-size: 12px; flex-shrink: 0;
}

.alert-tag {
  padding: 1px 6px; border-radius: 3px;
  font-size: 10px; font-weight: 600; letter-spacing: .03em;
}
.pct_up          .alert-tag { background: var(--stock-up-bg); color: var(--stock-up) }
.pct_down        .alert-tag { background: var(--stock-down-bg); color: var(--stock-down) }
.volume_surge    .alert-tag { background: var(--stock-warn-bg); color: var(--stock-warn) }
.price_breakout  .alert-tag { background: var(--accent-bg); color: var(--accent) }

.pct_up          .alert-val { color: var(--stock-up); font-weight: 600 }
.pct_down        .alert-val { color: var(--stock-down); font-weight: 600 }

.alert-name { color: var(--text-primary); font-weight: 500 }
.alert-val  { color: var(--text-secondary) }
.alert-th   { color: var(--text-muted); font-size: 11px }
.alert-time { color: var(--text-muted); font-size: 11px; font-family: var(--font-mono) }
</style>
