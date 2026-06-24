<template>
  <div class="chat-window" ref="chatContainer">
    <div v-if="messages.length === 0" class="empty-state">
      <el-empty description="开始新的对话吧">
        <template #image>
          <div style="font-size: 64px; color: var(--color-text-placeholder)">
            <el-icon><ChatDotRound /></el-icon>
          </div>
        </template>
      </el-empty>
    </div>
    <div v-else class="message-list">
      <MessageBubble
        v-for="(msg, index) in messages"
        :key="msg.id"
        :message="msg"
        :is-streaming="isStreaming && index === messages.length - 1 && msg.role === 'assistant'"
        :is-last="index === messages.length - 1 && msg.role === 'assistant'"
        @regenerate="$emit('regenerate')"
      />
      <div v-if="isStreaming" class="typing-indicator">
        <span></span>
        <span></span>
        <span></span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, nextTick, computed } from 'vue'
import { ChatDotRound } from '@element-plus/icons-vue'
import type { ChatMessage } from '@/types'
import MessageBubble from './MessageBubble.vue'

const props = defineProps<{
  messages: ChatMessage[]
  isStreaming: boolean
}>()

defineEmits<{
  regenerate: []
}>()

const chatContainer = ref<HTMLElement>()

// Track last message content for streaming updates
const lastMessageContent = computed(() => {
  const msgs = props.messages
  return msgs.length > 0 ? msgs[msgs.length - 1].content : ''
})

function scrollToBottom() {
  nextTick(() => {
    if (chatContainer.value) {
      chatContainer.value.scrollTop = chatContainer.value.scrollHeight
    }
  })
}

watch(() => props.messages.length, scrollToBottom)
watch(lastMessageContent, scrollToBottom)
watch(() => props.isStreaming, scrollToBottom)
</script>

<style scoped>
.chat-window {
  height: 100%;
  overflow-y: auto;
  padding: 16px;
  scroll-behavior: smooth;
}

.empty-state {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.message-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.typing-indicator {
  display: flex;
  gap: 4px;
  padding: 16px;
  padding-left: 64px;
}

.typing-indicator span {
  width: 8px;
  height: 8px;
  background: var(--color-primary);
  border-radius: 50%;
  animation: bounce 1.4s infinite ease-in-out;
}

.typing-indicator span:nth-child(1) {
  animation-delay: -0.32s;
}

.typing-indicator span:nth-child(2) {
  animation-delay: -0.16s;
}

@keyframes bounce {
  0%, 80%, 100% {
    transform: scale(0);
  }
  40% {
    transform: scale(1);
  }
}
</style>
