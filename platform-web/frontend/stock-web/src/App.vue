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
</style>
