<template>
  <div class="source-reference">
    <div class="source-header" @click="expanded = !expanded">
      <el-icon><Link /></el-icon>
      <span>{{ sources.length }} 个参考来源</span>
      <el-icon class="expand-icon" :class="{ expanded }"><ArrowDown /></el-icon>
    </div>
    <el-collapse-transition>
      <div v-show="expanded" class="source-list">
        <div v-for="(source, idx) in sources" :key="source.id || idx" class="source-item">
          <div class="source-info">
            <el-tag size="small" type="info">{{ source.documentName || source.title || '未知文档' }}</el-tag>
            <el-tag size="small" type="success">相似度: {{ (source.score * 100).toFixed(1) }}%</el-tag>
            <span class="chunk-index">段落 #{{ source.chunkIndex + 1 }}</span>
          </div>
          <div class="source-content">{{ source.content }}</div>
        </div>
      </div>
    </el-collapse-transition>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { Link, ArrowDown } from '@element-plus/icons-vue'
import type { SourceReference as SourceRef } from '@/types'

defineProps<{
  sources: SourceRef[]
}>()

const expanded = ref(false)
</script>

<style scoped>
.source-reference {
  margin-top: 8px;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  overflow: hidden;
}

.source-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  background: #f5f7fa;
  cursor: pointer;
  font-size: 13px;
  color: #606266;
  user-select: none;
}

.source-header:hover {
  background: #ecf5ff;
}

.expand-icon {
  margin-left: auto;
  transition: transform 0.3s;
}

.expand-icon.expanded {
  transform: rotate(180deg);
}

.source-list {
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.source-item {
  padding: 10px;
  background: #fafafa;
  border-radius: 6px;
  border: 1px solid #ebeef5;
}

.source-info {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}

.chunk-index {
  font-size: 12px;
  color: #909399;
}

.source-content {
  font-size: 13px;
  color: #606266;
  line-height: 1.5;
  max-height: 100px;
  overflow-y: auto;
}
</style>
