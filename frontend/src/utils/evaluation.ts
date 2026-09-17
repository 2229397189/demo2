import type { EvaluationComparison, MetricAverages } from '@/types'

/** 指标键 → 展示名。键取自后端 RetrievalEvaluator / GenerationEvaluator 的 JSON 字段。 */
const METRIC_LABELS: Record<string, string> = {
  // 检索指标（RetrievalEvaluator.RetrievalMetrics）
  recallAtK: '召回率 Recall@K',
  precisionAtK: '精确率 Precision@K',
  mrr: 'MRR',
  ndcgAtK: 'NDCG@K',
  hitRate: '命中率 HitRate',
  // 生成指标（GenerationEvaluator.GenerationMetrics）
  faithfulness: '忠实度 Faithfulness',
  answerRelevancy: '答案相关性 AnswerRelevancy',
  contextPrecision: '上下文精确率 ContextPrecision',
  contextRecall: '上下文召回率 ContextRecall',
}

/** 与后端 EvaluationMetricsAggregator.EVALUATED_COUNT_SUFFIX 一致。 */
const EVALUATED_COUNT_SUFFIX = 'EvaluatedCount'

export type MetricGroup = 'retrieval' | 'generation'

export const METRIC_GROUP_LABELS: Record<MetricGroup, string> = {
  retrieval: '检索指标',
  generation: '生成指标',
}

/** 对比表格 / 图表的一行（一个指标在 A、B 两侧的取值与差值）。 */
export interface ComparisonMetricRow {
  /** 原始指标键，如 recallAtK */
  key: string
  /** 展示名 */
  label: string
  group: MetricGroup
  a: number | null
  b: number | null
  countA: number
  countB: number
  delta: number | null
  /** 更优的一侧（仅在两侧都有真实数值且不相等时给出） */
  best: 'A' | 'B' | null
}

function metricKeys(averages: MetricAverages): string[] {
  return Object.keys(averages).filter((key) => !key.endsWith(EVALUATED_COUNT_SUFFIX))
}

function countOf(averages: MetricAverages, key: string): number {
  const value = averages[`${key}${EVALUATED_COUNT_SUFFIX}`]
  return typeof value === 'number' ? value : 0
}

function toNullable(value: unknown): number | null {
  return typeof value === 'number' ? value : null
}

function buildRows(
  group: MetricGroup,
  avgA: MetricAverages,
  avgB: MetricAverages,
  delta: Record<string, number>
): ComparisonMetricRow[] {
  const keys = Array.from(new Set([...metricKeys(avgA), ...metricKeys(avgB)]))
  return keys.map((key) => {
    const a = toNullable(avgA[key])
    const b = toNullable(avgB[key])
    let best: 'A' | 'B' | null = null
    if (a !== null && b !== null && a !== b) {
      best = a > b ? 'A' : 'B'
    }
    return {
      key,
      label: METRIC_LABELS[key] || key,
      group,
      a,
      b,
      countA: countOf(avgA, key),
      countB: countOf(avgB, key),
      delta: toNullable(delta[key]),
      best,
    }
  })
}

/**
 * 把后端对比结构（taskA / taskB / metricsComparison）摊平为可渲染的行。
 * <p>ComparisonTable 与 MetricsChart 共用此函数，保证两处口径一致，
 * 且对「未评估（null）」指标的处理只有一处实现。</p>
 */
export function buildComparisonRows(comparison: EvaluationComparison): ComparisonMetricRow[] {
  const metrics = comparison.metricsComparison
  return [
    ...buildRows('retrieval', metrics.retrievalMetricsA, metrics.retrievalMetricsB, metrics.retrievalDelta),
    ...buildRows('generation', metrics.generationMetricsA, metrics.generationMetricsB, metrics.generationDelta),
  ]
}
