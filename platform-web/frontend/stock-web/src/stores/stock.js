import { defineStore } from 'pinia'
import { dashboardApi, stockApi, alertApi } from '@/api/request'

// Backend MarketSummaryDTO uses @JsonProperty(snake_case), so normalize keys
function normalizeSummary(data) {
  if (!data) return null
  return {
    statTime: data.stat_time ?? data.statTime ?? '',
    totalStocks: data.total_stocks ?? data.totalStocks ?? 0,
    upCount: data.up_count ?? data.upCount ?? 0,
    downCount: data.down_count ?? data.downCount ?? 0,
    flatCount: data.flat_count ?? data.flatCount ?? 0,
    avgChangePct: data.avg_change_pct ?? data.avgChangePct ?? 0,
    totalVolume: data.total_volume ?? data.totalVolume ?? 0,
    totalAmount: data.total_amount ?? data.totalAmount ?? 0
  }
}

export const useStockStore = defineStore('stock', {
  state: () => ({
    marketSummary: {
      statTime: '',
      totalStocks: 0,
      upCount: 0,
      downCount: 0,
      flatCount: 0,
      avgChangePct: 0,
      totalVolume: 0,
      totalAmount: 0
    },
    topUp: [],
    topDown: [],
    topAmount: [],
    topQuant: [],
    alerts: [],
    tradeTime: '',
    loading: false,
    wsConnected: false
  }),

  getters: {
    upRatio: (state) => {
      if (state.marketSummary.totalStocks === 0) return 0
      return (state.marketSummary.upCount / state.marketSummary.totalStocks * 100).toFixed(1)
    },
    downRatio: (state) => {
      if (state.marketSummary.totalStocks === 0) return 0
      return (state.marketSummary.downCount / state.marketSummary.totalStocks * 100).toFixed(1)
    },
    flatRatio: (state) => {
      if (state.marketSummary.totalStocks === 0) return 0
      return (state.marketSummary.flatCount / state.marketSummary.totalStocks * 100).toFixed(1)
    }
  },

  actions: {
    async fetchSummary() {
      try {
        const data = await dashboardApi.getSummary()
        const normalized = normalizeSummary(data)
        if (normalized) this.marketSummary = normalized
      } catch (e) {
        console.error('Fetch summary error:', e)
      }
    },

    async fetchTopUp() {
      try {
        const data = await stockApi.getTopUp(20)
        if (data) this.topUp = data
      } catch (e) {
        console.error('Fetch top up error:', e)
      }
    },

    async fetchTopDown() {
      try {
        const data = await stockApi.getTopDown(20)
        if (data) this.topDown = data
      } catch (e) {
        console.error('Fetch top down error:', e)
      }
    },

    async fetchTopAmount() {
      try {
        const data = await stockApi.getTopAmount(20)
        if (data) this.topAmount = data
      } catch (e) {
        console.error('Fetch top amount error:', e)
      }
    },

    async fetchTopQuant() {
      try {
        const data = await stockApi.getTopQuant(20)
        if (data) this.topQuant = data
      } catch (e) {
        console.error('Fetch top quant error:', e)
      }
    },

    async fetchAlerts() {
      try {
        const data = await alertApi.getLatest(20)
        if (data) this.alerts = data
      } catch (e) {
        console.error('Fetch alerts error:', e)
      }
    },

    async fetchAll() {
      this.loading = true
      await Promise.all([
        this.fetchSummary(),
        this.fetchTopUp(),
        this.fetchTopDown(),
        this.fetchTopAmount(),
        this.fetchTopQuant(),
        this.fetchAlerts()
      ])
      this.loading = false
    },

    // Called by WebSocket callbacks
    updateMarketSummary(data) {
      const normalized = normalizeSummary(data)
      if (normalized) this.marketSummary = normalized
    },

    updateTopUp(data) {
      if (data) this.topUp = data
    },

    updateTopDown(data) {
      if (data) this.topDown = data
    },

    updateTopAmount(data) {
      if (data) this.topAmount = data
    },

    updateTradeTime(time) {
      if (time) this.tradeTime = time
    }
  }
})
