<template>
  <el-card class="user-profile-card" shadow="hover">
    <template #header>
      <div class="card-header">
        <el-icon><User /></el-icon>
        <span>用户画像</span>
      </div>
    </template>
    <div v-if="profile" class="profile-content">
      <div class="profile-item">
        <label>用户ID</label>
        <span>{{ profile.userId ?? '未知' }}</span>
      </div>
      <div class="profile-item">
        <label>总记忆数</label>
        <span>{{ profile.totalMemories ?? 0 }}</span>
      </div>
      <div class="profile-item">
        <label>平均重要性</label>
        <span>{{ (profile.averageImportance as number)?.toFixed(2) ?? '-' }}</span>
      </div>
      <div v-if="(profile.topAccessedMemories as string[])?.length" class="profile-item">
        <label>高频记忆</label>
        <div class="facts-list">
          <el-tag v-for="(mem, index) in (profile.topAccessedMemories as string[])" :key="index" size="small" class="fact-tag">
            {{ mem }}
          </el-tag>
        </div>
      </div>
      <div v-if="profile.memoriesByType" class="profile-item">
        <label>按类型分布</label>
        <div class="preferences-list">
          <el-tag
            v-for="(value, key) in (profile.memoriesByType as Record<string, string[]>)"
            :key="key"
            size="small"
            type="warning"
            class="pref-tag"
          >
            {{ key }}: {{ (value as string[]).length }}条
          </el-tag>
        </div>
      </div>
    </div>
    <el-empty v-else description="暂无用户画像数据" :image-size="60" />
  </el-card>
</template>

<script setup lang="ts">
import { User } from '@element-plus/icons-vue'

defineProps<{
  profile: Record<string, unknown> | null
}>()
</script>

<style scoped>
.user-profile-card {
  height: 100%;
}

.card-header {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
}

.profile-content {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.profile-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.profile-item label {
  font-size: 12px;
  color: #909399;
  font-weight: 500;
}

.profile-item span {
  font-size: 14px;
  color: #303133;
}

.facts-list,
.preferences-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 4px;
}

.fact-tag,
.pref-tag {
  max-width: 100%;
  white-space: normal;
  height: auto;
}
</style>
