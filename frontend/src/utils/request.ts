import axios from 'axios'
import type { AxiosInstance, AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import { getToken, getStoredUserId, clearAuth } from '@/utils/authStorage'

/**
 * 认证失效时的回调（跳转登录页）。
 * <p>由 {@code main.ts} 注入 router —— 这样 request 模块不必反向依赖 router，
 * 避免 request → router → store → api → request 的循环引用。</p>
 */
export type UnauthorizedHandler = (redirect?: string) => void

let unauthorizedHandler: UnauthorizedHandler | null = null

/** 注册「未授权」处理器（在应用启动时调用一次）。 */
export function setUnauthorizedHandler(handler: UnauthorizedHandler): void {
  unauthorizedHandler = handler
}

/**
 * 统一的「登录态失效」处理：清本地凭据 → 提示 → 跳登录页。
 * <p>导出给非 axios 通路（SSE 的 fetch）复用，避免两套 401 逻辑。</p>
 */
export function notifyUnauthorized(redirect?: string): void {
  clearAuth()
  ElMessage.error('登录状态已失效，请重新登录')
  unauthorizedHandler?.(redirect)
}

const service: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json;charset=UTF-8',
  },
})

service.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    if (config.data instanceof FormData && config.headers) {
      const headers = config.headers as Record<string, unknown> & { delete?: (name: string) => boolean }
      headers.delete?.('Content-Type')
      delete headers['Content-Type']
      delete headers['content-type']
    }

    // 同一套取 token / userId 的逻辑（见 utils/authStorage），SSE 通路复用同一模块
    const token = getToken()
    if (token && config.headers) {
      config.headers.Authorization = `Bearer ${token}`
    }
    // 未登录时回落缺省用户（配合后端 AUTH_ENABLED=false 的 X-User-Id 兜底）
    if (config.headers) {
      config.headers['X-User-Id'] = getStoredUserId()
    }
    return config
  },
  (error) => {
    console.error('Request error:', error)
    return Promise.reject(error)
  }
)

service.interceptors.response.use(
  (response: AxiosResponse) => {
    const res = response.data
    if (res && res.code && res.code !== 200) {
      ElMessage.error(res.message || '请求失败')
      return Promise.reject(new Error(res.message || '请求失败'))
    }
    return res
  },
  (error) => {
    console.error('Response error:', error)

    const url: string = error?.config?.url || ''
    // 凭据类接口（登录 / 注册）的错误就地交给页面展示（表单内提示），
    // 既不做「登录态失效」跳转、也不弹全局 toast，避免重复提示。
    const isCredentialEndpoint = url.includes('/auth/login') || url.includes('/auth/register')
    if (isCredentialEndpoint) {
      return Promise.reject(error)
    }

    const status = error?.response?.status
    if (status === 401) {
      // token 缺失 / 过期：清凭据 + 跳登录（而不是只弹一句提示后把人留在原地）
      notifyUnauthorized()
      return Promise.reject(error)
    }

    const message = error.response?.data?.message || error.message || '网络错误'
    ElMessage.error(message)
    return Promise.reject(error)
  }
)

export default service
