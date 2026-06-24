<template>
  <el-container class="app-layout">
    <!-- 侧边栏 - 学习 ChatGPT 的简洁设计 -->
    <el-aside :width="isCollapsed ? '64px' : '260px'" class="app-sidebar sidebar-transition" :class="{ 'mobile-open': mobileMenuOpen }">
      <!-- Logo 区域 -->
      <div class="sidebar-header">
        <div class="logo-container" @click="isCollapsed = !isCollapsed">
          <div class="logo-icon">
            <el-icon :size="24">
              <ChatDotRound />
            </el-icon>
          </div>
          <transition name="fade">
            <span v-if="!isCollapsed" class="logo-text">AGI Assistant</span>
          </transition>
        </div>
      </div>

      <!-- 导航菜单 -->
      <nav class="sidebar-nav">
        <router-link
          v-for="item in menuItems"
          :key="item.path"
          :to="item.path"
          class="nav-item"
          :class="{ active: isActive(item.path) }"
        >
          <el-icon :size="20">
            <component :is="item.icon" />
          </el-icon>
          <transition name="fade">
            <span v-if="!isCollapsed" class="nav-label">{{ item.label }}</span>
          </transition>
        </router-link>
      </nav>

      <!-- 底部工具栏 -->
      <div class="sidebar-footer">
        <div class="footer-actions">
          <ThemeToggle />
          <el-tooltip content="设置" placement="right" :disabled="!isCollapsed">
            <button class="icon-btn" @click="showSettings = true">
              <el-icon :size="18"><Setting /></el-icon>
            </button>
          </el-tooltip>
        </div>
        <transition name="fade">
          <div v-if="!isCollapsed" class="footer-info">
            <span class="version">v1.0.0</span>
          </div>
        </transition>
      </div>
    </el-aside>

    <!-- 移动端遮罩层 -->
    <div
      v-if="mobileMenuOpen"
      class="mobile-overlay"
      @click="mobileMenuOpen = false"
    />

    <!-- 主内容区 -->
    <el-main class="app-main">
      <!-- 移动端菜单按钮 -->
      <button class="app-mobile-menu-btn" @click="mobileMenuOpen = !mobileMenuOpen">
        <el-icon :size="20"><Operation /></el-icon>
      </button>
      <router-view v-slot="{ Component, route }">
        <transition name="page" mode="out-in">
          <component :is="Component" :key="route.path" />
        </transition>
      </router-view>
    </el-main>

    <!-- 设置抽屉 -->
    <el-drawer v-model="showSettings" title="设置" size="360px">
      <div class="settings-content">
        <div class="setting-item">
          <span class="setting-label">主题模式</span>
          <ThemeToggle />
        </div>
      </div>
    </el-drawer>
  </el-container>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
  ChatDotRound,
  Document,
  Memo,
  DataLine,
  Cpu,
  Setting,
  Operation,
} from '@element-plus/icons-vue'
import ThemeToggle from '@/components/common/ThemeToggle.vue'

const route = useRoute()
const isCollapsed = ref(localStorage.getItem('sidebar-collapsed') === 'true')
const showSettings = ref(false)
const mobileMenuOpen = ref(false)

// 监听折叠状态变化并持久化
watch(isCollapsed, (val) => {
  localStorage.setItem('sidebar-collapsed', String(val))
})

// 菜单项配置
const menuItems = [
  {
    path: '/',
    label: '对话',
    icon: ChatDotRound,
  },
  {
    path: '/documents',
    label: '文档管理',
    icon: Document,
  },
  {
    path: '/memory',
    label: '记忆系统',
    icon: Memo,
  },
  {
    path: '/evaluation',
    label: '评测管理',
    icon: DataLine,
  },
  {
    path: '/sandbox',
    label: '代码沙箱',
    icon: Cpu,
  },
]

// 判断当前路由是否激活
const isActive = (path: string) => {
  if (path === '/') {
    return route.path === '/'
  }
  return route.path.startsWith(path)
}
</script>

