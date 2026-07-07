import axios from 'axios'

const request = axios.create({
  baseURL: '/api',
  timeout: 10000
})

// Response interceptor
request.interceptors.response.use(
  response => {
    const res = response.data
    if (res.code === 200) {
      return res.data
    }
    console.error('API Error:', res.message)
    return Promise.reject(new Error(res.message || 'API Error'))
  },
  error => {
    console.error('Request Error:', error.message)
    return Promise.reject(error)
  }
)

export default request

// Dashboard API
export const dashboardApi = {
  getSummary: () => request.get('/dashboard/summary')
}

// Stock API
export const stockApi = {
  getStockList: (keyword) => request.get('/stocks', { params: keyword ? { keyword } : {} }),
  getTopUp: (count = 20) => request.get('/stocks/top-up', { params: { count } }),
  getTopDown: (count = 20) => request.get('/stocks/top-down', { params: { count } }),
  getTopAmount: (count = 20) => request.get('/stocks/top-amount', { params: { count } }),
  getTopQuant: (count = 20) => request.get('/stocks/top-quant', { params: { count } }),
  getStockDetail: (code) => request.get(`/stocks/${code}`),
  getStockMinutes: (code, date) => request.get(`/stocks/${code}/minutes`, { params: { date } }),
  getSparkBatch: (codes) => request.post('/stocks/spark-batch', codes)
}

// Alert API
export const alertApi = {
  getLatest: (count = 50) => request.get('/alerts/latest', { params: { count } })
}
