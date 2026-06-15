<template>
  <el-container class="app-container">
    <el-aside width="220px" class="app-aside">
      <div class="logo">
        <h2>AGI Assistant</h2>
      </div>
      <el-menu
        :default-active="activeRoute"
        router
        class="aside-menu"
        background-color="#304156"
        text-color="#bfcbd9"
        active-text-color="#409eff"
      >
        <el-menu-item index="/">
          <el-icon><ChatDotRound /></el-icon>
          <template #title>对话</template>
        </el-menu-item>
        <el-menu-item index="/documents">
          <el-icon><Document /></el-icon>
          <template #title>文档管理</template>
        </el-menu-item>
        <el-menu-item index="/memory">
          <el-icon><Coin /></el-icon>
          <template #title>记忆系统</template>
        </el-menu-item>
        <el-menu-item index="/evaluation">
          <el-icon><DataAnalysis /></el-icon>
          <template #title>评测管理</template>
        </el-menu-item>
        <el-menu-item index="/sandbox">
          <el-icon><Monitor /></el-icon>
          <template #title>代码沙箱</template>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-main class="app-main">
      <router-view />
    </el-main>
  </el-container>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import {
  ChatDotRound,
  Document,
  Coin,
  DataAnalysis,
  Monitor,
} from '@element-plus/icons-vue'

const route = useRoute()
const activeRoute = computed(() => route.path)
</script>

<style>
.app-container {
  height: 100vh;
  width: 100%;
}

.app-aside {
  background-color: #304156;
  overflow: hidden;
}

.logo {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  border-bottom: 1px solid #3d4f65;
}

.logo h2 {
  font-size: 18px;
  font-weight: 600;
  margin: 0;
}

.aside-menu {
  border-right: none;
  height: calc(100% - 60px);
}

.aside-menu .el-menu-item {
  height: 50px;
  line-height: 50px;
}

.aside-menu .el-menu-item.is-active {
  background-color: #263445 !important;
}

.app-main {
  padding: 0;
  background-color: #f5f7fa;
  overflow: hidden;
}
</style>
