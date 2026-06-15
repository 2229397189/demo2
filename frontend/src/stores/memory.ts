import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Memory } from '@/types'
import * as memoryApi from '@/api/memory'

// Backend memory types differ from frontend display types
// Backend: short_term, long_term, graph, runtime
// Frontend: fact, preference, interaction, summary
const TYPE_MAP: Record<string, Memory['type']> = {
  short_term: 'fact',
  long_term: 'summary',
  graph: 'preference',
  runtime: 'interaction',
  fact: 'fact',
  preference: 'preference',
  interaction: 'interaction',
  summary: 'summary',
}

function normalizeMemory(raw: any): Memory {
  return {
    id: String(raw.id),
    userId: String(raw.userId ?? ''),
    type: TYPE_MAP[raw.type] || 'fact',
    content: raw.content || '',
    importance: raw.importance ?? 0,
    createdAt: raw.createdAt,
    updatedAt: raw.updatedAt,
    metadata: raw.metadata,
  }
}

export const useMemoryStore = defineStore('memory', () => {
  const memories = ref<Memory[]>([])
  const userProfile = ref<Record<string, unknown> | null>(null)
  const searchResults = ref<Memory[]>([])
  const isLoading = ref(false)
  const searchQuery = ref('')
  const activeType = ref<string>('all')
  const userId = ref('1')

  async function loadMemories(type?: string) {
    isLoading.value = true
    try {
      const res = await memoryApi.getMemories(userId.value, type === 'all' ? undefined : type)
      memories.value = (res.data as any[]).map(normalizeMemory)
    } catch (error) {
      console.error('Failed to load memories:', error)
    } finally {
      isLoading.value = false
    }
  }

  async function searchMemory(query: string) {
    if (!query.trim()) {
      searchResults.value = []
      return
    }
    isLoading.value = true
    try {
      const res = await memoryApi.searchMemories(query, userId.value)
      searchResults.value = (res.data as any[]).map(normalizeMemory)
    } catch (error) {
      console.error('Failed to search memories:', error)
    } finally {
      isLoading.value = false
    }
  }

  async function loadUserProfile() {
    try {
      const res = await memoryApi.getUserProfile(userId.value)
      userProfile.value = res.data as Record<string, unknown>
    } catch (error) {
      console.error('Failed to load user profile:', error)
    }
  }

  function setActiveType(type: string) {
    activeType.value = type
    loadMemories(type)
  }

  return {
    memories,
    userProfile,
    searchResults,
    isLoading,
    searchQuery,
    activeType,
    userId,
    loadMemories,
    searchMemory,
    loadUserProfile,
    setActiveType,
  }
})
