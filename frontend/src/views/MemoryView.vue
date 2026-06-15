<template>
  <div class="memory-view">
    <div class="page-header">
      <h2>记忆系统</h2>
      <p>管理用户记忆和画像信息</p>
    </div>

    <div class="memory-layout">
      <div class="memory-main">
        <el-card class="search-card" shadow="hover">
          <el-input
            v-model="memoryStore.searchQuery"
            placeholder="搜索记忆内容..."
            clearable
            @keyup.enter="handleSearch"
          >
            <template #append>
              <el-button @click="handleSearch" :loading="memoryStore.isLoading">
                <el-icon><Search /></el-icon>
              </el-button>
            </template>
          </el-input>
        </el-card>

        <el-card class="memory-card" shadow="hover">
          <template #header>
            <div class="card-header">
              <span>记忆列表</span>
              <el-button size="small" @click="loadMemories" :loading="memoryStore.isLoading">
                <el-icon><Refresh /></el-icon>
              </el-button>
            </div>
          </template>

          <el-tabs v-model="memoryStore.activeType" @tab-change="handleTabChange">
            <el-tab-pane label="全部" name="all" />
            <el-tab-pane label="事实" name="fact" />
            <el-tab-pane label="偏好" name="preference" />
            <el-tab-pane label="交互" name="interaction" />
            <el-tab-pane label="摘要" name="summary" />
          </el-tabs>

          <div v-if="displayMemories.length === 0" class="empty-memories">
            <el-empty description="暂无记忆数据" :image-size="80" />
          </div>

          <div v-else class="memory-list">
            <div
              v-for="memory in displayMemories"
              :key="memory.id"
              class="memory-item"
            >
              <div class="memory-header">
                <el-tag :type="getTypeTag(memory.type)" size="small">
                  {{ getTypeLabel(memory.type) }}
                </el-tag>
                <div class="memory-importance">
                  <el-icon><Star /></el-icon>
                  <span>{{ memory.importance.toFixed(1) }}</span>
                </div>
              </div>
              <div class="memory-content">{{ memory.content }}</div>
              <div class="memory-footer">
                <span class="memory-time">{{ formatTime(memory.createdAt) }}</span>
              </div>
            </div>
          </div>
        </el-card>
      </div>

      <div class="memory-sidebar">
        <UserProfile :profile="memoryStore.userProfile" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { Search, Refresh, Star } from '@element-plus/icons-vue'
import { useMemoryStore } from '@/stores/memory'
import UserProfile from '@/components/memory/UserProfile.vue'
import dayjs from 'dayjs'

const memoryStore = useMemoryStore()

const displayMemories = computed(() => {
  if (memoryStore.searchResults.length > 0) {
    return memoryStore.searchResults
  }
  return memoryStore.memories
})

function loadMemories() {
  memoryStore.loadMemories(memoryStore.activeType)
}

async function handleSearch() {
  if (!memoryStore.searchQuery.trim()) {
    memoryStore.searchResults = []
    return
  }
  await memoryStore.searchMemory(memoryStore.searchQuery)
}

function handleTabChange(type: string | number) {
  memoryStore.setActiveType(type as string)
}

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
  return dayjs(date).format('YYYY-MM-DD HH:mm')
}

onMounted(() => {
  memoryStore.loadMemories()
  memoryStore.loadUserProfile()
})
</script>

<style scoped>
.memory-view {
  padding: 24px;
  height: 100%;
  overflow-y: auto;
}

.page-header {
  margin-bottom: 24px;
}

.page-header h2 {
  margin: 0 0 8px;
  font-size: 24px;
  color: #303133;
}

.page-header p {
  margin: 0;
  color: #909399;
  font-size: 14px;
}

.memory-layout {
  display: flex;
  gap: 24px;
}

.memory-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}

.memory-sidebar {
  width: 300px;
  flex-shrink: 0;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
}

.empty-memories {
  padding: 40px 0;
}

.memory-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.memory-item {
  background: #fafafa;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 16px;
  transition: all 0.2s;
}

.memory-item:hover {
  border-color: #409eff;
  box-shadow: 0 2px 8px rgba(64, 158, 255, 0.1);
}

.memory-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.memory-importance {
  display: flex;
  align-items: center;
  gap: 4px;
  color: #e6a23c;
  font-size: 13px;
}

.memory-content {
  font-size: 14px;
  color: #303133;
  line-height: 1.6;
  margin-bottom: 8px;
}

.memory-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.memory-time {
  font-size: 12px;
  color: #909399;
}
</style>
