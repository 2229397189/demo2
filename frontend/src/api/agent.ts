import request from '@/utils/request'
import type { Result } from '@/types'

// 后端 AgentController（@RequestMapping("/api/agent")）真实端点：
//   GET    /api/agent/tools               → Result<Array<{name,description,riskLevel,status,lastExecutedAt}>>
//   POST   /api/agent/tools/{name}/execute → Result<Map>（工具执行结果，失败返回 Result.fail）
//   POST   /api/agent/dag/execute         → Result<{results, nodeStatus}>
//   GET    /api/agent/harness/status      → Result<{taskStatuses, threadPool}>
//   GET    /api/agent/audit/logs?userId=&limit= → Result<AuditLog[]>

/** 已注册工具的描述（对应 ToolRegistry.ToolDefinition 的对外视图）。 */
export interface AgentTool {
  name: string
  description: string
  riskLevel: string | null
  status: string | null
  lastExecutedAt: string | null
}

/** DAG 节点定义（对应 AgentController.DagNodeSpec）。 */
export interface DagNodeSpec {
  id: string
  type: 'LLM_CALL' | 'TOOL_CALL' | 'RAG_RETRIEVE' | 'CONDITION' | 'MERGE'
  config: Record<string, unknown>
}

/** DAG 边定义（对应 AgentController.DagEdgeSpec）。 */
export interface DagEdgeSpec {
  source: string
  target: string
}

/** DAG 执行请求（对应 AgentController.DagRequest）。 */
export interface DagRequest {
  nodes: DagNodeSpec[]
  edges: DagEdgeSpec[]
}

/** 审计日志（对应 AuditLog 实体）。 */
export interface AuditLog {
  id: number
  eventId?: string
  userId?: number
  action: string
  resource?: string
  riskLevel?: string
  blocked?: boolean
  details?: string
  ipAddress?: string
  userAgent?: string
  createdAt: string
}

/** 列出已注册工具。 */
export function listTools(): Promise<Result<AgentTool[]>> {
  return request.get('/agent/tools')
}

/** 执行某个工具。 */
export function executeTool(name: string, params: Record<string, unknown>): Promise<Result<Record<string, unknown>>> {
  return request.post(`/agent/tools/${name}/execute`, params)
}

/** 执行 DAG 编排。 */
export function executeDag(payload: DagRequest): Promise<Result<Record<string, unknown>>> {
  return request.post('/agent/dag/execute', payload)
}

/** 查询 Harness 运行状态。 */
export function harnessStatus(): Promise<Result<Record<string, unknown>>> {
  return request.get('/agent/harness/status')
}

/** 查询审计日志。 */
export function auditLogs(userId?: number, limit = 50): Promise<Result<AuditLog[]>> {
  return request.get('/agent/audit/logs', { params: { userId, limit } })
}
