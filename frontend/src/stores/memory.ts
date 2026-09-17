import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Memory, MemoryType } from '@/types'
import * as memoryApi from '@/api/memory'
import { getStoredUserId } from '@/utils/authStorage'

/**
 * 记忆类型规范值（单一来源：与后端统一口径）。
 * 历史后端曾存 short_term/long_term/graph/runtime 与小写 fact/preference，
 * 这里做一次兜底归一，避免旧数据在新前端里显示为「未知类型」。
 */
const CANONICAL_TYPES: MemoryType[] = ['FACT', 'PREFERENCE', 'KNOWLEDGE', 'HABIT', 'SUMMARY']

const LEGACY_TYPE_ALIASES: Record<string, MemoryType> = {
  SHORT_TERM: 'FACT',
  LONG_TERM: 'SUMMARY',
  GRAPH: 'KNOWLEDGE',
  RUNTIME: 'HABIT',
  INTERACTION: 'HABIT',
}

/** 把任意大小写 / 历史别名归一为规范记忆类型。 */
export function normalizeMemoryType(rawType?: string): MemoryType {
  if (!rawType) return 'FACT'
  const upper = rawType.toUpperCase()
  if ((CANONICAL_TYPES as string[]).includes(upper)) {
    return upper as MemoryType
  }
  return LEGACY_TYPE_ALIASES[upper] ?? 'FACT'
}

function normalizeMemory(raw: any): Memory {
  return {
    id: String(raw.id),
    userId: String(raw.userId ?? ''),
    type: normalizeMemoryType(raw.type),
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
  // 后端路径 /memory/{userId} 要求与当前登录用户一致，故跟随登录态
  const userId = ref<string>(getStoredUserId())

  async function loadMemories(type?: string) {
    isLoading.value = true
    try {
      userId.value = getStoredUserId()
      // 'all' → 不传 type，拉全部
      const filter = !type || type === 'all' ? undefined : type
      const res = await memoryApi.getMemories(userId.value, filter)
      memories.value = (res.data as any[]).map(normalizeMemory)
    } catch (error) {
      // 统一错误提示由 utils/request.ts 的响应拦截器负责，此处仅留调试日志，避免重复弹两次
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
      userId.value = getStoredUserId()
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
      userId.value = getStoredUserId()
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
