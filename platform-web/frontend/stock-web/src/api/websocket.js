import { Client } from '@stomp/stompjs'
import SockJS from 'sockjs-client'

let client = null
let subscriptions = {}

/**
 * Connect to WebSocket and subscribe to topics.
 * @param {Object} callbacks - { onMarket, onRankUp, onRankDown, onRankAmount, onConnected, onError }
 */
export function connectWebSocket(callbacks = {}) {
  if (client && client.connected) {
    console.log('WebSocket already connected')
    return
  }

  const wsUrl = '/ws'

  client = new Client({
    webSocketFactory: () => new SockJS(wsUrl),
    reconnectDelay: 5000,
    heartbeatIncoming: 4000,
    heartbeatOutgoing: 4000,
    debug: (msg) => console.debug('STOMP:', msg),
    onConnect: () => {
      console.log('WebSocket connected')

      // Subscribe to market summary
      subscriptions.market = client.subscribe('/topic/market', (message) => {
        try {
          const data = JSON.parse(message.body)
          if (callbacks.onMarket) callbacks.onMarket(data)
        } catch (e) {
          console.error('Parse market message error:', e)
        }
      })

      // Subscribe to rank up
      subscriptions.rankUp = client.subscribe('/topic/rank/up', (message) => {
        try {
          const data = JSON.parse(message.body)
          if (callbacks.onRankUp) callbacks.onRankUp(data)
        } catch (e) {
          console.error('Parse rank up message error:', e)
        }
      })

      // Subscribe to rank down
      subscriptions.rankDown = client.subscribe('/topic/rank/down', (message) => {
        try {
          const data = JSON.parse(message.body)
          if (callbacks.onRankDown) callbacks.onRankDown(data)
        } catch (e) {
          console.error('Parse rank down message error:', e)
        }
      })

      // Subscribe to rank amount
      subscriptions.rankAmount = client.subscribe('/topic/rank/amount', (message) => {
        try {
          const data = JSON.parse(message.body)
          if (callbacks.onRankAmount) callbacks.onRankAmount(data)
        } catch (e) {
          console.error('Parse rank amount message error:', e)
        }
      })

      // Subscribe to latest trade time (plain string)
      subscriptions.tradeTime = client.subscribe('/topic/time', (message) => {
        if (callbacks.onTradeTime) callbacks.onTradeTime(message.body)
      })

      if (callbacks.onConnected) callbacks.onConnected()
    },
    onDisconnect: () => {
      console.log('WebSocket disconnected')
      subscriptions = {}
    },
    onStompError: (frame) => {
      console.error('STOMP error:', frame.headers['message'])
      if (callbacks.onError) callbacks.onError(frame)
    }
  })

  client.activate()
}

/**
 * Disconnect WebSocket.
 */
export function disconnectWebSocket() {
  if (client) {
    client.deactivate()
    client = null
    subscriptions = {}
    console.log('WebSocket deactivated')
  }
}
