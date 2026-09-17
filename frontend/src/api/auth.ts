import request from '@/utils/request'
import type { Result, AuthUser, LoginRequest, LoginResponse, RegisterRequest } from '@/types'

// 后端 AuthController（@RequestMapping("/api/auth")）真实端点：
//   POST /api/auth/register  → RegisterRequest  → Result<User>
//   POST /api/auth/login     → LoginRequest     → Result<Map>{token,tokenType,expiresInHours,user}
//   GET  /api/auth/me        → (Bearer token)   → Result<User>
//   POST /api/auth/logout    → (Bearer token)   → Result<Void>
// 注意：后端没有 refresh 端点，此处不提供。

/** 登录：换取 JWT。 */
export function login(payload: LoginRequest): Promise<Result<LoginResponse>> {
  return request.post('/auth/login', payload)
}

/** 注册：创建新用户（后端返回用户信息，不含 token）。 */
export function register(payload: RegisterRequest): Promise<Result<AuthUser>> {
  return request.post('/auth/register', payload)
}

/** 获取当前登录用户（依据请求头 token 解析）。 */
export function me(): Promise<Result<AuthUser>> {
  return request.get('/auth/me')
}

/** 登出：JWT 无状态，服务端仅作语义占位，客户端需自行丢弃 token。 */
export function logout(): Promise<Result<void>> {
  return request.post('/auth/logout')
}
