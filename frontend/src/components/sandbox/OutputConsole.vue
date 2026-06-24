<template>
  <div class="output-console">
    <div class="console-header">
      <span class="console-title">
        <el-icon><Monitor /></el-icon>
        输出控制台
      </span>
      <div class="console-actions">
        <span v-if="executionTime > 0" class="exec-time">
          执行时间: {{ executionTime }}ms
        </span>
        <el-button size="small" text @click="$emit('clear')">
          <el-icon><Delete /></el-icon>
          清空
        </el-button>
      </div>
    </div>
    <div class="console-output" ref="outputRef">
      <pre v-if="output || error" :class="{ 'has-error': !!error }">{{ output || error }}</pre>
      <div v-else class="empty-output">
        <span>运行代码后，输出将显示在这里...</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, nextTick } from 'vue'
import { Monitor, Delete } from '@element-plus/icons-vue'

const props = defineProps<{
  output: string
  error: string
  executionTime: number
}>()

defineEmits<{
  clear: []
}>()

const outputRef = ref<HTMLElement>()

watch(
  () => [props.output, props.error],
  () => {
    nextTick(() => {
      if (outputRef.value) {
        outputRef.value.scrollTop = outputRef.value.scrollHeight
      }
    })
  }
)
</script>

<style scoped>
.output-console {
  height: 100%;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--color-border);
  border-radius: 4px;
  overflow: hidden;
}

.console-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  background: #303133;
  color: #e4e7ed;
  font-size: 13px;
}

.console-title {
  display: flex;
  align-items: center;
  gap: 6px;
}

.console-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.exec-time {
  font-size: 12px;
  color: #67c23a;
}

.empty-output {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #606266;
  font-size: 13px;
}

.console-output {
  flex: 1;
  overflow-y: auto;
  background: #1e1e1e;
  padding: 12px;
}

.console-output pre {
  margin: 0;
  font-family: 'Fira Code', 'Consolas', 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.6;
  color: #d4d4d4;
  white-space: pre-wrap;
  word-break: break-all;
}

.console-output pre.has-error {
  color: #f56c6c;
}
</style>
