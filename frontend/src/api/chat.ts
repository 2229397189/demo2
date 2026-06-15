import request from '@/utils/request'
import type { Result, ChatSession, ChatMessage } from '@/types'

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

export function streamChat(
  req: ChatRequest,
  onMessage: (chunk: string) => void,
  onDone: () => void,
  onError: (error: Error) => void
): () => void {
  const controller = new AbortController()
  const userId = localStorage.getItem('userId') || '1'

  fetch('/api/chat/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
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

      while (true) {
        const { done, value } = await reader.read()
        if (done) {
          onDone()
          break
        }
        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''

        for (const line of lines) {
          const trimmed = line.trim()
          if (!trimmed) continue

          // Handle [DONE] marker (OpenAI SSE convention)
          if (trimmed === 'data: [DONE]') {
            onDone()
            return
          }

          // Handle data lines (may follow an event: line)
          if (trimmed.startsWith('data:')) {
            const data = trimmed.slice(5).trim()
            if (!data) continue
            try {
              const parsed = JSON.parse(data)
              // Handle ChatStreamEvent format: { type: "content", content: "..." }
              if (parsed.type === 'content' && parsed.content) {
                onMessage(parsed.content)
              } else if (parsed.type === 'done') {
                onDone()
                return
              } else if (parsed.content) {
                onMessage(parsed.content)
              }
            } catch {
              // Plain text data
              onMessage(data)
            }
          }
        }
      }
    })
    .catch((error) => {
      if (error.name !== 'AbortError') {
        onError(error)
      }
    })

  return () => controller.abort()
}
