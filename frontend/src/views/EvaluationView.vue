<template>
  <div class="evaluation-view">
    <div class="page-header">
      <div>
        <h2>评测管理</h2>
        <p>创建和管理评测任务，查看评测结果</p>
      </div>
      <el-button type="primary" @click="showCreateDialog = true">
        <el-icon><Plus /></el-icon>
        创建评测任务
      </el-button>
    </div>

    <el-card class="task-card" shadow="hover">
      <template #header>
        <div class="card-header">
          <span>评测任务</span>
          <el-button @click="loadTasks" :loading="evaluationStore.isLoading">
            <el-icon><Refresh /></el-icon>
          </el-button>
        </div>
      </template>

      <el-table :data="evaluationStore.tasks" v-loading="evaluationStore.isLoading" stripe>
        <el-table-column prop="name" label="任务名称" min-width="150" />
        <el-table-column prop="datasetName" label="数据集" width="120" />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)" size="small">
              {{ getStatusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="指标" min-width="200">
          <template #default="{ row }">
            <el-tag v-for="metric in row.metrics" :key="metric" size="small" class="metric-tag">
              {{ metric }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="160">
          <template #default="{ row }">
            {{ formatTime(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="250" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" size="small" text @click="handleViewResults(row)">
              <el-icon><View /></el-icon>
              结果
            </el-button>
            <el-button
              type="success"
              size="small"
              text
              @click="handleCompare(row)"
              :disabled="selectedForCompare.includes(row.id)"
            >
              <el-icon><DataAnalysis /></el-icon>
              对比
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <div v-if="selectedForCompare.length > 0" class="compare-bar">
      <span>已选择 {{ selectedForCompare.length }} 个任务进行对比</span>
      <el-button type="primary" size="small" @click="handleRunCompare" :loading="comparing">
        开始对比
      </el-button>
      <el-button size="small" @click="selectedForCompare = []">取消</el-button>
    </div>

    <el-card v-if="evaluationStore.currentTask" class="results-card" shadow="hover">
      <template #header>
        <div class="card-header">
          <span>评测结果 - {{ evaluationStore.currentTask.name }}</span>
          <el-button text @click="evaluationStore.currentTask = null">
            <el-icon><Close /></el-icon>
          </el-button>
        </div>
      </template>

      <el-table :data="evaluationStore.results" stripe>
        <el-table-column prop="query" label="问题" min-width="200" show-overflow-tooltip />
        <el-table-column prop="expectedAnswer" label="期望答案" min-width="150" show-overflow-tooltip />
        <el-table-column prop="generatedAnswer" label="生成答案" min-width="150" show-overflow-tooltip />
        <el-table-column label="延迟" width="100">
          <template #default="{ row }">
            {{ row.latencyMs }}ms
          </template>
        </el-table-column>
        <el-table-column label="检索指标" min-width="200">
          <template #default="{ row }">
            <span v-if="row.retrievalMetrics" class="metrics-text">
              {{ formatMetrics(row.retrievalMetrics) }}
            </span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card v-if="evaluationStore.comparison" class="comparison-card" shadow="hover">
      <template #header>
        <div class="card-header">
          <span>对比分析</span>
          <el-button text @click="evaluationStore.comparison = null">
            <el-icon><Close /></el-icon>
          </el-button>
        </div>
      </template>

      <div class="comparison-content">
        <pre class="comparison-json">{{ JSON.stringify(evaluationStore.comparison, null, 2) }}</pre>
      </div>
    </el-card>

    <el-dialog v-model="showCreateDialog" title="创建评测任务" width="500">
      <el-form :model="newTask" label-width="80px">
        <el-form-item label="任务名称">
          <el-input v-model="newTask.name" placeholder="输入任务名称" />
        </el-form-item>
        <el-form-item label="数据集">
          <el-select v-model="newTask.datasetId" placeholder="选择评测数据集" style="width: 100%">
            <el-option
              v-for="ds in datasets"
              :key="ds.datasetId"
              :label="`${ds.datasetId} (${ds.queryCount} 道题)`"
              :value="ds.datasetId"
            />
          </el-select>
          <div v-if="datasets.length === 0" style="color: #909399; font-size: 12px; margin-top: 4px;">
            暂无可用数据集，请联系管理员添加
          </div>
        </el-form-item>
        <el-form-item label="检索策略">
          <el-select v-model="newTask.retrievalStrategy" placeholder="选择策略">
            <el-option label="混合检索" value="HYBRID" />
            <el-option label="稠密检索" value="DENSE" />
            <el-option label="稀疏检索" value="SPARSE" />
            <el-option label="图检索" value="GRAPH" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreateDialog = false">取消</el-button>
        <el-button type="primary" @click="handleCreateTask" :loading="creating">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  Plus,
  Refresh,
  View,
  DataAnalysis,
  Close,
} from '@element-plus/icons-vue'
import { useEvaluationStore } from '@/stores/evaluation'
import * as evaluationApi from '@/api/evaluation'
import type { EvaluationTask } from '@/types'
import dayjs from 'dayjs'

const evaluationStore = useEvaluationStore()
const showCreateDialog = ref(false)
const creating = ref(false)
const comparing = ref(false)
const selectedForCompare = ref<string[]>([])
const datasets = ref<Array<{ datasetId: string; queryCount: number }>>([])

const newTask = ref({
  name: '',
  datasetId: '',
  retrievalStrategy: 'HYBRID',
})

async function loadTasks() {
  await evaluationStore.loadTasks()
}

async function loadDatasets() {
  try {
    const res = await evaluationApi.listDatasets()
    datasets.value = res.data || []
  } catch (error) {
    console.error('Failed to load datasets:', error)
  }
}

async function handleCreateTask() {
  if (!newTask.value.name || !newTask.value.datasetId) {
    ElMessage.warning('请填写完整信息')
    return
  }
  creating.value = true
  try {
    await evaluationStore.createTask({
      name: newTask.value.name,
      datasetId: newTask.value.datasetId,
      retrievalStrategy: newTask.value.retrievalStrategy,
    })
    ElMessage.success('评测任务创建成功')
    showCreateDialog.value = false
    newTask.value = { name: '', datasetId: '', retrievalStrategy: 'HYBRID' }
  } catch (error) {
    console.error('Failed to create task:', error)
  } finally {
    creating.value = false
  }
}

async function handleViewResults(task: EvaluationTask) {
  await evaluationStore.selectTask(task)
}

function handleCompare(task: EvaluationTask) {
  if (selectedForCompare.value.length >= 2) {
    ElMessage.warning('最多选择2个任务进行对比')
    return
  }
  selectedForCompare.value.push(task.id)
}

async function handleRunCompare() {
  if (selectedForCompare.value.length < 2) {
    ElMessage.warning('请至少选择2个任务进行对比')
    return
  }
  comparing.value = true
  try {
    await evaluationStore.compareTasks(selectedForCompare.value[0], selectedForCompare.value[1])
    selectedForCompare.value = []
  } catch (error) {
    console.error('Failed to compare:', error)
  } finally {
    comparing.value = false
  }
}

function formatTime(date: string) {
  return dayjs(date).format('YYYY-MM-DD HH:mm')
}

function formatMetrics(metricsJson: string): string {
  try {
    const metrics = JSON.parse(metricsJson)
    return Object.entries(metrics)
      .filter(([, v]) => typeof v === 'number')
      .map(([k, v]) => `${k}: ${((v as number) * 100).toFixed(1)}%`)
      .join(', ')
  } catch {
    return metricsJson
  }
}

function getStatusType(status: string | number) {
  const map: Record<string, string> = {
    0: 'info',
    1: 'warning',
    2: 'success',
    3: 'danger',
    pending: 'info',
    PENDING: 'info',
    running: 'warning',
    RUNNING: 'warning',
    completed: 'success',
    COMPLETED: 'success',
    failed: 'danger',
    FAILED: 'danger',
  }
  return map[String(status)] || 'info'
}

function getStatusLabel(status: string | number) {
  const map: Record<string, string> = {
    0: '待执行',
    1: '运行中',
    2: '已完成',
    3: '失败',
    pending: '待执行',
    PENDING: '待执行',
    running: '运行中',
    RUNNING: '运行中',
    completed: '已完成',
    COMPLETED: '已完成',
    failed: '失败',
    FAILED: '失败',
  }
  return map[String(status)] || String(status)
}

onMounted(() => {
  loadTasks()
  loadDatasets()
})
</script>

<style scoped>
.evaluation-view {
  padding: 24px;
  height: 100%;
  overflow-y: auto;
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 24px;
}

.page-header h2 {
  margin: 0 0 8px;
  font-size: 24px;
  color: #303133;
}

.page-header p {
  margin: 0;
  color: #909399;
  font-size: 14px;
}

.task-card,
.results-card,
.comparison-card {
  margin-bottom: 24px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
}

.metric-tag {
  margin: 2px;
}

.compare-bar {
  position: fixed;
  bottom: 24px;
  left: 50%;
  transform: translateX(-50%);
  background: #303133;
  color: #fff;
  padding: 12px 24px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  gap: 16px;
  z-index: 100;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.2);
}

.comparison-content {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.comparison-json {
  background: #f5f7fa;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 16px;
  font-size: 13px;
  line-height: 1.5;
  overflow-x: auto;
  max-height: 500px;
  overflow-y: auto;
}
</style>
