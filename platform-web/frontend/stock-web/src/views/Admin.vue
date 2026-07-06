<template>
  <div class="page">
    <div class="card">
      <span class="card-icon">⚙</span>
      <h2 class="card-title">管理后台</h2>
      <p class="card-desc">管理后台功能正在开发中</p>
      <router-link to="/" class="card-btn">
        <el-icon><ArrowLeft /></el-icon>
        <span>返回大盘</span>
      </router-link>
    </div>
  </div>
</template>

<script setup>
import { ArrowLeft } from '@element-plus/icons-vue'
</script>

<style scoped>
.page {
  display: flex; align-items: center; justify-content: center;
  min-height: 100vh; background: var(--bg-root); padding: 40px;
}
.card {
  text-align: center; padding: 48px 40px;
  background: var(--bg-surface); border: 1px solid var(--border-subtle);
  border-radius: var(--radius-xl); max-width: 380px; width: 100%;
}
.card-icon { font-size: 40px; opacity: .25; margin-bottom: 16px; display: block }
.card-title { font-size: 20px; font-weight: 700; color: var(--text-primary); margin: 0 0 8px }
.card-desc { font-size: 14px; color: var(--text-muted); margin: 0 0 24px }
.card-btn {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 9px 20px; background: var(--bg-elevated);
  border: 1px solid var(--border-subtle); border-radius: var(--radius-md);
  color: var(--text-secondary); text-decoration: none;
  font-size: 13px; font-weight: 500; font-family: inherit;
  transition: all .15s;
}
.card-btn:hover { border-color: var(--accent); color: var(--accent); background: var(--accent-bg) }
</style>
