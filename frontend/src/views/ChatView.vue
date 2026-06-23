<template>
  <div class="chat-view">
    <!-- 会话侧边栏 - 学习 ChatGPT 的设计 -->
    <aside class="chat-sidebar">
      <div class="sidebar-header">
        <button class="new-chat-btn" @click="handleCreateSession">
          <el-icon><Plus /></el-icon>
          <span>新建对话</span>
        </button>
      </div>

      <div class="search-wrapper search-focus">
        <el-input
          v-model="searchText"
          placeholder="搜索对话..."
          clearable
          :prefix-icon="Search"
          size="default"
        />
      </div>

      <div class="session-list">
        <TransitionGroup name="list">
          <div
            v-for="session in filteredSessions"
            :key="session.id"
            class="session-item"
            :class="{ active: chatStore.currentSession?.id === session.id }"
            @click="handleSelectSession(session)"
          >
            <div class="session-icon">
              <el-icon :size="16"><ChatDotRound /></el-icon>
            </div>
            <div class="session-info">
              <div class="session-title">{{ session.title }}</div>
              <div class="session-meta">
                {{ formatRelativeTime(session.updatedAt) }}
              </div>
            </div>
            <button
              class="delete-btn"
              @click.stop="handleDeleteSession(session)"
              title="删除对话"
            >
              <el-icon :size="14"><Delete /></el-icon>
            </button>
          </div>
        </TransitionGroup>

        <div v-if="filteredSessions.length === 0" class="empty-state">
          <el-icon :size="48" color="var(--color-text-tertiary)"><ChatDotRound /></el-icon>
          <p>暂无对话</p>
        </div>
      </div>
    </aside>

    <!-- 主聊天区 -->
    <main class="chat-main">
      <!-- 聊天头部 -->
      <header class="chat-header" v-if="chatStore.currentSession">
        <div class="header-left">
          <h2>{{ chatStore.currentSession.title }}</h2>
        </div>
        <div class="header-right">
          <el-select v-model="retrievalStrategy" size="small" class="strategy-select">
            <el-option label="混合检索" value="hybrid" />
            <el-option label="稠密检索" value="dense" />
            <el-option label="稀疏检索" value="sparse" />
            <el-option label="图谱检索" value="graph" />
            <el-option label="纯文本" value="none" />
          </el-select>
          <el-tooltip content="启用记忆系统" placement="bottom">
            <el-switch
              v-model="useMemory"
              size="small"
              active-text="记忆"
            />
          </el-tooltip>
        </div>
      </header>

      <!-- 消息区域 -->
      <ChatWindow
        :messages="chatStore.messages"
        :is-streaming="chatStore.isStreaming"
      />

      <!-- 输入区域 -->
      <div class="input-area" v-if="chatStore.currentSession">
        <div class="input-container">
          <div class="input-wrapper search-focus">
            <el-input
              v-model="inputMessage"
              type="textarea"
              :rows="1"
              :autosize="{ minRows: 1, maxRows: 5 }"
              placeholder="输入消息... (Enter发送，Shift+Enter换行)"
              @keydown="handleKeydown"
              :disabled="chatStore.isStreaming"
              resize="none"
            />
            <button
              class="send-btn"
              :class="{ active: inputMessage.trim() && !chatStore.isStreaming }"
              @click="handleSend"
              :disabled="!inputMessage.trim() || chatStore.isStreaming"
            >
              <el-icon v-if="!chatStore.isStreaming"><Promotion /></el-icon>
              <el-icon v-else class="spin"><Loading /></el-icon>
            </button>
          </div>
          <div class="input-hint">
            <span>AGI Assistant 可能会犯错，请核实重要信息。</span>
          </div>
        </div>
      </div>

      <!-- 欢迎屏幕 - 学习 ChatGPT/Claude 的简洁风格 -->
      <div v-if="!chatStore.currentSession" class="welcome-screen">
        <div class="welcome-content">
          <div class="welcome-logo">
            <div class="logo-glow"></div>
            <el-icon :size="48"><ChatDotRound /></el-icon>
          </div>
          <h1>AGI Assistant</h1>
          <p class="welcome-desc">智能问答助手，支持文档检索、记忆管理和代码执行</p>

          <div class="feature-grid">
            <div
              v-for="feature in features"
              :key="feature.title"
              class="feature-card card-hover"
              @click="feature.action"
            >
              <div class="feature-icon" :style="{ background: feature.gradient }">
                <el-icon :size="24"><component :is="feature.icon" /></el-icon>
              </div>
              <h3>{{ feature.title }}</h3>
              <p>{{ feature.description }}</p>
            </div>
          </div>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox, ElMessage } from 'element-plus'
