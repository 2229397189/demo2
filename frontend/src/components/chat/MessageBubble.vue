<template>
  <div class="message-bubble" :class="[message.role]">
    <div class="message-avatar">
      <el-avatar v-if="message.role === 'user'" :size="36" style="background-color: var(--color-primary)">
        <el-icon><User /></el-icon>
      </el-avatar>
      <el-avatar v-else :size="36" style="background-color: var(--color-success)">
        <el-icon><ChatDotRound /></el-icon>
      </el-avatar>
    </div>
    <div class="message-content">
      <div class="message-header">
        <span class="message-role">{{ message.role === 'user' ? '用户' : 'AI助手' }}</span>
        <span class="message-time">{{ formatTime(message.createdAt) }}</span>
      </div>
      <!-- 思考过程展示 -->
      <div v-if="message.thinkingSteps?.length" class="thinking-process">
        <div class="thinking-header" @click="toggleThinking">
          <el-icon><Loading /></el-icon>
          <span>思考过程</span>
          <el-icon class="expand-icon" :class="{ expanded: showThinking }"><ArrowDown /></el-icon>
        </div>
        <transition name="el-zoom-in-top">
          <div v-show="showThinking" class="thinking-steps">
            <div
              v-for="(step, idx) in message.thinkingSteps"
              :key="idx"
              class="thinking-step"
              :class="step.step"
            >
              <el-icon v-if="step.step.includes('done')"><CircleCheck /></el-icon>
              <el-icon v-else class="rotating"><Loading /></el-icon>
              <span>{{ step.message }}</span>
            </div>
          </div>
        </transition>
      </div>
      <div class="message-body" :class="{ 'streaming': isStreaming }" v-html="renderedContent" />
      <!-- 消息操作按钮 -->
      <div v-if="message.role === 'assistant' && !isStreaming && message.content" class="message-actions">
        <button class="action-btn" @click="handleCopy" title="复制">
          <el-icon><CopyDocument /></el-icon>
        </button>
        <button v-if="isLast" class="action-btn" @click="$emit('regenerate')" title="重新生成">
          <el-icon><RefreshRight /></el-icon>
        </button>
      </div>
      <SourceReference v-if="message.sources?.length" :sources="message.sources" />
      <SourceReference v-if="message.webResults?.length" :sources="message.webResults" />
      <SandboxResult
        v-for="(result, idx) in (message.sandboxResults || [])"
        :key="idx"
        :result="result"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { User, ChatDotRound, Loading, CircleCheck, ArrowDown, CopyDocument, RefreshRight } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js'
import 'highlight.js/styles/github-dark.css'
import type { ChatMessage } from '@/types'
import SourceReference from './SourceReference.vue'
import SandboxResult from './SandboxResult.vue'
import dayjs from 'dayjs'

const props = defineProps<{
  message: ChatMessage
  isStreaming?: boolean
  isLast?: boolean
}>()

defineEmits<{
  regenerate: []
}>()

async function handleCopy() {
  try {
    await navigator.clipboard.writeText(props.message.content)
    ElMessage.success('已复制到剪贴板')
  } catch {
    ElMessage.error('复制失败')
  }
}

const showThinking = ref(true)

function toggleThinking() {
  showThinking.value = !showThinking.value
}

const md = new MarkdownIt({
  html: false,
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
  color: var(--color-text-secondary);
}

.message-time {
  font-size: 11px;
  color: var(--color-text-tertiary);
}

.message-body {
  background: var(--color-bg-primary);
  padding: 12px 16px;
  border-radius: 12px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
  line-height: 1.6;
  word-break: break-word;
}

.message-bubble.user .message-body {
  background: var(--color-primary-bg, #ecf5ff);
  color: var(--color-text-primary);
}

.message-actions {
  display: flex;
  gap: 4px;
  margin-top: 6px;
  opacity: 0;
  transition: opacity 0.2s;
}

.message-bubble:hover .message-actions {
  opacity: 1;
}

.action-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: 6px;
  background: var(--color-bg-secondary);
  color: var(--color-text-tertiary);
  cursor: pointer;
  transition: all 0.2s;
}

.action-btn:hover {
  background: var(--color-bg-tertiary);
  color: var(--color-text-primary);
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
  border-left: 3px solid var(--color-border);
  padding-left: 12px;
  margin: 8px 0;
  color: var(--color-text-secondary);
}

.message-body :deep(table) {
  border-collapse: collapse;
  margin: 8px 0;
  width: 100%;
}

.message-body :deep(th),
.message-body :deep(td) {
  border: 1px solid var(--color-border);
  padding: 8px;
  text-align: left;
}

.message-body :deep(th) {
  background: var(--color-bg-secondary);
}

/* Thinking process styles */
.thinking-process {
  margin-bottom: 12px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  overflow: hidden;
  background: var(--color-bg-secondary);
}

.thinking-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  cursor: pointer;
  font-size: 13px;
  color: var(--color-text-secondary);
  font-weight: 500;
  transition: background-color 0.2s;
}

.thinking-header:hover {
  background: var(--color-bg-tertiary);
}

.expand-icon {
  margin-left: auto;
  transition: transform 0.3s;
}

.expand-icon.expanded {
  transform: rotate(180deg);
}

.thinking-steps {
  padding: 0 14px 12px;
}

.thinking-step {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
  font-size: 12px;
  color: var(--color-text-tertiary);
  border-bottom: 1px dashed var(--color-border-light);
}

.thinking-step:last-child {
  border-bottom: none;
}

.thinking-step .el-icon {
  font-size: 14px;
}

.thinking-step.retrieval .el-icon,
.thinking-step.websearch .el-icon,
.thinking-step.memory .el-icon,
.thinking-step.generation .el-icon {
  color: var(--color-primary);
}

.thinking-step.retrieval_done .el-icon,
.thinking-step.websearch_done .el-icon,
.thinking-step.memory_done .el-icon {
  color: var(--color-success);
}

.rotating {
  animation: rotate 1s linear infinite;
}

@keyframes rotate {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* Streaming cursor effect */
.message-body.streaming::after {
  content: '▋';
  display: inline-block;
  color: var(--color-primary);
  animation: blink 0.8s infinite;
  margin-left: 2px;
  font-weight: bold;
}

@keyframes blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}
</style>
