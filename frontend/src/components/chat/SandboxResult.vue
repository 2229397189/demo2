<template>
  <div class="sandbox-result">
    <div class="sandbox-header" @click="expanded = !expanded">
      <el-icon><Monitor /></el-icon>
      <span>代码执行结果</span>
      <el-tag size="small" :type="hasError ? 'danger' : 'success'" style="margin-left: 8px">
        {{ hasError ? '执行出错' : '执行成功' }}
      </el-tag>
      <el-tag v-if="result.executionTime" size="small" type="info" style="margin-left: 4px">
        {{ result.executionTime }}ms
      </el-tag>
      <el-icon class="expand-icon" :class="{ expanded }"><ArrowDown /></el-icon>
    </div>
    <el-collapse-transition>
      <div v-show="expanded" class="sandbox-body">
        <div class="sandbox-code">
          <div class="code-header">
            <el-tag size="small">{{ result.language || 'unknown' }}</el-tag>
            <span class="code-label">代码</span>
          </div>
          <pre class="hljs"><code>{{ result.code }}</code></pre>
        </div>
        <div v-if="result.output" class="sandbox-output">
          <div class="output-header">
            <el-icon><Document /></el-icon>
            <span>输出</span>
          </div>
          <pre class="output-content">{{ result.output }}</pre>
        </div>
        <div v-if="result.error" class="sandbox-error">
          <div class="error-header">
            <el-icon><WarningFilled /></el-icon>
            <span>错误</span>
          </div>
          <pre class="error-content">{{ result.error }}</pre>
        </div>
      </div>
    </el-collapse-transition>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { Monitor, ArrowDown, Document, WarningFilled } from '@element-plus/icons-vue'
import type { SandboxExecution } from '@/types'

const props = defineProps<{
  result: SandboxExecution
}>()

const expanded = ref(false)
const hasError = computed(() => !!props.result.error || props.result.exitCode !== 0)
</script>

<style scoped>
.sandbox-result {
  margin-top: 8px;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  overflow: hidden;
}

.sandbox-header {
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

.sandbox-header:hover {
  background: #ecf5ff;
}

.expand-icon {
  margin-left: auto;
  transition: transform 0.3s;
}

.expand-icon.expanded {
  transform: rotate(180deg);
}

.sandbox-body {
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.sandbox-code {
  border-radius: 6px;
  overflow: hidden;
}

.code-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  background: #282c34;
  font-size: 12px;
  color: #abb2bf;
}

.code-label {
  font-size: 12px;
  color: #abb2bf;
}

.sandbox-code pre {
  margin: 0;
  padding: 12px;
  background: #282c34;
  color: #abb2bf;
  font-size: 13px;
  line-height: 1.5;
  overflow-x: auto;
  max-height: 200px;
}

.sandbox-output,
.sandbox-error {
  border-radius: 6px;
  overflow: hidden;
}

.output-header,
.error-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  font-size: 12px;
}

.output-header {
  background: #f0f9eb;
  color: #67c23a;
}

.error-header {
  background: #fef0f0;
  color: #f56c6c;
}

.output-content {
  margin: 0;
  padding: 10px;
  background: #fafafa;
  font-size: 13px;
  line-height: 1.5;
  overflow-x: auto;
  max-height: 200px;
  color: #303133;
}

.error-content {
  margin: 0;
  padding: 10px;
  background: #fff5f5;
  font-size: 13px;
  line-height: 1.5;
  overflow-x: auto;
  max-height: 200px;
  color: #f56c6c;
}
</style>
