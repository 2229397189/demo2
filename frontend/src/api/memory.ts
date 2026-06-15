import request from '@/utils/request'
import type { Result, Memory } from '@/types'

export function getMemories(userId: string, type?: string, limit = 20): Promise<Result<Memory[]>> {
  return request.get(`/memory/${userId}`, { params: { type, limit } })
}

export function searchMemories(query: string, userId: string, topK = 10): Promise<Result<Memory[]>> {
  return request.post('/memory/search', { userId: Number(userId), query, topK })
}

export function getUserProfile(userId: string): Promise<Result<Record<string, unknown>>> {
  return request.get(`/memory/profile/${userId}`)
}
