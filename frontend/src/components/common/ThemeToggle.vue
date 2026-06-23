<template>
  <el-tooltip :content="tooltipText" placement="bottom">
    <button class="theme-toggle" @click="toggleTheme" :aria-label="tooltipText">
      <transition name="scale" mode="out-in">
        <el-icon v-if="theme === 'light'" key="light" :size="18">
          <Sunny />
        </el-icon>
        <el-icon v-else-if="theme === 'dark'" key="dark" :size="18">
          <Moon />
        </el-icon>
        <el-icon v-else key="system" :size="18">
          <Monitor />
        </el-icon>
      </transition>
    </button>
  </el-tooltip>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Sunny, Moon, Monitor } from '@element-plus/icons-vue'
import { useTheme } from '@/composables/useTheme'

const { theme, toggleTheme } = useTheme()

const tooltipText = computed(() => {
  const labels = {
    light: '亮色模式',
    dark: '暗色模式',
    system: '跟随系统',
  }
  return `当前：${labels[theme.value]}，点击切换`
})
</script>

<style scoped>
.theme-toggle {
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

.theme-toggle:hover {
  background-color: var(--color-primary-bg);
  color: var(--color-primary);
}

.theme-toggle:active {
  transform: scale(0.95);
}

.scale-enter-active,
.scale-leave-active {
  transition: all 0.2s ease;
}

.scale-enter-from,
.scale-leave-to {
  opacity: 0;
  transform: scale(0.8) rotate(90deg);
}
</style>