import {
  Plus,
  Delete,
  Promotion,
  ChatDotRound,
  Document,
  Memo,
  Cpu,
  Search,
  Loading,
} from '@element-plus/icons-vue'
import { useChatStore } from '@/stores/chat'
import type { ChatSession } from '@/types'
import ChatWindow from '@/components/chat/ChatWindow.vue'
import { formatRelativeTime } from '@/utils/format'

const chatStore = useChatStore()
const router = useRouter()
const searchText = ref('')
const inputMessage = ref('')
const retrievalStrategy = ref<'none' | 'dense' | 'sparse' | 'graph' | 'hybrid'>('hybrid')
const useMemory = ref(true)

// 功能卡片配置
const features = [
  {
    title: '智能对话',
    description: '基于 RAG 的精准问答',
    icon: ChatDotRound,
    gradient: 'linear-gradient(135deg, #6366f1, #818cf8)',
    action: () => handleCreateSession(),
  },
  {
    title: '文档管理',
    description: '上传并管理知识库',
    icon: Document,
    gradient: 'linear-gradient(135deg, #10b981, #34d399)',
    action: () => router.push('/documents'),
  },
  {
    title: '记忆系统',
    description: '个性化记忆与画像',
    icon: Memo,
    gradient: 'linear-gradient(135deg, #f59e0b, #fbbf24)',
    action: () => router.push('/memory'),
  },
  {
    title: '代码沙箱',
    description: '安全执行代码片段',
    icon: Cpu,
    gradient: 'linear-gradient(135deg, #ef4444, #f87171)',
    action: () => router.push('/sandbox'),
  },
]

const filteredSessions = computed(() => {
  if (!searchText.value) return chatStore.sortedSessions
  const query = searchText.value.toLowerCase()
  return chatStore.sortedSessions.filter(s =>
    s.title.toLowerCase().includes(query)
  )
})

async function handleCreateSession() {
  try {
    const { value } = await ElMessageBox.prompt('请输入对话标题', '新建对话', {
      confirmButtonText: '创建',
      cancelButtonText: '取消',
      inputValue: '新对话',
      inputValidator: (val) => val.trim().length > 0 ? true : '标题不能为空',
    })
    await chatStore.createSession(value)
    ElMessage.success('对话创建成功')
  } catch {
    // cancelled
  }
}

async function handleSelectSession(session: ChatSession) {
  await chatStore.selectSession(session)
}

async function handleDeleteSession(session: ChatSession) {
  try {
    await ElMessageBox.confirm(
      `确定删除对话 "${session.title}" 吗？`,
      '删除确认',
      { type: 'warning' }
    )
    await chatStore.deleteSession(session.id)
    ElMessage.success('对话已删除')
  } catch {
    // cancelled
  }
}

function handleSend() {
  const content = inputMessage.value.trim()
  if (!content) return
  chatStore.sendMessage(content, retrievalStrategy.value, useMemory.value)
  inputMessage.value = ''
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    handleSend()
  }
}

onMounted(() => {
  chatStore.loadSessions()
})
</script>

<style scoped>
.chat-view {
  display: flex;
  height: 100vh;
  overflow: hidden;
}

/* ── 会话侧边栏 ── */
.chat-sidebar {
  width: 280px;
  background-color: var(--color-bg-primary);
  border-right: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.sidebar-header {
  padding: var(--space-4);
}

.new-chat-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  width: 100%;
  padding: var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background-color: var(--color-bg-primary);
  color: var(--color-text-primary);
  font-size: var(--text-sm);
  font-weight: 500;
  cursor: pointer;
  transition: all var(--transition-fast);
}

.new-chat-btn:hover {
  background-color: var(--color-bg-tertiary);
  border-color: var(--color-primary);
  color: var(--color-primary);
}

.search-wrapper {
  padding: 0 var(--space-4) var(--space-3);
}

.session-list {
  flex: 1;
  overflow-y: auto;
  padding: 0 var(--space-2);
}

.session-item {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-3);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--transition-fast);
  margin-bottom: var(--space-1);
  position: relative;
}

.session-item:hover {
  background-color: var(--color-bg-tertiary);
}

.session-item.active {
  background-color: var(--color-primary-bg);
}

.session-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: var(--radius-md);
  background-color: var(--color-bg-tertiary);
  color: var(--color-text-secondary);
  flex-shrink: 0;
}

.session-item.active .session-icon {
  background-color: var(--color-primary);
  color: white;
}

.session-info {
  flex: 1;
  min-width: 0;
}

