<template>
  <div class="admin-placeholder">
    <el-result
      icon="info"
      title="管理后台"
      sub-title="管理后台功能正在开发中，敬请期待..."
    >
      <template #extra>
        <el-button type="primary" @click="$router.push('/dashboard')">
          返回大屏
        </el-button>
      </template>
    </el-result>
  </div>
</template>

<script setup>
</script>

<style scoped>
.admin-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: 40px;
}
</style>
