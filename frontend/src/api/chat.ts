import request from '@/utils/request'
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

export function streamChat(
  req: ChatRequest,
  callbacks: StreamCallbacks
): () => void {
  const controller = new AbortController()
  const userId = localStorage.getItem('userId') || '1'

  fetch('/api/chat/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json;charset=UTF-8',
      'X-User-Id': userId,
    },
    body: JSON.stringify(req),
    signal: controller.signal,
  })
    .then(async (response) => {
      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`)
      }
      const reader = response.body?.getReader()
      if (!reader) {
        throw new Error('No reader available')
      }
      const decoder = new TextDecoder()
      let buffer = ''
      let currentEvent = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) {
          callbacks.onDone()
          break
        }
        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''

        for (const line of lines) {
          const trimmed = line.trim()
          if (!trimmed) continue

          if (trimmed === 'data: [DONE]') {
            callbacks.onDone()
            return
          }

          // Capture SSE event name
          if (trimmed.startsWith('event:')) {
            currentEvent = trimmed.slice(6).trim()
            continue
          }

          if (trimmed.startsWith('data:')) {
            const data = trimmed.slice(5).trim()
            if (!data) continue
            try {
              const parsed = JSON.parse(data)
              // Route by SSE event name (set by backend emitter.send(...).name(...))
              if (currentEvent === 'sandbox' && callbacks.onSandbox) {
                callbacks.onSandbox(parsed)
              } else if (currentEvent === 'websearch' && callbacks.onWebSearch) {
                callbacks.onWebSearch(parsed.content || parsed)
              } else if (currentEvent === 'source' && callbacks.onSource) {
                callbacks.onSource(parsed.content || parsed)
              } else if (currentEvent === 'thinking' && callbacks.onThinking) {
                callbacks.onThinking(parsed.step, parsed.message)
              } else if (currentEvent === 'content' || parsed.type === 'content') {
                callbacks.onMessage(parsed.content || parsed)
              } else if (parsed.type === 'done') {
                callbacks.onDone()
                return
              } else if (parsed.content) {
                callbacks.onMessage(parsed.content)
              }
              // Reset event name after processing data
              currentEvent = ''
            } catch {
              callbacks.onMessage(data)
              currentEvent = ''
            }
          }
        }
      }
    })
    .catch((error) => {
      if (error.name !== 'AbortError') {
        callbacks.onError(error)
      }
    })

  return () => controller.abort()
}
