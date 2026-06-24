<template>
  <div class="comparison-table">
    <el-table :data="tableData" border stripe style="width: 100%">
      <el-table-column prop="metric" label="指标" width="150" fixed />
      <el-table-column
        v-for="(taskName, index) in comparison.taskNames"
        :key="index"
        :label="taskName"
        min-width="120"
      >
        <template #default="{ row }">
          <span :class="{ 'best-value': isBest(row.metric, index) }">
            {{ formatValue(row.values[index]) }}
          </span>
          <el-icon v-if="isBest(row.metric, index)" class="best-icon"><Trophy /></el-icon>
        </template>
      </el-table-column>
      <el-table-column label="统计" min-width="200">
        <template #default="{ row }">
          <span class="stat-text">
            平均: {{ formatValue(row.avg) }} |
            最小: {{ formatValue(row.min) }} |
            最大: {{ formatValue(row.max) }}
          </span>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Trophy } from '@element-plus/icons-vue'
import type { EvaluationComparison } from '@/types'

const props = defineProps<{
  comparison: EvaluationComparison
}>()

const tableData = computed(() => {
  return Object.keys(props.comparison.metrics).map(metric => {
    const values = props.comparison.metrics[metric]
    const summary = props.comparison.summary[metric]
    return {
      metric,
      values,
      avg: summary?.avg || 0,
      min: summary?.min || 0,
      max: summary?.max || 0,
    }
  })
})

function isBest(metric: string, index: number): boolean {
  const values = props.comparison.metrics[metric]
  if (!values || values.length === 0) return false
  const maxVal = Math.max(...values)
  return values[index] === maxVal && values.filter(v => v === maxVal).length === 1
}

function formatValue(value: number): string {
  if (value === undefined || value === null) return '-'
  return (value * 100).toFixed(2) + '%'
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

.best-icon {
  color: var(--color-warning);
  margin-left: 4px;
  vertical-align: middle;
}

.stat-text {
  font-size: 12px;
  color: var(--color-text-secondary);
}
</style>
