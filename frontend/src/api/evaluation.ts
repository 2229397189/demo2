import request from '@/utils/request'
import type { Result, EvaluationTask, EvaluationResult } from '@/types'

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

export function compare(taskA: string, taskB: string): Promise<Result<Record<string, unknown>>> {
  return request.get('/evaluation/compare', { params: { taskA, taskB } })
}

export function listDatasets(): Promise<Result<Array<{ datasetId: string; queryCount: number }>>> {
  return request.get('/evaluation/datasets')
}
