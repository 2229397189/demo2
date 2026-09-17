import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import zhCn from 'element-plus/dist/locale/zh-cn.mjs'
import App from './App.vue'
import router from './router'
import { setUnauthorizedHandler } from './utils/request'

// 导入自定义样式（放在 Element Plus 之后，确保品牌 token 覆盖生效）
import './styles/variables.css'
import './styles/animations.css'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)
app.use(ElementPlus, { locale: zhCn as any })

// 统一「登录态失效」跳转：axios 与 SSE 触发 401 时都走这里
setUnauthorizedHandler((redirect) => {
  const current = router.currentRoute.value
  if (current.path === '/login') return
  router.push({
    path: '/login',
    query: { redirect: redirect || current.fullPath },
  })
})

app.mount('#app')
