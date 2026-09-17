import request, { notifyUnauthorized } from '@/utils/request'
import { getToken, getStoredUserId } from '@/utils/authStorage'
import type { Result, ChatSession, ChatMessage, ChatRequest, SourceReference, SandboxExecution } from '@/types'

export function listSessions(): Promise<Result<ChatSession[]>> {
  return request.get('/chat/sessions')
}

export function createSession(title?: string): Promise<Result<ChatSession>> {
  return request.post('/chat/sessions', null, { params: title ? { title } : {} })
}

export function deleteSession(sessionId: string): Promise<Result<void>> {
  return request.delete(`/chat/sessions/${sessionId}`)
}

export function getMessages(sessionId: string): Promise<Result<ChatMessage[]>> {
  return request.get(`/chat/sessions/${sessionId}/messages`)
}

export interface StreamCallbacks {
  onMessage: (chunk: string) => void
  onDone: () => void
  onError: (error: Error) => void
  onSource?: (sources: SourceReference[]) => void
  onWebSearch?: (results: SourceReference[]) => void
  onSandbox?: (result: SandboxExecution) => void
  onThinking?: (step: string, message: string) => void
}

/** 从后端错误事件的负载里抽出可读的错误文案。 */
function extractErrorMessage(parsed: unknown, raw: string): string {
  if (parsed && typeof parsed === 'object') {
    const obj = parsed as Record<string, unknown>
    const candidate = obj.error ?? obj.message ?? obj.content
    if (typeof candidate === 'string' && candidate.trim()) {
      return candidate
    }
  }
  if (typeof parsed === 'string' && parsed.trim()) {
    return parsed
  }
  return raw.trim() || '服务端返回了一个错误'
}

/**
 * 原生 fetch 直连 SSE 流。
 *
 * <p>修复要点：</p>
 * <ol>
 *   <li><b>补 Authorization</b>：此前只用 heading Content-Type + X-User-Id，
 *       绕过了 axios 拦截器，AUTH_ENABLED=true 时直接 401。现与 utils/request.ts
 *       共用同一套取 token 逻辑（utils/authStorage）。
 *       401 时走统一的登录态失效处理（提示 + 跳登录），不再静默失败。</li>
 *   <li><b>处理 event: error</b>：后端会用 {@code .name("error")} 推送错误
 *       （如输入被安全策略拦截），此前没有该分支，错误被当成正文或丢弃。</li>
 *   <li><b>以「流读取结束」作为完成信号</b>：后端从不发送 {@code [DONE]}，
 *       因此不再依赖它（同时兼容其出现），并保证 onDone / onError 只触发一次。</li>
 * </ol>
 *
 * @returns 取消函数（调用即中断流）
 */
export function streamChat(
  req: ChatRequest,
  callbacks: StreamCallbacks
): () => void {
  const controller = new AbortController()

  const headers: Record<string, string> = {
    'Content-Type': 'application/json;charset=UTF-8',
    'X-User-Id': getStoredUserId(),
  }
  const token = getToken()
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }

  let finished = false
  const finish = () => {
    if (finished) return
    finished = true
    callbacks.onDone()
  }
  const fail = (error: Error) => {
    if (finished) return
    finished = true
    callbacks.onError(error)
  }

  /** 按 SSE 事件名分发单条 data。 */
  const dispatch = (eventName: string, data: string): void => {
    if (!data) return
    let parsed: unknown = null
    let isJson = false
    try {
      parsed = JSON.parse(data)
      isJson = true
    } catch {
      parsed = null
      isJson = false
    }

    const parsedType =
      isJson && parsed && typeof parsed === 'object' && typeof (parsed as Record<string, unknown>).type === 'string'
        ? ((parsed as Record<string, unknown>).type as string)
        : ''
    const name = eventName || parsedType

    switch (name) {
      case 'error':
        // 明确告知用户，而不是把错误当正文
        fail(new Error(extractErrorMessage(parsed, isJson ? '' : data)))
        return
      case 'sandbox':
        callbacks.onSandbox?.(parsed as SandboxExecution)
        return
      case 'websearch': {
        const payload =
          isJson && parsed && typeof parsed === 'object' && 'content' in (parsed as Record<string, unknown>)
            ? (parsed as Record<string, unknown>).content
            : parsed
        callbacks.onWebSearch?.(payload as SourceReference[])
        return
      }
      case 'source': {
        const payload =
          isJson && parsed && typeof parsed === 'object' && 'content' in (parsed as Record<string, unknown>)
            ? (parsed as Record<string, unknown>).content
            : parsed
        callbacks.onSource?.(payload as SourceReference[])
        return
      }
      case 'thinking': {
        const obj = (parsed ?? {}) as Record<string, unknown>
        callbacks.onThinking?.(String(obj.step ?? ''), String(obj.message ?? ''))
        return
      }
      case 'content':
        callbacks.onMessage(
          isJson && parsed && typeof parsed === 'object' && typeof (parsed as Record<string, unknown>).content === 'string'
            ? ((parsed as Record<string, unknown>).content as string)
            : isJson
              ? ''
              : data
        )
        return
      default:
        // 无显式事件名：兼容 {type:'content'} / 纯字符串 / 直接 JSON
        if (isJson && parsed && typeof parsed === 'object') {
          const obj = parsed as Record<string, unknown>
          if (typeof obj.content === 'string') {
            callbacks.onMessage(obj.content)
            return
          }
          if (typeof obj.error === 'string') {
            fail(new Error(obj.error))
            return
          }
        }
        if (typeof parsed === 'string') {
          callbacks.onMessage(parsed)
          return
        }
        callbacks.onMessage(data)
    }
  }

  fetch('/api/chat/stream', {
    method: 'POST',
    headers,
    body: JSON.stringify(req),
    signal: controller.signal,
  })
    .then(async (response) => {
      if (response.status === 401) {
        notifyUnauthorized()
        throw new Error('未登录或登录状态已失效，请重新登录')
      }
      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`)
      }
      const reader = response.body?.getReader()
      if (!reader) {
        throw new Error('当前浏览器不支持流式响应')
      }

      const decoder = new TextDecoder()
      let buffer = ''
      let currentEvent = ''
      let sawDone = false

      while (!finished && !sawDone) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() ?? ''

        for (const line of lines) {
          if (finished) break
          const trimmed = line.trim()
          if (!trimmed || trimmed.startsWith(':')) continue
          if (trimmed.startsWith('event:')) {
            currentEvent = trimmed.slice(6).trim()
            continue
          }
          if (!trimmed.startsWith('data:')) continue
          let data = trimmed.slice(5)
          if (data.startsWith(' ')) data = data.slice(1)
          if (data === '[DONE]') {
            currentEvent = ''
            sawDone = true
            break
          }
          const eventName = currentEvent
          currentEvent = ''
          dispatch(eventName, data)
        }
      }

      // 结尾残留（最后一行没有换行符）
      if (!finished && !sawDone) {
        const tail = buffer.trim()
        if (tail.startsWith('data:')) {
          let data = tail.slice(5)
          if (data.startsWith(' ')) data = data.slice(1)
          if (data && data !== '[DONE]') dispatch(currentEvent, data)
        }
      }

      finish()
    })
    .catch((error) => {
      // 用户主动停止：中断态由调用方（store）处理，不当作错误上报
      if (error && error.name === 'AbortError') return
      fail(error instanceof Error ? error : new Error(String(error)))
    })

  return () => controller.abort()
}
