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

// ============================================================
//  认证（对应后端 AuthController / AuthServiceImpl）
// ============================================================

/** 后端返回的用户信息（User 实体已剥离 password）。 */
export interface AuthUser {
  id: number
  username: string
  nickname?: string
  email?: string
  avatar?: string
  status?: number
  createdAt?: string
  updatedAt?: string
}

/** POST /api/auth/login 请求体（对应 LoginRequest）。 */
export interface LoginRequest {
  username: string
  password: string
}

/** POST /api/auth/register 请求体（对应 RegisterRequest）。 */
export interface RegisterRequest {
  username: string
  password: string
  nickname?: string
  email?: string
}

/**
 * POST /api/auth/login 的响应 data 结构，与
 * {@code AuthServiceImpl#login} 写入的 Map 键完全一致：
 * {@code token / tokenType / expiresInHours / user}。
 */
export interface LoginResponse {
  token: string
  tokenType: string
  expiresInHours: number
  user: AuthUser
}

// ============================================================
//  聊天
// ============================================================

export interface ChatSession {
  id: string
  title: string
  createdAt: string
  updatedAt: string
  messageCount: number
}

export interface ThinkingStep {
  step: string
  message: string
}

export interface ChatMessage {
  id: string
  sessionId: string
  role: 'user' | 'assistant' | 'system'
  content: string
  sources?: SourceReference[]
  webResults?: SourceReference[]
  sandboxResults?: SandboxExecution[]
  thinkingSteps?: ThinkingStep[]
  createdAt: string
}

export interface SourceReference {
  id?: string
  documentId: string
  documentName: string
  content: string
  score: number
  chunkIndex: number
  // Backend SearchResult fields
  title?: string
  source?: string
}

// 检索策略：与后端 HybridRetrievalService.RetrievalStrategy + ChatServiceImpl 的
// RACE 分支对齐（后端按 toUpperCase 解析，前端统一小写）
export type RetrievalStrategy =
  | 'none'
  | 'dense'
  | 'sparse'
  | 'graph'
  | 'hybrid'
  | 'race'
  | 'full'

// 与后端 model.dto.ChatRequest 字段一一对应：
// message / sessionId / useMemory / retrievalStrategy / stream
export interface ChatRequest {
  sessionId: string
  message: string
  retrievalStrategy: RetrievalStrategy
  useMemory: boolean
  stream: boolean
}

// ============================================================
//  文档（对应后端 model.enums.DocumentStatus）
// ============================================================

/**
 * 文档处理状态。
 * <p>后端 {@code DocumentStatus} 是<b>数字</b>枚举，前端必须与之保持一致，
 * 否则 {@code 0/1/2…} 与字符串字面量互不兼容，类型检查会失败、展示也会错位。</p>
 */
export enum DocumentStatus {
  PENDING = 0,
  PROCESSING = 1,
  COMPLETED = 2,
  FAILED = 3,
  PARTIAL = 4,
}

export interface Document {
  id: string
  title: string
  fileType: string
  fileSize: number
  /** 后端返回的数字枚举，见 {@link DocumentStatus} */
  status: DocumentStatus
  chunkCount: number
  createdAt: string
  updatedAt: string
  tags?: string
  source?: string
  /** 最近一次处理的错误 / 降级说明：PARTIAL、FAILED 时用于定位「为什么检索不到」 */
  errorMessage?: string
}

// ============================================================
//  记忆
// ============================================================

/**
 * 记忆类型规范值（与后端统一口径）。
 * <p>后端写入时归一为大写，筛选大小写不敏感，因此前端一律使用大写 token，
 * 中文标签仅用于展示。</p>
 */
export const MEMORY_TYPES = ['FACT', 'PREFERENCE', 'KNOWLEDGE', 'HABIT', 'SUMMARY'] as const

export type MemoryType = (typeof MEMORY_TYPES)[number]

export interface Memory {
  id: string
  userId: string
  type: MemoryType
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

// ============================================================
//  评测（对应后端 EvaluationController / EvaluationServiceImpl）
// ============================================================

export interface EvaluationTask {
  id: string
  userId: string
  name: string
  /** 数字枚举，对应后端 EvaluationStatus：0 待评估 / 1 评估中 / 2 已完成 / 3 失败 */
  status: number
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

/**
 * 单个任务的概要 —— 对应后端
 * {@code EvaluationServiceImpl#buildTaskSummary} 返回的 Map。
 * <p>注意 {@code status} 这里是<b>字符串</b>（枚举名如 {@code COMPLETED}），
 * 与列表接口返回的数字枚举不同。</p>
 */
export interface EvaluationTaskSummary {
  id: number
  name: string
  retrievalStrategy: string | null
  modelId: string | null
  status: string
  totalQueries: number
  completedQueries: number
  /** 仅当该任务有结果时才存在 */
  averageLatencyMs?: number
  /** 仅当该任务有结果时才存在 */
  resultCount?: number
}

/**
 * 某一侧（A / B）的指标均值表。
 * <p>键为指标名；值可能是 {@code null}（表示该指标<b>未评估</b>，绝非 0 分）。
 * 另外每个指标都额外带一个 {@code <指标名>EvaluatedCount} 键，表示参与平均的真实样本数。</p>
 */
export type MetricAverages = Record<string, number | null>

/** 指标差值表：仅包含两侧都存在真实数值的指标。 */
export type MetricDeltas = Record<string, number>

/** 对应后端 {@code buildMetricsComparison} 返回的 Map。 */
export interface EvaluationMetricsComparison {
  retrievalMetricsA: MetricAverages
  retrievalMetricsB: MetricAverages
  retrievalDelta: MetricDeltas
  generationMetricsA: MetricAverages
  generationMetricsB: MetricAverages
  generationDelta: MetricDeltas
}

/**
 * 对比结果 —— 对应后端 {@code EvaluationServiceImpl#compareResults}
 * 返回的 Map：{@code taskA / taskB / metricsComparison}。
 */
export interface EvaluationComparison {
  taskA: EvaluationTaskSummary
  taskB: EvaluationTaskSummary
  metricsComparison: EvaluationMetricsComparison
}

// ============================================================
//  沙箱
// ============================================================

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

export interface SandboxExecution {
  language: string
  code: string
  output: string
  error: string
  exitCode: number
  executionTime: number
}
