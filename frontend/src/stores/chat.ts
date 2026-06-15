import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { ChatSession, ChatMessage, ChatRequest, SourceReference } from '@/types'
import * as chatApi from '@/api/chat'

/** Normalize backend session (number IDs, no messageCount) to frontend type */
function normalizeSession(raw: any): ChatSession {
  return {
    id: String(raw.id),
    title: raw.title || 'New Chat',
    createdAt: raw.createdAt,
    updatedAt: raw.updatedAt,
    messageCount: raw.messageCount ?? 0,
  }
}

/** Normalize backend message (number IDs) to frontend type */
function normalizeMessage(raw: any): ChatMessage {
  return {
    id: String(raw.id),
    sessionId: String(raw.sessionId),
    role: raw.role,
    content: raw.content || '',
    sources: raw.sources,
    createdAt: raw.createdAt,
  }
}

export const useChatStore = defineStore('chat', () => {
  const sessions = ref<ChatSession[]>([])
  const currentSession = ref<ChatSession | null>(null)
  const messages = ref<ChatMessage[]>([])
  const isLoading = ref(false)
  const isStreaming = ref(false)
  const streamContent = ref('')
  const streamSources = ref<SourceReference[]>([])

  const sortedSessions = computed(() =>
    [...sessions.value].sort((a, b) =>
      new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime()
    )
  )

  async function loadSessions() {
    try {
      const res = await chatApi.listSessions()
      sessions.value = (res.data as any[]).map(normalizeSession)
    } catch (error) {
      console.error('Failed to load sessions:', error)
    }
  }

  async function createSession(title?: string) {
    try {
      const res = await chatApi.createSession(title)
      const session = normalizeSession(res.data)
      sessions.value.unshift(session)
      currentSession.value = session
      messages.value = []
      return session
    } catch (error) {
      console.error('Failed to create session:', error)
      throw error
    }
  }

  async function deleteSession(sessionId: string) {
    try {
      await chatApi.deleteSession(sessionId)
      sessions.value = sessions.value.filter(s => s.id !== sessionId)
      if (currentSession.value?.id === sessionId) {
        currentSession.value = null
        messages.value = []
      }
    } catch (error) {
      console.error('Failed to delete session:', error)
      throw error
    }
  }

  async function selectSession(session: ChatSession) {
    currentSession.value = session
    await loadMessages(session.id)
  }

  async function loadMessages(sessionId: string) {
    try {
      const res = await chatApi.getMessages(sessionId)
      messages.value = (res.data as any[]).map(normalizeMessage)
    } catch (error) {
      console.error('Failed to load messages:', error)
    }
  }

  function sendMessage(
    content: string,
    retrievalStrategy: 'dense' | 'sparse' | 'graph' | 'hybrid' = 'hybrid',
    useMemory = true
  ) {
    if (!currentSession.value || isStreaming.value) return

    const userMessage: ChatMessage = {
      id: Date.now().toString(),
      sessionId: currentSession.value.id,
      role: 'user',
      content,
      createdAt: new Date().toISOString(),
    }
    messages.value.push(userMessage)

    const req: ChatRequest = {
      sessionId: currentSession.value.id,
      message: content,
      retrievalStrategy,
      useMemory,
      stream: true,
    }

    isStreaming.value = true
    streamContent.value = ''
    streamSources.value = []

    const assistantMessage: ChatMessage = {
      id: (Date.now() + 1).toString(),
      sessionId: currentSession.value.id,
      role: 'assistant',
      content: '',
      sources: [],
      createdAt: new Date().toISOString(),
    }
    messages.value.push(assistantMessage)

    chatApi.streamChat(
      req,
      (chunk: string) => {
        streamContent.value += chunk
        assistantMessage.content = streamContent.value
      },
      () => {
        isStreaming.value = false
        assistantMessage.sources = [...streamSources.value]
        const session = sessions.value.find(s => s.id === currentSession.value?.id)
        if (session) {
          session.updatedAt = new Date().toISOString()
          session.messageCount += 2
        }
      },
      (error: Error) => {
        isStreaming.value = false
        assistantMessage.content += '\n\n[流式响应中断: ' + error.message + ']'
        console.error('Stream error:', error)
      }
    )
  }

  return {
    sessions,
    currentSession,
    messages,
    isLoading,
    isStreaming,
    streamContent,
    streamSources,
    sortedSessions,
    loadSessions,
    createSession,
    deleteSession,
    selectSession,
    loadMessages,
    sendMessage,
  }
})
