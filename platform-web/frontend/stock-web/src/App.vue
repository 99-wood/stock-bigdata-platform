<template>
  <div id="app-container">
    <router-view />
  </div>
</template>

<script setup>
</script>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Microsoft YaHei', 'PingFang SC', sans-serif;
  background-color: #0A0C10;
  color: #E3E7EC;
}

#app-container {
  min-height: 100vh;
  width: 100%;
}

/* ---- Global stock code color rules (single source) ---- */
.code-up        { color: #F85149 !important; }   /* 涨 / 涨停 — 红 */
.code-down      { color: #3FB950 !important; }   /* 跌 / 跌停 — 绿 */
.code-suspended { color: #D29922 !important; }   /* 停牌 — 琥珀 */
.code-delisted  { color: #636D7E !important; }   /* 退市 — 暗灰 */
.code-flat      { color: #8892A0 !important; }   /* 平盘 — 浅灰 */
.code-neutral   { color: #E3E7EC; }              /* 默认 — 白 */
</style>
