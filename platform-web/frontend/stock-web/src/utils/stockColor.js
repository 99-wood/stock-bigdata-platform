/**
 * 集中式股票代码着色规则 — 整个前端唯一出口。
 *
 * 优先级:
 *   1. 停牌 (status == '-1' | '-3')  → suspended (黄)
 *   2. 退市 (status == '-2')          → delisted (灰)
 *   3. changePct > 0                 → up (红)
 *   4. changePct < 0                 → down (绿)
 *   5. changePct == 0                → flat (灰)
 *   6. 涨停 (bid>0, ask==0)          → up (红)
 *   7. 跌停 (ask>0, bid==0)          → down (绿)
 *   8. 兜底                           → neutral (灰)
 *
 * @param {Object}  stock  - { code, bid, ask, status, changePct }
 * @returns {'up'|'down'|'suspended'|'delisted'|'flat'|'neutral'}
 */
export function getStockDirection(stock) {
  if (!stock) return 'neutral'

  // 1. 停牌
  if (stock.status === '-1' || stock.status === '-3') return 'suspended'

  // 2. 退市
  if (stock.status === '-2') return 'delisted'

  // 3. changePct 驱动（主要来源）
  const pct = Number(stock.changePct)
  if (!isNaN(pct)) {
    if (pct > 0) return 'up'
    if (pct < 0) return 'down'
    if (pct === 0) return 'flat'
  }

  // 4. 涨停 / 跌停兜底（changePct 缺失时用 bid/ask 判断）
  const b = Number(stock.bid), a = Number(stock.ask)
  if (!isNaN(b) && !isNaN(a)) {
    if (b > 0 && a === 0) return 'up'
    if (a > 0 && b === 0) return 'down'
  }

  // 5. 兜底
  return 'neutral'
}

/**
 * CSS class suffix: 'code-' + result
 */
export function getStockColorClass(stock) {
  return 'code-' + getStockDirection(stock)
}
