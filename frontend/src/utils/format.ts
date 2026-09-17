/**
 * 通用格式化工具函数
 * 消除重复代码，统一格式化逻辑
 */

import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'
import 'dayjs/locale/zh-cn'

dayjs.extend(relativeTime)
dayjs.locale('zh-cn')

/**
 * 格式化时间 - 相对时间（如：3分钟前）
 */
export function formatRelativeTime(date: string | Date): string {
  if (!date) return ''
  return dayjs(date).fromNow()
}

/**
 * 格式化时间 - 完整日期时间
 */
export function formatDateTime(date: string | Date): string {
  if (!date) return ''
  return dayjs(date).format('YYYY-MM-DD HH:mm:ss')
}

/**
 * 格式化时间 - 短日期
 */
export function formatDate(date: string | Date): string {
  if (!date) return ''
  return dayjs(date).format('MM-DD HH:mm')
}

/**
 * 格式化文件大小
 */
export function formatFileSize(bytes: number): string {
  if (bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i]
}

/**
 * 格式化数字（添加千分位分隔符）
 */
export function formatNumber(num: number): string {
  return num.toLocaleString('zh-CN')
}

/**
 * 格式化百分比
 */
export function formatPercent(value: number, decimals: number = 1): string {
  return (value * 100).toFixed(decimals) + '%'
}

/**
 * 截断文本
 */
export function truncateText(text: string, maxLength: number): string {
  if (!text || text.length <= maxLength) return text
  return text.slice(0, maxLength) + '...'
}

/**
 * 格式化延迟（毫秒）
 */
export function formatLatency(ms: number): string {
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(2)}s`
}

/**
 * 文档状态映射（键为后端 DocumentStatus 的数字枚举：0/1/2/3/4）
 */
export const documentStatusMap: Record<number, { label: string; type: string }> = {
  0: { label: '待处理', type: 'info' },
  1: { label: '处理中', type: 'warning' },
  2: { label: '已完成', type: 'success' },
  3: { label: '处理失败', type: 'danger' },
  // 分块已落库、但至少一路索引（向量 / BM25 / 图谱）未建立 —— 语义见后端 DocumentStatus.PARTIAL
  4: { label: '部分完成（部分索引未建立）', type: 'warning' },
}

/**
 * 评测状态映射
 */
export const evaluationStatusMap: Record<number, { label: string; type: string }> = {
  0: { label: '待执行', type: 'info' },
  1: { label: '执行中', type: 'warning' },
  2: { label: '已完成', type: 'success' },
  3: { label: '执行失败', type: 'danger' },
}

/**
 * 记忆类型映射 —— 键为后端 / 前后端统一口径的 5 个大写 token：
 * FACT / PREFERENCE / KNOWLEDGE / HABIT / SUMMARY。
 * <p>MemoryView 与 MemoryGraph 共用此表，避免各写一份而逐渐漂移。</p>
 */
export const memoryTypeMap: Record<string, { label: string; type: string }> = {
  FACT: { label: '事实', type: '' },
  PREFERENCE: { label: '偏好', type: 'success' },
  KNOWLEDGE: { label: '知识', type: 'danger' },
  HABIT: { label: '习惯', type: 'warning' },
  SUMMARY: { label: '摘要', type: 'info' },
}

/** 记忆类型的中文标签（未知类型原样返回）。 */
export function getMemoryTypeLabel(type: string): string {
  return memoryTypeMap[type]?.label || type
}

/** 记忆类型对应的 el-tag 类型（未知类型返回空串）。 */
export function getMemoryTypeTag(type: string): string {
  return memoryTypeMap[type]?.type || ''
}

/**
 * 获取状态标签类型
 */
export function getStatusType(statusMap: Record<number, { label: string; type: string }>, status: number): string {
  return statusMap[status]?.type || 'info'
}

/**
 * 获取状态标签文本
 */
export function getStatusLabel(statusMap: Record<number, { label: string; type: string }>, status: number): string {
  return statusMap[status]?.label || '未知'
}

/**
 * 生成随机 ID
 */
export function generateId(): string {
  return Math.random().toString(36).substr(2, 9)
}

/**
 * 防抖函数
 */
export function debounce<T extends (...args: any[]) => any>(
  func: T,
  wait: number
): (...args: Parameters<T>) => void {
  let timeout: ReturnType<typeof setTimeout>
  return (...args: Parameters<T>) => {
    clearTimeout(timeout)
    timeout = setTimeout(() => func(...args), wait)
  }
}

/**
 * 节流函数
 */
export function throttle<T extends (...args: any[]) => any>(
  func: T,
  limit: number
): (...args: Parameters<T>) => void {
  let inThrottle: boolean
  return (...args: Parameters<T>) => {
    if (!inThrottle) {
      func(...args)
      inThrottle = true
      setTimeout(() => (inThrottle = false), limit)
    }
  }
}
