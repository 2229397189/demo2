import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
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
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to, _from, next) => {
  document.title = `${to.meta.title || 'AGI Assistant'} - AGI Assistant`
  next()
})

export default router
