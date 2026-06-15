export interface Result<T> {
  code: number
  message: string
  data: T
}

export interface PageResult<T> {
  records: T[]
  total: number
  page: number
  size: number
}

export interface ChatSession {
  id: string
  title: string
  createdAt: string
  updatedAt: string
  messageCount: number
}

export interface ChatMessage {
  id: string
  sessionId: string
  role: 'user' | 'assistant' | 'system'
  content: string
  sources?: SourceReference[]
  createdAt: string
}

export interface SourceReference {
  id: string
  documentId: string
  documentName: string
  content: string
  score: number
  chunkIndex: number
}

export interface ChatRequest {
  sessionId: string
  message: string
  retrievalStrategy: 'dense' | 'sparse' | 'graph' | 'hybrid'
  useMemory: boolean
  stream: boolean
}

export interface Document {
  id: string
  title: string
  fileType: string
  fileSize: number
  status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | number
  chunkCount: number
  createdAt: string
  updatedAt: string
  tags?: string
  source?: string
}

export interface Memory {
  id: string
  userId: string
  type: 'fact' | 'preference' | 'interaction' | 'summary'
  content: string
  importance: number
  createdAt: string
  updatedAt: string
  metadata?: Record<string, unknown>
}

export interface UserProfile {
  userId: string
  name: string
  preferences: Record<string, unknown>
  facts: string[]
  summary: string
  lastActive: string
}

export interface EvaluationTask {
  id: string
  userId: string
  name: string
  status: string | number
  datasetId: string
  retrievalStrategy?: string
  modelId?: string
  totalQueries: number
  completedQueries: number
  createdAt: string
  updatedAt: string
}

export interface EvaluationResult {
  id: string
  taskId: string
  queryId: string
  query: string
  expectedAnswer: string
  generatedAnswer: string
  retrievedDocIds?: string
  retrievalMetrics?: string
  generationMetrics?: string
  latencyMs: number
  createdAt: string
}

export interface EvaluationComparison {
  taskIds: string[]
  taskNames: string[]
  metrics: Record<string, number[]>
  summary: Record<string, { avg: number; min: number; max: number }>
}

export interface SandboxRequest {
  language: 'python' | 'javascript' | 'java'
  code: string
  timeout: number
}

export interface SandboxResponse {
  output: string
  error: string
  exitCode: number
  executionTime: number
  memoryUsage: number
}
