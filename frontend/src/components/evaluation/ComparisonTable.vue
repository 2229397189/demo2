<template>
  <div class="comparison-table">
    <el-table :data="tableData" border stripe style="width: 100%">
      <el-table-column prop="groupLabel" label="类别" width="90" fixed />
      <el-table-column prop="label" label="指标" min-width="180" fixed />
      <el-table-column :label="comparison.taskA.name" min-width="150">
        <template #default="{ row }">
          <span :class="{ 'best-value': row.best === 'A' }">{{ formatMetric(row.a) }}</span>
          <span v-if="row.a !== null" class="count-text">（{{ row.countA }} 样本）</span>
        </template>
      </el-table-column>
      <el-table-column :label="comparison.taskB.name" min-width="150">
        <template #default="{ row }">
          <span :class="{ 'best-value': row.best === 'B' }">{{ formatMetric(row.b) }}</span>
          <span v-if="row.b !== null" class="count-text">（{{ row.countB }} 样本）</span>
        </template>
      </el-table-column>
      <el-table-column label="差值 (B − A)" min-width="120">
        <template #default="{ row }">
          <span :class="deltaClass(row.delta)">{{ formatDelta(row.delta) }}</span>
        </template>
      </el-table-column>
    </el-table>

    <p v-if="tableData.length === 0" class="empty-hint">
      两个任务暂无可用指标（可能都还没运行，或指标全部未评估）。
    </p>

    <p class="note">
      注：标注「未评估」表示该指标没有有效样本（后端以 null 返回，绝非 0 分）。
    </p>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { EvaluationComparison } from '@/types'
import { buildComparisonRows, METRIC_GROUP_LABELS } from '@/utils/evaluation'

const props = defineProps<{
  comparison: EvaluationComparison
}>()

const tableData = computed(() =>
  buildComparisonRows(props.comparison).map((row) => ({
    ...row,
    groupLabel: METRIC_GROUP_LABELS[row.group],
  }))
)

function formatMetric(value: number | null): string {
  if (value === null || value === undefined) return '未评估'
  return (value * 100).toFixed(2) + '%'
}

function formatDelta(value: number | null): string {
  if (value === null || value === undefined) return '-'
  const sign = value > 0 ? '+' : ''
  return `${sign}${(value * 100).toFixed(2)}%`
}

function deltaClass(value: number | null): string {
  if (value === null || value === undefined || value === 0) return ''
  return value > 0 ? 'delta-up' : 'delta-down'
}
</script>

<style scoped>
.comparison-table {
  width: 100%;
}

.best-value {
  color: var(--color-success);
  font-weight: 600;
}

.count-text {
  margin-left: 4px;
  font-size: 11px;
  color: var(--color-text-tertiary);
}

.delta-up {
  color: var(--color-success);
  font-weight: 600;
}

.delta-down {
  color: var(--color-danger);
  font-weight: 600;
}

.empty-hint {
  margin: 12px 0 0;
  font-size: 13px;
  color: var(--color-text-tertiary);
}

.note {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--color-text-tertiary);
}
</style>
