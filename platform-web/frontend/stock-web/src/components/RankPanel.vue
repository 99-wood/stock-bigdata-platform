<template>
  <div class="panel">
    <div class="panel-head">
      <h3 class="panel-title">{{ title }}</h3>
      <span class="panel-count">{{ data.length }}</span>
    </div>
    <div class="panel-body">
      <div
        v-for="(item, idx) in data" :key="item.code"
        class="row group" @click="$emit('select', item.code)"
      >
        <span class="rank-badge" :class="rankBadge(idx)">{{ idx + 1 }}</span>
        <div class="stock-main">
          <span class="stock-code" :class="codeColor(item)">{{ item.code }}</span>
        </div>
        <div class="stock-sides">
          <span class="bid">{{ side(item, 'bid') }}</span>
          <span class="ask">{{ side(item, 'ask') }}</span>
        </div>
        <span class="row-action opacity-0 group-hover:opacity-100">
          <el-icon><ArrowRight /></el-icon>
        </span>
      </div>
      <div v-if="!data.length" class="empty">
        <span class="empty-dot"></span>
        <span>暂无数据</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ArrowRight } from '@element-plus/icons-vue'
import { getStockColorClass } from '@/utils/stockColor'

const props = defineProps({
  title: String, data: Array, type: String
})
defineEmits(['select'])

function rankBadge(i) {
  if (i === 0) return 'r1'
  if (i === 1) return 'r2'
  if (i === 2) return 'r3'
  return ''
}

function codeColor(item) {
  if (item.changePct != null) return getStockColorClass(item)
  if (props.type === 'up') return 'code-up'
  if (props.type === 'down') return 'code-down'
  return 'code-neutral'
}

function side(item, s) {
  const b = Number(item.bid), a = Number(item.ask)
  if (s === 'bid') {
    if (b > 0) return b.toFixed(2)
    if (a > 0 && b === 0) return '跌停'
    return '停牌'
  }
  if (a > 0) return a.toFixed(2)
  if (b > 0 && a === 0) return '涨停'
  return '停牌'
}
</script>

<style scoped>
.panel {
  background: var(--bg-surface);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg);
  overflow: hidden;
  transition: border-color .2s;
}
.panel:hover { border-color: var(--border-strong) }

.panel-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border-subtle);
}

.panel-title { font-size: 13px; font-weight: 600; color: var(--text-primary); letter-spacing: -.01em }
.panel-count {
  font-size: 10px; font-weight: 600; color: var(--text-muted);
  background: var(--bg-elevated); padding: 2px 8px;
  border-radius: var(--radius-full); font-family: var(--font-mono);
}

.panel-body { max-height: 460px; overflow-y: auto }

.row {
  display: flex; align-items: center;
  padding: 8px 16px;
  border-bottom: 1px solid rgba(255,255,255,.025);
  cursor: pointer; transition: background .1s;
}
.row:last-child { border-bottom: none }
.row:hover { background: var(--bg-hover) }

.rank-badge {
  width: 24px; height: 24px;
  display: flex; align-items: center; justify-content: center;
  border-radius: var(--radius-sm);
  font-size: 11px; font-weight: 700;
  color: var(--text-muted); background: var(--bg-elevated);
  font-family: var(--font-mono); flex-shrink: 0; margin-right: 12px;
}
.rank-badge.r1 { background: var(--stock-up); color: #fff }
.rank-badge.r2 { background: #E17341; color: #fff }
.rank-badge.r3 { background: var(--stock-warn); color: #1A1A1A }

.stock-main { flex: 1; overflow: hidden }

.stock-code {
  font-size: 13px; font-family: var(--font-mono); font-weight: 500;
  letter-spacing: .02em;
}

.stock-sides {
  display: flex; flex-direction: column; align-items: flex-end;
  gap: 1px; flex-shrink: 0; font-size: 11px; font-family: var(--font-mono);
}
.bid { color: var(--stock-down) }
.ask { color: var(--stock-up) }

.row-action {
  margin-left: 8px; color: var(--text-muted); font-size: 13px;
  transition: opacity .15s; flex-shrink: 0;
}
.opacity-0 { opacity: 0 }

.empty {
  display: flex; align-items: center; justify-content: center;
  gap: 8px; padding: 44px 16px; color: var(--text-muted); font-size: 13px;
}
.empty-dot { width: 5px; height: 5px; border-radius: 50%; background: var(--border-default) }
</style>