<style>
/* 全局样式导入 */
@import './styles/variables.css';
@import './styles/animations.css';
</style>

<style scoped>
.app-layout {
  height: 100vh;
  overflow: hidden;
}

/* ── 侧边栏 ── */
.app-sidebar {
  background-color: var(--color-bg-primary);
  border-right: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.sidebar-header {
  padding: var(--space-4);
  border-bottom: 1px solid var(--color-border-light);
}

.logo-container {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-2);
  border-radius: var(--radius-lg);
  cursor: pointer;
  transition: background-color var(--transition-fast);
}

.logo-container:hover {
  background-color: var(--color-bg-tertiary);
}

.logo-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: var(--radius-md);
  background: linear-gradient(135deg, var(--color-primary), var(--color-primary-light));
  color: white;
  flex-shrink: 0;
}

.logo-text {
  font-size: var(--text-lg);
  font-weight: 600;
  color: var(--color-text-primary);
  white-space: nowrap;
}

/* ── 导航菜单 ── */
.sidebar-nav {
  flex: 1;
  padding: var(--space-2);
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.nav-item {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-3) var(--space-3);
  border-radius: var(--radius-md);
  color: var(--color-text-secondary);
  text-decoration: none;
  transition: all var(--transition-fast);
  position: relative;
}

.nav-item:hover {
  background-color: var(--color-bg-tertiary);
  color: var(--color-text-primary);
}

.nav-item.active {
  background-color: var(--color-primary-bg);
  color: var(--color-primary);
}

.nav-item.active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 3px;
  height: 20px;
  background-color: var(--color-primary);
  border-radius: 0 var(--radius-full) var(--radius-full) 0;
}

.nav-label {
  font-size: var(--text-sm);
  font-weight: 500;
  white-space: nowrap;
}

/* ── 底部工具栏 ── */
.sidebar-footer {
  padding: var(--space-4);
  border-top: 1px solid var(--color-border-light);
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.footer-actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.icon-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border: none;
  border-radius: var(--radius-md);
  background-color: transparent;
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.icon-btn:hover {
  background-color: var(--color-primary-bg);
  color: var(--color-primary);
}

.footer-info {
  text-align: center;
}

.version {
  font-size: var(--text-xs);
  color: var(--color-text-tertiary);
}

/* ── 主内容区 ── */
.app-main {
  background-color: var(--color-bg-secondary);
  padding: 0;
  overflow: hidden;
}

/* ── 设置抽屉 ── */
.settings-content {
  padding: var(--space-4);
}

.setting-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-3) 0;
}

.setting-label {
  font-size: var(--text-sm);
  color: var(--color-text-primary);
}

/* ── 移动端菜单按钮 ── */
.app-mobile-menu-btn {
  display: none;
  position: fixed;
  top: var(--space-3);
  left: var(--space-3);
  z-index: var(--z-sticky);
  width: 40px;
  height: 40px;
  border: none;
  border-radius: var(--radius-md);
  background-color: var(--color-bg-primary);
  color: var(--color-text-primary);
  cursor: pointer;
  box-shadow: var(--shadow-sm);
  align-items: center;
  justify-content: center;
  transition: all var(--transition-fast);
}

.app-mobile-menu-btn:hover {
  background-color: var(--color-bg-tertiary);
}

/* ── 移动端遮罩层 ── */
.mobile-overlay {
  display: none;
}

/* ── 响应式设计 ── */
@media (max-width: 768px) {
  .app-mobile-menu-btn {
    display: flex;
  }

  .mobile-overlay {
    display: block;
    position: fixed;
    inset: 0;
    background: var(--color-bg-overlay);
    z-index: calc(var(--z-fixed) - 1);
  }

  .app-sidebar {
    position: fixed;
    left: 0;
    top: 0;
    bottom: 0;
    z-index: var(--z-fixed);
    transform: translateX(-100%);
    transition: transform var(--transition-slow);
  }

  .app-sidebar.mobile-open {
    transform: translateX(0);
  }
}
</style>
