/**
 * 认证信息的本地存储 —— 全局单一来源。
 *
 * <p>此前 {@code token} 的读取逻辑散落在 {@code utils/request.ts}（axios 拦截器）与
 * {@code api/chat.ts}（原生 fetch 的 SSE 流）两处，而写入逻辑一处都没有 ——
 * 于是「读得到、写不进」，无论怎么调接口都不会带上 Authorization。
 * 这里把读 / 写 / 清统一收口，axios 与 SSE 都从本模块取值，杜绝两份实现。</p>
 *
 * <p>存储键沿用历史值（{@code token} / {@code userId}），避免破坏既有本地数据；
 * 新增 {@code authUser} 保存登录返回的用户信息。</p>
 */

import type { AuthUser } from '@/types'

const TOKEN_KEY = 'token'
const USER_KEY = 'authUser'
const USER_ID_KEY = 'userId'

/**
 * 后端 {@code AuthInterceptor} 在 {@code AUTH_ENABLED=false}（本地调试）时，
 * 会用请求头 {@code X-User-Id} 兜底身份、缺省为 1。前端在未登录时沿用同一缺省值，
 * 保证「本地关鉴权」场景下接口照常可用。
 */
export const DEFAULT_USER_ID = '1'

/** 读取 JWT。 */
export function getToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY)
  } catch {
    return null
  }
}

/** 写入 JWT。 */
export function setToken(token: string): void {
  try {
    localStorage.setItem(TOKEN_KEY, token)
  } catch {
    /* localStorage 不可用（隐私模式等）时静默降级，不影响主流程 */
  }
}

/** 删除 JWT。 */
export function removeToken(): void {
  try {
    localStorage.removeItem(TOKEN_KEY)
  } catch {
    /* ignore */
  }
}

/** 读取已登录用户信息（未登录返回 null）。 */
export function getStoredUser(): AuthUser | null {
  try {
    const raw = localStorage.getItem(USER_KEY)
    return raw ? (JSON.parse(raw) as AuthUser) : null
  } catch {
    return null
  }
}

/**
 * 写入 / 清除用户信息。
 * <p>写入时会同步刷新 {@code userId}（供 SSE 与 axios 的 X-User-Id 使用）；
 * 清除时把 {@code userId} 一并移除，使 {@link getStoredUserId} 回落到缺省值。</p>
 */
export function setStoredUser(user: AuthUser | null): void {
  try {
    if (user) {
      localStorage.setItem(USER_KEY, JSON.stringify(user))
      localStorage.setItem(USER_ID_KEY, String(user.id))
    } else {
      localStorage.removeItem(USER_KEY)
      localStorage.removeItem(USER_ID_KEY)
    }
  } catch {
    /* ignore */
  }
}

/** 读取当前用户 ID（未登录时回落到 {@link DEFAULT_USER_ID}）。 */
export function getStoredUserId(): string {
  try {
    return localStorage.getItem(USER_ID_KEY) || DEFAULT_USER_ID
  } catch {
    return DEFAULT_USER_ID
  }
}

/** 一次性持久化「登录成功」结果（token + 用户信息）。 */
export function persistAuth(token: string, user: AuthUser): void {
  setToken(token)
  setStoredUser(user)
}

/** 清除全部认证痕迹（登出 / token 失效）。 */
export function clearAuth(): void {
  removeToken()
  setStoredUser(null)
}
