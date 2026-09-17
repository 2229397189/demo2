<template>
  <div class="metrics-chart" ref="chartRef" />
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch } from 'vue'
import * as echarts from 'echarts'
import type { EChartsType } from 'echarts'
import type { ComparisonMetricRow } from '@/utils/evaluation'

const props = defineProps<{
  /** 两个任务的名字，顺序为 [A, B] */
  labels: string[]
  /** 由 buildComparisonRows 生成的行 */
  rows: ComparisonMetricRow[]
}>()

const chartRef = ref<HTMLElement>()
let chart: EChartsType | null = null

function initChart() {
  if (!chartRef.value) return
  chart = echarts.init(chartRef.value)
  updateChart()
}

function updateChart() {
  if (!chart) return

  const categories = props.rows.map((row) => row.label)
  const seriesA = props.rows.map((row) => row.a)
  const seriesB = props.rows.map((row) => row.b)

  chart.setOption({
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
    },
    legend: {
      data: props.labels,
      bottom: 0,
    },
    grid: {
      left: '3%',
      right: '4%',
      bottom: '18%',
      top: '10%',
      containLabel: true,
    },
    xAxis: {
      type: 'category',
      data: categories,
      axisLabel: {
        rotate: categories.length > 4 ? 25 : 0,
        fontSize: 11,
        interval: 0,
      },
    },
    yAxis: {
      type: 'value',
      axisLabel: {
        formatter: (value: number) => (value * 100).toFixed(0) + '%',
      },
    },
    series: [
      {
        name: props.labels[0] ?? 'A',
        type: 'bar',
        data: seriesA,
        itemStyle: { borderRadius: [4, 4, 0, 0] },
      },
      {
        name: props.labels[1] ?? 'B',
        type: 'bar',
        data: seriesB,
        itemStyle: { borderRadius: [4, 4, 0, 0] },
      },
    ],
  })
}

watch(() => props.rows, updateChart, { deep: true })
watch(() => props.labels, updateChart, { deep: true })

onMounted(() => {
  initChart()
  window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  chart?.dispose()
})

function handleResize() {
  chart?.resize()
}
</script>

<style scoped>
.metrics-chart {
  width: 100%;
  height: 100%;
  min-height: 300px;
}
</style>
