/**
 * 主题切换 Composable
 * 支持亮色/暗色模式，自动保存到 localStorage
 */

import { ref, watch, onMounted } from 'vue'

type Theme = 'light' | 'dark' | 'system'

const theme = ref<Theme>('system')
const isDark = ref(false)

export function useTheme() {
  // 获取系统主题偏好
  const getSystemTheme = (): boolean => {
    return window.matchMedia('(prefers-color-scheme: dark)').matches
  }

  // 应用主题到 DOM
  const applyTheme = (dark: boolean) => {
    const root = document.documentElement
    if (dark) {
      root.setAttribute('data-theme', 'dark')
      root.classList.add('dark')
    } else {
      root.removeAttribute('data-theme')
      root.classList.remove('dark')
    }
    isDark.value = dark
  }

  // 切换主题
  const toggleTheme = () => {
    const themes: Theme[] = ['light', 'dark', 'system']
    const currentIndex = themes.indexOf(theme.value)
    theme.value = themes[(currentIndex + 1) % themes.length]
  }

  // 设置主题
  const setTheme = (newTheme: Theme) => {
    theme.value = newTheme
  }

  // 监听主题变化
  watch(theme, (newTheme) => {
    localStorage.setItem('theme', newTheme)

    if (newTheme === 'system') {
      applyTheme(getSystemTheme())
    } else {
      applyTheme(newTheme === 'dark')
    }
  })

  // 监听系统主题变化
  const handleSystemThemeChange = () => {
    if (theme.value === 'system') {
      applyTheme(getSystemTheme())
    }
  }

  // 初始化
  onMounted(() => {
    // 从 localStorage 读取保存的主题
    const savedTheme = localStorage.getItem('theme') as Theme
    if (savedTheme) {
      theme.value = savedTheme
    }

    // 应用主题
    if (theme.value === 'system') {
      applyTheme(getSystemTheme())
    } else {
      applyTheme(theme.value === 'dark')
    }

    // 监听系统主题变化
    window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', handleSystemThemeChange)
  })

  return {
    theme,
    isDark,
    toggleTheme,
    setTheme,
  }
}