.session-title {
  font-size: var(--text-sm);
  font-weight: 500;
  color: var(--color-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-meta {
  font-size: var(--text-xs);
  color: var(--color-text-tertiary);
  margin-top: var(--space-1);
}

.delete-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: var(--radius-md);
  background-color: transparent;
  color: var(--color-text-tertiary);
  cursor: pointer;
  opacity: 0;
  transition: all var(--transition-fast);
}

.session-item:hover .delete-btn {
  opacity: 1;
}

.delete-btn:hover {
  background-color: rgba(239, 68, 68, 0.1);
  color: var(--color-danger);
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: var(--space-8);
  color: var(--color-text-tertiary);
}

.empty-state p {
  margin-top: var(--space-3);
  font-size: var(--text-sm);
}

/* ── 主聊天区 ── */
.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  background-color: var(--color-bg-secondary);
  overflow: hidden;
}

.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-3) var(--space-6);
  background-color: var(--color-bg-primary);
  border-bottom: 1px solid var(--color-border);
  backdrop-filter: blur(8px);
}

.header-left h2 {
  margin: 0;
  font-size: var(--text-base);
  font-weight: 600;
  color: var(--color-text-primary);
}

.header-right {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.strategy-select {
  width: 130px;
}

/* ── 输入区域 ── */
.input-area {
  padding: var(--space-4) var(--space-6);
  background-color: var(--color-bg-secondary);
}

.input-container {
  max-width: var(--chat-max-width);
  margin: 0 auto;
}

.input-wrapper {
  display: flex;
  align-items: flex-end;
  gap: var(--space-3);
  padding: var(--space-3);
  background-color: var(--color-bg-primary);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-xl);
  transition: all var(--transition-normal);
}

.input-wrapper:focus-within {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 3px var(--color-primary-bg);
}

.input-wrapper :deep(.el-textarea__inner) {
  border: none;
  box-shadow: none;
  padding: 0 var(--space-2);
  background: transparent;
}

.send-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border: none;
  border-radius: var(--radius-lg);
  background-color: var(--color-bg-tertiary);
  color: var(--color-text-tertiary);
  cursor: not-allowed;
  transition: all var(--transition-fast);
  flex-shrink: 0;
}

.send-btn.active {
  background-color: var(--color-primary);
  color: white;
  cursor: pointer;
}

.send-btn.active:hover {
  background-color: var(--color-primary-dark);
}

.input-hint {
  text-align: center;
  margin-top: var(--space-2);
}

.input-hint span {
  font-size: var(--text-xs);
  color: var(--color-text-tertiary);
}

/* ── 欢迎屏幕 ── */
.welcome-screen {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-8);
}

.welcome-content {
  text-align: center;
  max-width: 800px;
}

.welcome-logo {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 80px;
  height: 80px;
  border-radius: var(--radius-2xl);
  background: linear-gradient(135deg, var(--color-primary), var(--color-primary-light));
  color: white;
  margin-bottom: var(--space-6);
}

.logo-glow {
  position: absolute;
  inset: -10px;
  border-radius: var(--radius-2xl);
  background: linear-gradient(135deg, var(--color-primary), var(--color-primary-light));
  opacity: 0.3;
  filter: blur(20px);
}

.welcome-content h1 {
  margin: 0 0 var(--space-3);
  font-size: var(--text-3xl);
  font-weight: 700;
  color: var(--color-text-primary);
  background: linear-gradient(135deg, var(--color-text-primary), var(--color-primary));
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.welcome-desc {
  font-size: var(--text-lg);
  color: var(--color-text-secondary);
  margin-bottom: var(--space-10);
}

.feature-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--space-4);
}

.feature-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: var(--space-6);
  background-color: var(--color-bg-primary);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-xl);
  cursor: pointer;
  transition: all var(--transition-normal);
}

.feature-card:hover {
  border-color: var(--color-primary);
  box-shadow: var(--shadow-lg);
}

.feature-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  border-radius: var(--radius-lg);
  color: white;
  margin-bottom: var(--space-4);
}

.feature-card h3 {
  margin: 0 0 var(--space-2);
  font-size: var(--text-base);
  font-weight: 600;
  color: var(--color-text-primary);
}

.feature-card p {
  margin: 0;
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
}

/* ── 响应式设计 ── */
@media (max-width: 1024px) {
  .feature-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 768px) {
  .chat-sidebar {
    position: fixed;
    left: 0;
    top: 0;
    bottom: 0;
    z-index: 100;
    transform: translateX(-100%);
    transition: transform var(--transition-slow);
  }

  .chat-sidebar.open {
    transform: translateX(0);
  }

  .feature-grid {
    grid-template-columns: 1fr;
  }
}
</style>
