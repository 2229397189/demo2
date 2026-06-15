<template>
  <div class="metrics-chart" ref="chartRef" />
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch } from 'vue'
import * as echarts from 'echarts'
import type { EChartsType } from 'echarts'

const props = defineProps<{
  data: Record<string, number[]>
  labels: string[]
  metrics: string[]
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

  const series = props.metrics.map((metric) => ({
    name: metric,
    type: 'bar' as const,
    data: props.data[metric] || [],
    itemStyle: {
      borderRadius: [4, 4, 0, 0],
    },
  }))

  chart.setOption({
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
    },
    legend: {
      data: props.metrics,
      bottom: 0,
    },
    grid: {
      left: '3%',
      right: '4%',
      bottom: '15%',
      top: '10%',
      containLabel: true,
    },
    xAxis: {
      type: 'category',
      data: props.labels,
      axisLabel: {
        rotate: props.labels.length > 3 ? 30 : 0,
        fontSize: 11,
      },
    },
    yAxis: {
      type: 'value',
      axisLabel: {
        formatter: (value: number) => (value * 100).toFixed(0) + '%',
      },
    },
    series,
  })
}

watch(() => props.data, updateChart, { deep: true })

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
