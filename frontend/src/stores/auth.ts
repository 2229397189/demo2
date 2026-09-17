import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { AuthUser, LoginRequest, LoginResponse, RegisterRequest } from '@/types'
import * as authApi from '@/api/auth'
import {
  getToken,
  getStoredUser,
  persistAuth,
  clearAuth,
  getStoredUserId,
} from '@/utils/authStorage'

/**
 * 认证状态。
 *
 * <p>职责：保存 token 与当前用户；提供登录 / 注册 / 拉取当前用户 / 登出；
 * 并提供一个「后端是否强制鉴权」的探测方法，供路由守卫优雅处理本地
 * {@code AUTH_ENABLED=false} 的情况。</p>
 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(getToken())
  const user = ref<AuthUser | null>(getStoredUser())

  /**
   * 后端是否强制鉴权。
   * <ul>
   *   <li>{@code null}：尚未探测</li>
   *   <li>{@code true}：{@code AUTH_ENABLED=true}，未登录必须去登录页</li>
   *   <li>{@code false}：{@code AUTH_ENABLED=false}（本地调试），不打扰用户</li>
   * </ul>
   */
  const authRequired = ref<boolean | null>(null)

  function applyAuth(data: LoginResponse): void {
    token.value = data.token
    user.value = data.user
    persistAuth(data.token, data.user)
    // 登录成功即说明后端确实在鉴权（否则不会签发 token）
    authRequired.value = true
  }

  /** 登录。失败时抛出错误（由调用方就地展示）。 */
  async function login(payload: LoginRequest): Promise<AuthUser> {
    const res = await authApi.login(payload)
    applyAuth(res.data)
    return res.data.user
  }

  /** 注册成功后自动登录，返回登录后的用户信息。 */
  async function register(payload: RegisterRequest): Promise<AuthUser> {
    await authApi.register(payload)
    return login({ username: payload.username, password: payload.password })
  }

  /**
   * 拉取当前登录用户。
   * 失败不抛出（用于启动时静默校对 token 是否仍有效）——
   * token 真的失效时，401 处理链路会统一清凭据并跳登录。
   */
  async function fetchMe(): Promise<AuthUser | null> {
    try {
      const res = await authApi.me()
      user.value = res.data
      persistAuth(token.value || '', res.data)
      return res.data
    } catch {
      return null
    }
  }

  /** 登出：尽力通知后端（无状态，仅占位），随后清空本地凭据。 */
  async function logout(): Promise<void> {
    try {
      await authApi.logout()
    } catch {
      // 无状态 JWT：后端失败也不影响「本地丢弃 token」这一唯一有效动作
    } finally {
      token.value = null
      user.value = null
      clearAuth()
    }
  }

  /**
   * 探测后端是否强制鉴权。
   * <p>用一个<b>必然需要身份</b>的业务只读接口（{@code GET /api/chat/sessions}）做探针：
   * {@code AUTH_ENABLED=false} 时后端用 {@code X-User-Id} 兜底返回 200；
   * {@code AUTH_ENABLED=true} 且无 token 时返回 401。据此区分「真的需要登录」与
   * 「本地关鉴权」，避免本地开发被登录页锁死。</p>
   * <p>结果只探测一次并缓存；后端不可达时按「不强制」处理（避免把「后端没起」
   * 误判为「需要登录」）。</p>
   */
  async function detectAuthRequired(): Promise<boolean> {
    if (authRequired.value !== null) {
      return authRequired.value
    }
    try {
      const res = await fetch('/api/chat/sessions', {
        method: 'GET',
        headers: { 'X-User-Id': getStoredUserId() },
      })
      authRequired.value = res.status === 401
    } catch {
      authRequired.value = false
    }
    return authRequired.value
  }

  return {
    token,
    user,
    authRequired,
    login,
    register,
    fetchMe,
    logout,
    detectAuthRequired,
  }
})
