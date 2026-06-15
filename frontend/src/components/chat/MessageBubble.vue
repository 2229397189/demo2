<template>
  <div class="message-bubble" :class="[message.role]">
    <div class="message-avatar">
      <el-avatar v-if="message.role === 'user'" :size="36" style="background-color: #409eff">
        <el-icon><User /></el-icon>
      </el-avatar>
      <el-avatar v-else :size="36" style="background-color: #67c23a">
        <el-icon><ChatDotRound /></el-icon>
      </el-avatar>
    </div>
    <div class="message-content">
      <div class="message-header">
        <span class="message-role">{{ message.role === 'user' ? '用户' : 'AI助手' }}</span>
        <span class="message-time">{{ formatTime(message.createdAt) }}</span>
      </div>
      <div class="message-body" v-html="renderedContent" />
      <SourceReference v-if="message.sources?.length" :sources="message.sources" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { User, ChatDotRound } from '@element-plus/icons-vue'
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js'
import 'highlight.js/styles/github-dark.css'
import type { ChatMessage } from '@/types'
import SourceReference from './SourceReference.vue'
import dayjs from 'dayjs'

const props = defineProps<{
  message: ChatMessage
}>()

const md = new MarkdownIt({
  html: true,
  linkify: true,
  typographer: true,
  highlight(str: string, lang: string) {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return `<pre class="hljs"><code>${hljs.highlight(str, { language: lang }).value}</code></pre>`
      } catch {
        // ignore
      }
    }
    return `<pre class="hljs"><code>${md.utils.escapeHtml(str)}</code></pre>`
  },
})

const renderedContent = computed(() => {
  return md.render(props.message.content || '')
})

function formatTime(date: string) {
  return dayjs(date).format('HH:mm:ss')
}
</script>

<style scoped>
.message-bubble {
  display: flex;
  gap: 12px;
  padding: 16px;
  max-width: 100%;
}

.message-bubble.user {
  flex-direction: row-reverse;
}

.message-bubble.user .message-content {
  align-items: flex-end;
}

.message-bubble.user .message-header {
  flex-direction: row-reverse;
}

.message-avatar {
  flex-shrink: 0;
}

.message-content {
  display: flex;
  flex-direction: column;
  max-width: 75%;
}

.message-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.message-role {
  font-size: 12px;
  font-weight: 600;
  color: #606266;
}

.message-time {
  font-size: 11px;
  color: #909399;
}

.message-body {
  background: #fff;
  padding: 12px 16px;
  border-radius: 12px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
  line-height: 1.6;
  word-break: break-word;
}

.message-bubble.user .message-body {
  background: #ecf5ff;
  color: #303133;
}

.message-body :deep(pre) {
  margin: 8px 0;
  padding: 12px;
  border-radius: 6px;
  overflow-x: auto;
}

.message-body :deep(code) {
  font-family: 'Fira Code', 'Consolas', monospace;
  font-size: 13px;
}

.message-body :deep(p) {
  margin: 4px 0;
}

.message-body :deep(ul),
.message-body :deep(ol) {
  padding-left: 20px;
  margin: 8px 0;
}

.message-body :deep(blockquote) {
  border-left: 3px solid #dcdfe6;
  padding-left: 12px;
  margin: 8px 0;
  color: #606266;
}

.message-body :deep(table) {
  border-collapse: collapse;
  margin: 8px 0;
  width: 100%;
}

.message-body :deep(th),
.message-body :deep(td) {
  border: 1px solid #dcdfe6;
  padding: 8px;
  text-align: left;
}

.message-body :deep(th) {
  background: #f5f7fa;
}
</style>
