<template>
  <div ref="chartRef" class="kline-chart"></div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, watch, nextTick } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  data: { type: Array, default: () => [] }  // [{time, open, high, low, close, vol}]
})

const chartRef = ref(null)
let chart = null

function buildOption(klineData) {
  const dates = klineData.map(d => d.time)
  const ohlc = klineData.map(d => [d.open, d.close, d.low, d.high])
  const vols = klineData.map(d => d.vol)

  return {
    backgroundColor: 'transparent',
    grid: [
      { left: '8%', right: '1%', top: '1%', height: '70%' },
      { left: '8%', right: '1%', top: '73%', height: '26%' }
    ],
    xAxis: [
      {
        type: 'category', data: dates, gridIndex: 0,
        axisLine: { lineStyle: { color: '#2D3340' } },
        axisTick: { show: false },
        axisLabel: { color: '#636D7E', fontSize: 10, fontFamily: "'Fira Code',monospace" },
        boundaryGap: true
      },
      {
        type: 'category', data: dates, gridIndex: 1,
        axisLine: { lineStyle: { color: '#2D3340' } },
        axisTick: { show: false },
        axisLabel: { show: false },
        boundaryGap: true
      }
    ],
    yAxis: [
      {
        type: 'value', scale: true, gridIndex: 0, splitNumber: 4,
        min: function (value) { return Math.floor(value.min * 0.998 * 100) / 100 },
        max: function (value) { return Math.ceil(value.max * 1.002 * 100) / 100 },
        splitLine: { lineStyle: { color: 'rgba(45,51,64,0.4)' } },
        axisLabel: {
          color: '#636D7E', fontSize: 10, fontFamily: "'Fira Code',monospace",
          formatter: v => v.toFixed(2)
        }
      },
      {
        type: 'value', scale: true, gridIndex: 1, splitNumber: 2,
        splitLine: { lineStyle: { color: 'rgba(45,51,64,0.4)' } },
        axisLabel: {
          color: '#636D7E', fontSize: 10, fontFamily: "'Fira Code',monospace",
          formatter: v => {
            const hands = v / 100
            return hands >= 10000 ? (hands / 10000).toFixed(0) + '万手' : hands.toFixed(0) + '手'
          }
        }
      }
    ],
    series: [
      {
        name: 'K线', type: 'candlestick', data: ohlc, xAxisIndex: 0, yAxisIndex: 0,
        itemStyle: {
          color: '#e15241', color0: '#3cb371',
          borderColor: '#e15241', borderColor0: '#3cb371'
        },
        emphasis: {
          itemStyle: {
            color: '#ff6b5e', color0: '#4cd98b',
            borderColor: '#ff6b5e', borderColor0: '#4cd98b'
          }
        }
      },
      {
        name: '成交量', type: 'bar', data: vols, xAxisIndex: 1, yAxisIndex: 1,
        itemStyle: {
          color: function (params) {
            const d = klineData[params.dataIndex]
            return d.close >= d.open ? '#e15241' : '#3cb371'
          }
        },
        emphasis: {
          itemStyle: { opacity: 0.7 }
        }
      }
    ],
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross' },
      backgroundColor: '#13161D',
      borderColor: '#2D3340',
      textStyle: { color: '#E3E7EC', fontSize: 12, fontFamily: "'Fira Code',monospace" },
      formatter: function (params) {
        const k = params.find(p => p.seriesName === 'K线')
        const v = params.find(p => p.seriesName === '成交量')
        if (!k || !v) return ''
        const d = klineData[k.dataIndex]
        const cls = d.close >= d.open ? '#e15241' : '#3cb371'
        return `<span style="color:${cls};font-weight:700">${d.time}</span><br/>
          <span style="color:#C9D1D9">开 ${d.open.toFixed(2)}&nbsp;&nbsp;高 ${d.high.toFixed(2)}</span><br/>
          <span style="color:#C9D1D9">收 ${d.close.toFixed(2)}&nbsp;&nbsp;低 ${d.low.toFixed(2)}</span><br/>
          <span style="color:#8892A0">量 ${fmtVolTooltip(v.value)}</span>`
      }
    },
    dataZoom: [
      { type: 'inside', xAxisIndex: [0, 1], start: 0, end: 100 }
    ]
  }
}

// volume 股→手, auto-unit
function fmtVolTooltip(v) {
  const hands = v / 100
  if (hands >= 10000) return (hands / 10000).toFixed(1) + '万手'
  return hands.toLocaleString() + '手'
}

function render() {
  if (!chart || !props.data.length) return
  chart.setOption(buildOption(props.data), true)
}

watch(() => props.data, () => nextTick(render), { deep: true })

onMounted(() => {
  chart = echarts.init(chartRef.value, null, { renderer: 'canvas' })
  render()
  window.addEventListener('resize', () => chart?.resize())
})

onUnmounted(() => {
  chart?.dispose()
  chart = null
})
</script>

<style scoped>
.kline-chart {
  width: 100%;
  height: 100%;
  min-height: 200px;
}
</style>
