import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录', layout: 'blank', public: true },
  },
  {
    path: '/',
    name: 'Chat',
    component: () => import('@/views/ChatView.vue'),
    meta: { title: '对话' },
  },
  {
    path: '/documents',
    name: 'Documents',
    component: () => import('@/views/DocumentView.vue'),
    meta: { title: '文档管理' },
  },
  {
    path: '/memory',
    name: 'Memory',
    component: () => import('@/views/MemoryView.vue'),
    meta: { title: '记忆系统' },
  },
  {
    path: '/evaluation',
    name: 'Evaluation',
    component: () => import('@/views/EvaluationView.vue'),
    meta: { title: '评测管理' },
  },
  {
    path: '/sandbox',
    name: 'Sandbox',
    component: () => import('@/views/SandboxView.vue'),
    meta: { title: '代码沙箱' },
  },
  {
    path: '/agent',
    name: 'Agent',
    component: () => import('@/views/AgentView.vue'),
    meta: { title: 'Agent 工作台' },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    redirect: '/',
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

/**
 * 全局前置守卫。
 *
 * <p>核心逻辑（对应生产环境「没登录 → 全站 401」的修复）：</p>
 * <ol>
 *   <li>公开页（{@code meta.public}，如登录页）直接放行；已登录再访登录页则回首页。</li>
 *   <li>有 token：乐观放行；token 若已失效，业务请求的 401 会被 axios / SSE 统一
 *       处理（清凭据 + 跳登录），无需在此阻塞导航。</li>
 *   <li>无 token：<b>先探测后端是否真的强制鉴权</b>——
 *       {@code AUTH_ENABLED=false}（本地调试）时后端不校验 token，此时不应把用户
 *       锁在登录页；仅当探测确认为需要鉴权时才重定向到 /login。</li>
 * </ol>
 */
router.beforeEach(async (to, _from, next) => {
  document.title = `${to.meta.title || 'AGI Assistant'} - AGI Assistant`

  const auth = useAuthStore()

  if (to.meta.public === true) {
    if (auth.token) {
      next({ path: '/' })
      return
    }
    next()
    return
  }

  if (auth.token) {
    // 启动时静默校对 token（失败由 401 链路兜底，不阻塞导航）
    if (!auth.user) {
      auth.fetchMe()
    }
    next()
    return
  }

  const required = await auth.detectAuthRequired()
  if (required) {
    next({ path: '/login', query: { redirect: to.fullPath } })
  } else {
    // 后端未开启鉴权（本地调试）：放行，避免误把用户挡在登录页
    next()
  }
})

export default router
