import request from '@/utils/request'
import type { Result, PageResult, Document } from '@/types'

export function uploadDocument(file: File): Promise<Result<Document>> {
  const formData = new FormData()
  formData.append('file', file)
  return request.post('/documents/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

export function listDocuments(page = 1, size = 20): Promise<Result<PageResult<Document>>> {
  return request.get('/documents', { params: { page, size } })
}

export function getDocument(id: string): Promise<Result<Document>> {
  return request.get(`/documents/${id}`)
}

export function deleteDocument(id: string): Promise<Result<void>> {
  return request.delete(`/documents/${id}`)
}

export function processDocument(id: string): Promise<Result<void>> {
  return request.post(`/documents/${id}/process`)
}
