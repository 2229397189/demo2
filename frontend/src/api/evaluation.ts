import request from '@/utils/request'
import type { Result, EvaluationTask, EvaluationResult, EvaluationComparison } from '@/types'

export function createTask(task: {
  name: string
  datasetId: string
  retrievalStrategy?: string
  modelId?: string
}): Promise<Result<EvaluationTask>> {
  return request.post('/evaluation/tasks', task)
}

export function listTasks(): Promise<Result<EvaluationTask[]>> {
  return request.get('/evaluation/tasks')
}

export function getResults(taskId: string): Promise<Result<EvaluationResult[]>> {
  return request.get(`/evaluation/tasks/${taskId}/results`)
}

// 返回后端 compareResults 的真实结构：{ taskA, taskB, metricsComparison }
export function compare(taskA: string, taskB: string): Promise<Result<EvaluationComparison>> {
  return request.get('/evaluation/compare', { params: { taskA, taskB } })
}

export function listDatasets(): Promise<Result<Array<{ datasetId: string; queryCount: number }>>> {
  return request.get('/evaluation/datasets')
}

/** 触发指定评测任务的异步执行 */
export function runTask(taskId: string): Promise<Result<EvaluationTask>> {
  return request.post(`/evaluation/tasks/${taskId}/run`)
}

/** 从已上传文档构建数据集 */
export function importFromDocuments(
  datasetId: string,
  limit = 4
): Promise<Result<{ datasetId: string; imported: number }>> {
  return request.post('/evaluation/datasets/import', null, { params: { datasetId, limit } })
}
