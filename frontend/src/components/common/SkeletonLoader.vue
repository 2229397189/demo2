<template>
  <div class="skeleton-loader" :class="[`skeleton-${variant}`]">
    <div v-if="variant === 'card'" class="skeleton-card">
      <div class="skeleton-header">
        <div class="skeleton-avatar skeleton"></div>
        <div class="skeleton-title-group">
          <div class="skeleton-title skeleton" :style="{ width: titleWidth }"></div>
          <div class="skeleton-subtitle skeleton" :style="{ width: subtitleWidth }"></div>
        </div>
      </div>
      <div class="skeleton-body">
        <div v-for="i in lines" :key="i" class="skeleton-line skeleton" :style="{ width: i === lines ? '60%' : '100%' }"></div>
      </div>
    </div>

    <div v-else-if="variant === 'list'" class="skeleton-list">
      <div v-for="i in count" :key="i" class="skeleton-list-item">
        <div class="skeleton-icon skeleton"></div>
        <div class="skeleton-content">
          <div class="skeleton-line skeleton" style="width: 70%"></div>
          <div class="skeleton-line skeleton" style="width: 40%; height: 10px"></div>
        </div>
      </div>
    </div>

    <div v-else-if="variant === 'chat'" class="skeleton-chat">
      <div v-for="i in count" :key="i" class="skeleton-message" :class="{ 'skeleton-user': i % 2 === 0 }">
        <div class="skeleton-avatar skeleton"></div>
        <div class="skeleton-bubble">
          <div class="skeleton-line skeleton" style="width: 80%"></div>
          <div class="skeleton-line skeleton" style="width: 60%"></div>
          <div v-if="i % 3 === 0" class="skeleton-line skeleton" style="width: 40%"></div>
        </div>
      </div>
    </div>

    <div v-else class="skeleton-basic">
      <div v-for="i in count" :key="i" class="skeleton-line skeleton" :style="{ width: i === count ? '60%' : '100%' }"></div>
    </div>
  </div>
</template>

<script setup lang="ts">
interface Props {
  variant?: 'card' | 'list' | 'chat' | 'basic'
  count?: number
  lines?: number
  titleWidth?: string
  subtitleWidth?: string
}

withDefaults(defineProps<Props>(), {
  variant: 'basic',
  count: 3,
  lines: 3,
  titleWidth: '60%',
  subtitleWidth: '40%',
})
</script>

<style scoped>
.skeleton-loader {
  padding: var(--space-4);
}

/* ── 基础骨架 ── */
.skeleton-basic {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

/* ── 卡片骨架 ── */
.skeleton-card {
  background-color: var(--color-bg-primary);
  border-radius: var(--radius-lg);
  padding: var(--space-4);
}

.skeleton-header {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-bottom: var(--space-4);
}

.skeleton-avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  flex-shrink: 0;
}

.skeleton-title-group {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.skeleton-title {
  height: 16px;
  border-radius: var(--radius-sm);
}

.skeleton-subtitle {
  height: 12px;
  border-radius: var(--radius-sm);
}

.skeleton-body {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.skeleton-line {
  height: 14px;
  border-radius: var(--radius-sm);
}

/* ── 列表骨架 ── */
.skeleton-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.skeleton-list-item {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-3);
  border-radius: var(--radius-md);
}

.skeleton-icon {
  width: 36px;
  height: 36px;
  border-radius: var(--radius-md);
  flex-shrink: 0;
}

.skeleton-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

/* ── 聊天骨架 ── */
.skeleton-chat {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.skeleton-message {
  display: flex;
  gap: var(--space-3);
  max-width: 80%;
}

.skeleton-message.skeleton-user {
  align-self: flex-end;
  flex-direction: row-reverse;
}

.skeleton-message .skeleton-avatar {
  width: 32px;
  height: 32px;
}

.skeleton-bubble {
  background-color: var(--color-bg-tertiary);
  border-radius: var(--radius-lg);
  padding: var(--space-3);
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.skeleton-user .skeleton-bubble {
  background-color: var(--color-primary-bg);
}
</style>
