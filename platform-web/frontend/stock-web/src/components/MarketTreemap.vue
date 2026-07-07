<template>
  <div class="treemap-dual">
    <div class="tm-half">
      <div class="tm-title up">▲ 成交额前20上涨</div>
      <div ref="upRef" class="tm-chart"></div>
    </div>
    <div class="tm-half">
      <div class="tm-title down">▼ 成交额前20下跌</div>
      <div ref="downRef" class="tm-chart"></div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, watch, nextTick } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  upData: { type: Array, default: () => [] },
  downData: { type: Array, default: () => [] }
})

const upRef = ref(null), downRef = ref(null)
let upChart = null, downChart = null

function colorOpacity(pct, isUp) {
  const t = Math.min(Math.abs(pct), 10) / 10 // 0→1, capped at 10%
  const alpha = 0.5 + t * 0.5 // opacity: 0.5→1.0
  return isUp ? `rgba(255,73,91,${alpha.toFixed(2)})` : `rgba(63,185,80,${alpha.toFixed(2)})`
}

function buildOption(list) {
  const isUp = list.length > 0 && (list[0].changePct || 0) >= 0
  const areas = list.map(d => Math.sqrt(d.amount || 1))
  const minSqrt = Math.min(...areas), maxSqrt = Math.max(...areas), range = maxSqrt - minSqrt || 1
  const mapped = list.map(d => {
    const t = (Math.sqrt(d.amount || 1) - minSqrt) / range
    return {
      name: d.name || d.code || '--',
      value: d.amount || 1,
      changePct: d.changePct || 0,
      code: d.code,
      itemStyle: { color: colorOpacity(d.changePct || 0, isUp) },
      label: { fontSize: Math.round(11 + t * 13) }
    }
  })
  return {
    backgroundColor: 'transparent',
    tooltip: {
      backgroundColor: '#13161D', borderColor: '#2D3340',
      textStyle: { color: '#E3E7EC', fontSize: 12, fontFamily: "'Fira Code',monospace" },
      formatter: p => {
        if (!p.data || !p.data.code) return ''
        const d = p.data, sign = d.changePct >= 0 ? '+' : ''
        const cls = d.changePct >= 0 ? '#FF495B' : '#3FB950'
        return `<b style="color:${cls}">${d.name}</b><br/>涨跌幅 <span style="color:${cls}">${sign}${d.changePct}%</span>`
      }
    },
    series: [{
      type: 'treemap', name: '', data: mapped,
      width: '100%', height: '100%',
      roam: false, nodeClick: false, leafDepth: 1, squareRatio: 1.5,
      breadcrumb: { show: false },
      label: { show: true, color: '#E3E7EC', overflow: 'break', formatter: p => { const d = p.data, s = d.changePct >= 0 ? '+' : ''; return d.name + '\n' + s + d.changePct + '%' } },
      upperLabel: { show: false },
      itemStyle: { borderColor: '#0D1117', borderWidth: 0, gapWidth: 0 }
    }]
  }
}

function render() {
  if (upChart && props.upData.length) upChart.setOption(buildOption(props.upData), true)
  if (downChart && props.downData.length) downChart.setOption(buildOption(props.downData), true)
}

watch([() => props.upData, () => props.downData], () => nextTick(render), { deep: true })

function onResize() { upChart?.resize(); downChart?.resize() }

onMounted(() => {
  upChart = echarts.init(upRef.value, null, { renderer: 'canvas' })
  downChart = echarts.init(downRef.value, null, { renderer: 'canvas' })
  render()
  window.addEventListener('resize', onResize)
})

onUnmounted(() => {
  upChart?.dispose(); downChart?.dispose()
  upChart = downChart = null
})
</script>

<style scoped>
.treemap-dual { display: flex; flex-direction: column; height: 100%; gap: 0 }
.tm-half { flex: 1; display: flex; flex-direction: column; min-height: 0; max-width: 460px }
.tm-title { font-size: 11px; font-weight: 600; padding: 4px 8px; letter-spacing: .04em; flex-shrink: 0 }
.tm-title.up { color: #FF495B; background: rgba(255,73,91,0.08); border-bottom: 1px solid rgba(255,73,91,0.2) }
.tm-title.down { color: #3FB950; background: rgba(63,185,80,0.08); border-bottom: 1px solid rgba(63,185,80,0.2) }
.tm-chart { flex: 1; min-height: 0 }
</style>
