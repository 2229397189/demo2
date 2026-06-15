import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { EvaluationTask, EvaluationResult } from '@/types'
import * as evaluationApi from '@/api/evaluation'

function normalizeTask(raw: any): EvaluationTask {
  return {
    id: String(raw.id),
    userId: String(raw.userId ?? ''),
    name: raw.name || '',
    status: raw.status,  // Keep as number, view handles mapping
    datasetId: String(raw.datasetId ?? ''),
    retrievalStrategy: raw.retrievalStrategy,
    modelId: raw.modelId,
    totalQueries: raw.totalQueries ?? 0,
    completedQueries: raw.completedQueries ?? 0,
    createdAt: raw.createdAt,
    updatedAt: raw.updatedAt,
  }
}

function normalizeResult(raw: any): EvaluationResult {
  return {
    id: String(raw.id),
    taskId: String(raw.taskId ?? ''),
    queryId: String(raw.queryId ?? ''),
    query: raw.query || '',
    expectedAnswer: raw.expectedAnswer || '',
    generatedAnswer: raw.generatedAnswer || '',
    retrievedDocIds: raw.retrievedDocIds,
    retrievalMetrics: raw.retrievalMetrics,
    generationMetrics: raw.generationMetrics,
    latencyMs: raw.latencyMs ?? 0,
    createdAt: raw.createdAt,
  }
}

export const useEvaluationStore = defineStore('evaluation', () => {
  const tasks = ref<EvaluationTask[]>([])
  const currentTask = ref<EvaluationTask | null>(null)
  const results = ref<EvaluationResult[]>([])
  const comparison = ref<Record<string, unknown> | null>(null)
  const isLoading = ref(false)

  async function loadTasks() {
    isLoading.value = true
    try {
      const res = await evaluationApi.listTasks()
      tasks.value = (res.data as any[]).map(normalizeTask)
    } catch (error) {
      console.error('Failed to load tasks:', error)
    } finally {
      isLoading.value = false
    }
  }

  async function createTask(task: { name: string; datasetId: string; retrievalStrategy?: string; modelId?: string }) {
    try {
      const res = await evaluationApi.createTask(task)
      const normalized = normalizeTask(res.data)
      tasks.value.unshift(normalized)
      return normalized
    } catch (error) {
      console.error('Failed to create task:', error)
      throw error
    }
  }

  async function selectTask(task: EvaluationTask) {
    currentTask.value = task
    await loadResults(task.id)
  }

  async function loadResults(taskId: string) {
    isLoading.value = true
    try {
      const res = await evaluationApi.getResults(taskId)
      results.value = (res.data as any[]).map(normalizeResult)
    } catch (error) {
      console.error('Failed to load results:', error)
    } finally {
      isLoading.value = false
    }
  }

  async function compareTasks(taskA: string, taskB: string) {
    isLoading.value = true
    try {
      const res = await evaluationApi.compare(taskA, taskB)
      comparison.value = res.data
    } catch (error) {
      console.error('Failed to compare tasks:', error)
    } finally {
      isLoading.value = false
    }
  }

  return {
    tasks,
    currentTask,
    results,
    comparison,
    isLoading,
    loadTasks,
    createTask,
    selectTask,
    loadResults,
    compareTasks,
  }
})
