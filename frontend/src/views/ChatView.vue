<template>
  <div class="chat-view">
    <div class="sidebar">
      <div class="sidebar-header">
        <el-button type="primary" @click="handleCreateSession" style="width: 100%">
          <el-icon><Plus /></el-icon>
          新建对话
        </el-button>
      </div>
      <div class="search-box">
        <el-input
          v-model="searchText"
          placeholder="搜索对话..."
          clearable
          prefix-icon="Search"
        />
      </div>
      <div class="session-list">
        <div
          v-for="session in filteredSessions"
          :key="session.id"
          class="session-item"
          :class="{ active: chatStore.currentSession?.id === session.id }"
          @click="handleSelectSession(session)"
        >
          <div class="session-info">
            <div class="session-title">{{ session.title }}</div>
            <div class="session-meta">
              <span>{{ session.messageCount }} 条消息</span>
              <span>{{ formatTime(session.updatedAt) }}</span>
            </div>
          </div>
          <el-button
            class="delete-btn"
            type="danger"
            text
            size="small"
            @click.stop="handleDeleteSession(session)"
          >
            <el-icon><Delete /></el-icon>
          </el-button>
        </div>
        <el-empty v-if="filteredSessions.length === 0" description="暂无对话" :image-size="60" />
      </div>
    </div>

    <div class="chat-main">
      <div class="chat-header" v-if="chatStore.currentSession">
        <h3>{{ chatStore.currentSession.title }}</h3>
        <div class="chat-controls">
          <el-select v-model="retrievalStrategy" size="small" style="width: 140px">
            <el-option label="纯文本" value="none" />
            <el-option label="混合检索" value="hybrid" />
            <el-option label="稠密检索" value="dense" />
            <el-option label="稀疏检索" value="sparse" />
            <el-option label="图谱检索" value="graph" />
          </el-select>
          <el-switch
            v-model="useMemory"
            active-text="记忆"
            inactive-text=""
            size="small"
          />
        </div>
      </div>

      <ChatWindow
        :messages="chatStore.messages"
        :is-streaming="chatStore.isStreaming"
      />

      <div class="input-area" v-if="chatStore.currentSession">
        <div class="input-wrapper">
          <el-input
            v-model="inputMessage"
            type="textarea"
            :rows="2"
            :autosize="{ minRows: 1, maxRows: 4 }"
            placeholder="输入消息... (Enter发送，Shift+Enter换行)"
            @keydown="handleKeydown"
            :disabled="chatStore.isStreaming"
          />
          <el-button
            type="primary"
            :icon="Promotion"
            :loading="chatStore.isStreaming"
            @click="handleSend"
            :disabled="!inputMessage.trim()"
            class="send-btn"
          />
        </div>
      </div>

      <div v-if="!chatStore.currentSession" class="welcome-screen">
        <div class="welcome-content">
          <h1>AGI Assistant</h1>
          <p>智能问答助手，支持文档检索、记忆管理和代码执行</p>
          <div class="feature-cards">
            <el-card shadow="hover" class="feature-card" @click="router.push('/')">
              <el-icon size="32" color="#409eff"><ChatDotRound /></el-icon>
              <h3>智能对话</h3>
              <p>基于RAG的精准问答</p>
            </el-card>
            <el-card shadow="hover" class="feature-card" @click="router.push('/documents')">
              <el-icon size="32" color="#67c23a"><Document /></el-icon>
              <h3>文档管理</h3>
              <p>上传并管理知识库</p>
            </el-card>
            <el-card shadow="hover" class="feature-card" @click="router.push('/memory')">
              <el-icon size="32" color="#e6a23c"><Coin /></el-icon>
              <h3>记忆系统</h3>
              <p>个性化记忆与画像</p>
            </el-card>
            <el-card shadow="hover" class="feature-card" @click="router.push('/sandbox')">
              <el-icon size="32" color="#f56c6c"><Monitor /></el-icon>
              <h3>代码沙箱</h3>
              <p>安全执行代码片段</p>
            </el-card>
          </div>
        </div>
      </div>
    </div>
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
  Coin,
  Monitor,
} from '@element-plus/icons-vue'
import { useChatStore } from '@/stores/chat'
import type { ChatSession } from '@/types'
import ChatWindow from '@/components/chat/ChatWindow.vue'
import dayjs from 'dayjs'

const chatStore = useChatStore()
const router = useRouter()
const searchText = ref('')
const inputMessage = ref('')
const retrievalStrategy = ref<'none' | 'dense' | 'sparse' | 'graph' | 'hybrid'>('hybrid')
const useMemory = ref(false)

const filteredSessions = computed(() => {
  if (!searchText.value) return chatStore.sortedSessions
  const query = searchText.value.toLowerCase()
  return chatStore.sortedSessions.filter(s =>
    s.title.toLowerCase().includes(query)
  )
})

function formatTime(date: string) {
  return dayjs(date).format('MM-DD HH:mm')
}

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
  height: 100%;
}

.sidebar {
  width: 280px;
  background: #fff;
  border-right: 1px solid #e4e7ed;
  display: flex;
  flex-direction: column;
}

.sidebar-header {
  padding: 16px;
  border-bottom: 1px solid #ebeef5;
}

.search-box {
  padding: 12px 16px;
  border-bottom: 1px solid #ebeef5;
}

.session-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.session-item {
  display: flex;
  align-items: center;
  padding: 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
  margin-bottom: 4px;
}

.session-item:hover {
  background: #f5f7fa;
}

.session-item.active {
  background: #ecf5ff;
  border: 1px solid #b3d8ff;
}

.session-info {
  flex: 1;
  min-width: 0;
}

.session-title {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-meta {
  display: flex;
  gap: 8px;
  font-size: 11px;
  color: #909399;
  margin-top: 4px;
}

.delete-btn {
  opacity: 0;
  transition: opacity 0.2s;
}

.session-item:hover .delete-btn {
  opacity: 1;
}

.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: #f5f7fa;
  overflow: hidden;
}

.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 20px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
}

.chat-header h3 {
  margin: 0;
  font-size: 16px;
  color: #303133;
}

.chat-controls {
  display: flex;
  align-items: center;
  gap: 12px;
}

.input-area {
  padding: 16px 20px;
  background: #fff;
  border-top: 1px solid #e4e7ed;
}

.input-wrapper {
  display: flex;
  gap: 12px;
  align-items: flex-end;
}

.input-wrapper .el-textarea {
  flex: 1;
}

.send-btn {
  height: 40px;
  width: 40px;
  padding: 0;
}

.welcome-screen {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}

.welcome-content {
  text-align: center;
  max-width: 700px;
  padding: 40px;
}

.welcome-content h1 {
  font-size: 36px;
  color: #303133;
  margin-bottom: 12px;
}

.welcome-content > p {
  font-size: 16px;
  color: #606266;
  margin-bottom: 40px;
}

.feature-cards {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 20px;
}

.feature-card {
  cursor: pointer;
  transition: transform 0.2s;
}

.feature-card:hover {
  transform: translateY(-4px);
}

.feature-card h3 {
  margin: 12px 0 8px;
  font-size: 16px;
  color: #303133;
}

.feature-card p {
  font-size: 13px;
  color: #909399;
  margin: 0;
}
</style>
