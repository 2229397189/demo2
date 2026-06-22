import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { ChatSession, ChatMessage, ChatRequest, SourceReference, SandboxExecution } from '@/types'
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
  const streamWebResults = ref<SourceReference[]>([])
  const streamSandboxResults = ref<SandboxExecution[]>([])
  const thinkingSteps = ref<Array<{step: string, message: string}>>([])

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
    retrievalStrategy: 'none' | 'dense' | 'sparse' | 'graph' | 'hybrid' = 'none',
    useMemory = false
  ) {
    if (!currentSession.value || isStreaming.value) return

    const userMessage: ChatMessage = {
      id: Date.now().toString(),
      sessionId: currentSession.value.id,
      role: 'user',
      content,
      createdAt: new Date().toISOString(),
    }
    messages.value = [...messages.value, userMessage]

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
    streamWebResults.value = []
    streamSandboxResults.value = []
    thinkingSteps.value = []

    const assistantMessage: ChatMessage = {
      id: (Date.now() + 1).toString(),
      sessionId: currentSession.value.id,
      role: 'assistant',
      content: '',
      sources: [],
      webResults: [],
      sandboxResults: [],
      thinkingSteps: [],
      createdAt: new Date().toISOString(),
    }
    messages.value = [...messages.value, assistantMessage]
    const assistantIndex = messages.value.length - 1

    // 更新消息并触发响应式 - 使用新数组引用来确保 Vue 检测到变化
    const updateMessage = () => {
      const newMessages = [...messages.value]
      newMessages[assistantIndex] = {
        ...newMessages[assistantIndex],
        content: streamContent.value,
        thinkingSteps: [...thinkingSteps.value],
        sources: [...streamSources.value],
        webResults: [...streamWebResults.value],
        sandboxResults: [...streamSandboxResults.value],
      }
      messages.value = newMessages
    }

    chatApi.streamChat(req, {
      onMessage(chunk: string) {
        streamContent.value += chunk
        updateMessage()
      },
      onDone() {
        isStreaming.value = false
        updateMessage()
        const session = sessions.value.find(s => s.id === currentSession.value?.id)
        if (session) {
          session.updatedAt = new Date().toISOString()
          session.messageCount = (session.messageCount || 0) + 2
        }
      },
      onError(error: Error) {
        isStreaming.value = false
        const newMessages = [...messages.value]
        newMessages[assistantIndex] = {
          ...newMessages[assistantIndex],
          content: streamContent.value + '\n\n[流式响应中断: ' + error.message + ']',
        }
        messages.value = newMessages
        console.error('Stream error:', error)
      },
      onSource(sources: SourceReference[]) {
        streamSources.value = sources.map((s: any) => ({
          id: s.id,
          documentId: s.documentId || '',
          documentName: s.documentName || s.title || '',
          content: s.content || '',
          score: s.score || 0,
          chunkIndex: s.chunkIndex || 0,
        }))
        updateMessage()
      },
      onWebSearch(results: SourceReference[]) {
        streamWebResults.value = results.map((s: any) => ({
          id: s.id,
          documentId: s.documentId || '',
          documentName: s.documentName || s.title || '',
          content: s.content || '',
          score: s.score || 0,
          chunkIndex: s.chunkIndex || 0,
          source: s.source || '',
        }))
        updateMessage()
      },
      onSandbox(result: SandboxExecution) {
        streamSandboxResults.value.push(result)
        // Also append sandbox output to message content for visibility
        const sandboxOutput = result.error
          ? `\n\n> [代码执行错误] ${result.error}`
          : result.output
            ? `\n\n> [代码执行结果]\n> ${result.output.trim().replace(/\n/g, '\n> ')}`
            : ''
        if (sandboxOutput) {
          streamContent.value += sandboxOutput
          updateMessage()
        }
      },
      onThinking(step: string, message: string) {
        if (step === 'done') {
          // 生成完成，清除思考过程
          thinkingSteps.value = []
        } else {
          thinkingSteps.value.push({ step, message })
        }
        updateMessage()
      },
    })
  }

  return {
    sessions,
    currentSession,
    messages,
    isLoading,
    isStreaming,
    streamContent,
    streamSources,
    streamWebResults,
    streamSandboxResults,
    thinkingSteps,
    sortedSessions,
    loadSessions,
    createSession,
    deleteSession,
    selectSession,
    loadMessages,
    sendMessage,
  }
})
