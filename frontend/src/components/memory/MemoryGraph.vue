<template>
  <div class="memory-graph">
    <div v-if="memories.length === 0" class="empty-graph">
      <el-empty description="暂无记忆数据" :image-size="80" />
    </div>
    <div v-else class="memory-tree">
      <div
        v-for="memory in memories"
        :key="memory.id"
        class="memory-node"
        @click="$emit('select', memory)"
      >
        <div class="node-header">
          <el-tag :type="getTypeTag(memory.type)" size="small">{{ getTypeLabel(memory.type) }}</el-tag>
          <span class="importance">
            <el-icon><Star /></el-icon>
            {{ memory.importance.toFixed(1) }}
          </span>
        </div>
        <div class="node-content">{{ memory.content }}</div>
        <div class="node-meta">
          <span>{{ formatTime(memory.createdAt) }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { Star } from '@element-plus/icons-vue'
import type { Memory } from '@/types'
import dayjs from 'dayjs'

defineProps<{
  memories: Memory[]
}>()

defineEmits<{
  select: [memory: Memory]
}>()

function getTypeTag(type: string) {
  const map: Record<string, string> = {
    fact: '',
    preference: 'success',
    interaction: 'warning',
    summary: 'info',
  }
  return map[type] || ''
}

function getTypeLabel(type: string) {
  const map: Record<string, string> = {
    fact: '事实',
    preference: '偏好',
    interaction: '交互',
    summary: '摘要',
  }
  return map[type] || type
}

function formatTime(date: string) {
  return dayjs(date).format('MM-DD HH:mm')
}
</script>

<style scoped>
.memory-graph {
  height: 100%;
  overflow-y: auto;
}

.empty-graph {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.memory-tree {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 8px;
}

.memory-node {
  background: var(--color-bg-primary);
  border: 1px solid var(--color-border);
  border-radius: 8px;
  padding: 12px;
  cursor: pointer;
  transition: all 0.2s;
}

.memory-node:hover {
  border-color: var(--color-primary);
  box-shadow: 0 2px 8px rgba(64, 158, 255, 0.15);
}

.node-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.importance {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--color-warning);
}

.node-content {
  font-size: 14px;
  color: var(--color-text-primary);
  line-height: 1.5;
  margin-bottom: 8px;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.node-meta {
  font-size: 11px;
  color: var(--color-text-tertiary);
}
</style>
