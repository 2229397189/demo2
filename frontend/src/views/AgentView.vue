<template>
  <div class="agent-view">
    <div class="page-header">
      <h2>Agent 工作台</h2>
      <p>工具调用 · DAG 编排 · Harness 运行状态 · 模型 Provider · 审计追溯</p>
    </div>

    <el-tabs v-model="activeTab" class="agent-tabs">
      <!-- ── 工具台 ── -->
      <el-tab-pane label="工具" name="tools">
        <el-card shadow="hover" class="section-card">
          <template #header>
            <div class="card-header">
              <span>已注册工具</span>
              <el-button size="small" @click="loadTools" :loading="loading.tools">
                <el-icon><Refresh /></el-icon> 刷新
              </el-button>
            </div>
          </template>

          <el-table :data="tools" v-loading="loading.tools" stripe>
            <el-table-column prop="name" label="名称" width="160" />
            <el-table-column prop="description" label="描述" min-width="220" show-overflow-tooltip />
            <el-table-column label="风险等级" width="110">
              <template #default="{ row }">
                <el-tag :type="riskTagType(row.riskLevel)" size="small" effect="plain">
                  {{ row.riskLevel ?? '—' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="最近状态" width="130">
              <template #default="{ row }">
                <el-tag :type="statusTagType(row.status)" size="small" effect="plain">
                  {{ statusLabel(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="120" fixed="right">
              <template #default="{ row }">
                <el-button size="small" type="primary" plain @click="openExecute(row)">执行</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <!-- ── DAG 编排 ── -->
      <el-tab-pane label="DAG 编排" name="dag">
        <el-card shadow="hover" class="section-card">
          <template #header>
            <div class="card-header">
              <span>动态图执行</span>
              <div class="header-actions">
                <el-button size="small" @click="loadExampleDag">加载示例</el-button>
                <el-button size="small" type="primary" @click="runDag" :loading="loading.dag">
                  执行 DAG
                </el-button>
              </div>
            </div>
          </template>

          <el-input
            v-model="dagJson"
            type="textarea"
            :rows="10"
            placeholder='{"nodes":[{"id":"a","type":"TOOL_CALL","config":{"tool":"calculate","expression":"1+2*3"}}],"edges":[]}'
            class="dag-input"
          />
          <div v-if="dagError" class="dag-error">{{ dagError }}</div>

          <div v-if="dagResult" class="dag-result">
            <div class="result-title">执行结果</div>
            <pre>{{ prettyJson(dagResult) }}</pre>
          </div>
        </el-card>
      </el-tab-pane>

      <!-- ── Harness 状态 ── -->
      <el-tab-pane label="Harness" name="harness">
        <el-card shadow="hover" class="section-card">
          <template #header>
            <div class="card-header">
              <span>运行状态</span>
              <el-button size="small" @click="loadHarness" :loading="loading.harness">
                <el-icon><Refresh /></el-icon> 刷新
              </el-button>
            </div>
          </template>
          <div v-if="harness" class="harness-box">
            <div class="result-title">任务状态机</div>
            <pre>{{ prettyJson(harness.taskStatuses ?? {}) }}</pre>
            <div class="result-title">线程池快照</div>
            <pre>{{ prettyJson(harness.threadPool ?? {}) }}</pre>
          </div>
          <el-empty v-else-if="!loading.harness" description="暂无数据" :image-size="60" />
        </el-card>
      </el-tab-pane>

      <!-- ── 模型 Provider ── -->
      <el-tab-pane label="模型" name="models">
        <el-card shadow="hover" class="section-card">
          <template #header>
            <div class="card-header">
              <span>模型 Provider</span>
              <el-button size="small" @click="loadModels" :loading="loading.models">
                <el-icon><Refresh /></el-icon> 刷新
              </el-button>
            </div>
          </template>

          <div v-if="modelProviders" class="model-summary">
            <div class="model-line">
              <span class="model-key">当前生效 Provider</span>
              <el-tag type="success" size="small">{{ modelProviders.activeProvider }}</el-tag>
            </div>
            <div class="model-line">
              <span class="model-key">配置指定 Provider</span>
              <el-tag size="small" type="info">{{ modelProviders.configuredProvider }}</el-tag>
            </div>
          </div>

          <el-table :data="modelProviders?.providers ?? []" stripe v-loading="loading.models">
            <el-table-column prop="name" label="Provider" width="120" />
            <el-table-column label="可用性" width="120">
              <template #default="{ row }">
                <el-tag :type="row.available ? 'success' : 'info'" size="small">
                  {{ row.available ? '可用' : '不可用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="model" label="模型" min-width="200">
              <template #default="{ row }">
                <span v-if="row.model">{{ row.model }}</span>
                <span v-else class="model-muted">未配置</span>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <!-- ── 审计日志 ── -->
      <el-tab-pane label="审计" name="audit">
        <el-card shadow="hover" class="section-card">
          <template #header>
            <div class="card-header">
              <span>审计日志</span>
              <el-button size="small" @click="loadAudit" :loading="loading.audit">
                <el-icon><Refresh /></el-icon> 刷新
              </el-button>
            </div>
          </template>

          <el-table :data="auditLogs" stripe v-loading="loading.audit">
            <el-table-column prop="id" label="ID" width="70" />
            <el-table-column prop="action" label="动作" min-width="140" show-overflow-tooltip />
            <el-table-column label="风险" width="90">
              <template #default="{ row }">
                <el-tag :type="riskTagType(row.riskLevel)" size="small" effect="plain">
                  {{ row.riskLevel ?? '—' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="拦截" width="80">
              <template #default="{ row }">
                <el-tag v-if="row.blocked" type="danger" size="small">已拦截</el-tag>
                <span v-else>—</span>
              </template>
            </el-table-column>
            <el-table-column prop="ipAddress" label="IP" width="120" />
            <el-table-column label="时间" width="180">
              <template #default="{ row }">
                <span class="time-text">{{ formatTime(row.createdAt) }}</span>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 工具执行对话框 -->
    <el-dialog v-model="executeDialog.visible" :title="`执行工具：${executeDialog.toolName}`" width="520px">
      <el-input
        v-model="executeDialog.paramsJson"
        type="textarea"
        :rows="6"
        placeholder='{"query": "什么是 RAG"}'
        class="dag-input"
      />
      <div v-if="executeDialog.error" class="dag-error">{{ executeDialog.error }}</div>
      <div v-if="executeDialog.result" class="dag-result">
        <div class="result-title">返回结果</div>
        <pre>{{ prettyJson(executeDialog.result) }}</pre>
      </div>
      <template #footer>
        <el-button @click="executeDialog.visible = false">关闭</el-button>
        <el-button type="primary" @click="doExecute" :loading="executeDialog.running">执行</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import {
  listTools,
  executeTool,
  executeDag,
  harnessStatus,
  auditLogs,
  type AgentTool,
  type AuditLog,
  type DagRequest,
} from '@/api/agent'
import { listProviders, type ModelProviders } from '@/api/models'
import { formatDateTime } from '@/utils/format'

const activeTab = ref('tools')

const loading = reactive({
  tools: false,
  dag: false,
  harness: false,
  models: false,
  audit: false,
})

// ── 工具 ──
const tools = ref<AgentTool[]>([])
async function loadTools() {
  loading.tools = true
  try {
    const res = await listTools()
    tools.value = res.data ?? []
  } catch {
    /* request 层已提示 */
  } finally {
    loading.tools = false
  }
}

// ── DAG ──
const dagJson = ref('')
const dagError = ref('')
const dagResult = ref<Record<string, unknown> | null>(null)

function loadExampleDag() {
  dagJson.value = JSON.stringify(
    {
      nodes: [
        { id: 'calc', type: 'TOOL_CALL', config: { tool: 'calculate', expression: '1+2*3' } },
        { id: 'time', type: 'TOOL_CALL', config: { tool: 'current_time' } },
        { id: 'merge', type: 'MERGE', config: {} },
      ],
      edges: [
        { source: 'calc', target: 'merge' },
        { source: 'time', target: 'merge' },
      ],
    },
    null,
    2,
  )
  dagError.value = ''
  dagResult.value = null
}

async function runDag() {
  dagError.value = ''
  dagResult.value = null
  let payload: DagRequest
  try {
    payload = JSON.parse(dagJson.value)
  } catch {
    dagError.value = 'JSON 解析失败，请检查输入'
    return
  }
  if (!payload || !payload.nodes || payload.nodes.length === 0) {
    dagError.value = 'nodes 不能为空'
    return
  }
  loading.dag = true
  try {
    const res = await executeDag(payload)
    dagResult.value = res.data ?? {}
  } catch {
    /* request 层已提示 */
  } finally {
    loading.dag = false
  }
}

// ── Harness ──
const harness = ref<Record<string, unknown> | null>(null)
async function loadHarness() {
  loading.harness = true
  try {
    const res = await harnessStatus()
    harness.value = res.data ?? {}
  } catch {
    /* request 层已提示 */
  } finally {
    loading.harness = false
  }
}

// ── 模型 ──
const modelProviders = ref<ModelProviders | null>(null)
async function loadModels() {
  loading.models = true
  try {
    const res = await listProviders()
    modelProviders.value = res.data ?? null
  } catch {
    /* request 层已提示 */
  } finally {
    loading.models = false
  }
}

// ── 审计 ──
const auditLogsData = ref<AuditLog[]>([])
async function loadAudit() {
  loading.audit = true
  try {
    const res = await auditLogs(undefined, 50)
    auditLogsData.value = res.data ?? []
  } catch {
    /* request 层已提示 */
  } finally {
    loading.audit = false
  }
}

// ── 工具执行对话框 ──
const executeDialog = reactive({
  visible: false,
  toolName: '',
  paramsJson: '{}',
  error: '',
  result: null as Record<string, unknown> | null,
  running: false,
})

function openExecute(row: AgentTool) {
  executeDialog.toolName = row.name
  executeDialog.paramsJson = '{}'
  executeDialog.error = ''
  executeDialog.result = null
  executeDialog.visible = true
}

async function doExecute() {
  executeDialog.error = ''
  executeDialog.result = null
  let params: Record<string, unknown>
  try {
    params = JSON.parse(executeDialog.paramsJson || '{}')
  } catch {
    executeDialog.error = 'JSON 解析失败，请检查参数'
    return
  }
  executeDialog.running = true
  try {
    const res = await executeTool(executeDialog.toolName, params)
    executeDialog.result = res.data ?? {}
    if (res.code && res.code !== 200) {
      executeDialog.error = res.message || '执行失败'
    }
  } catch {
    /* request 层已提示 */
  } finally {
    executeDialog.running = false
  }
}

// ── 工具函数 ──
function riskTagType(level: string | null): 'success' | 'warning' | 'danger' | 'info' {
  if (level === 'SAFE') return 'success'
  if (level === 'WARN') return 'warning'
  if (level === 'BLOCK') return 'danger'
  return 'info'
}

function statusTagType(status: string | null): 'success' | 'danger' | 'warning' | 'info' {
  if (status === 'SUCCESS') return 'success'
  if (status === 'FAILURE' || status === 'TIMEOUT') return 'danger'
  if (status === 'PARTIAL') return 'warning'
  return 'info'
}

function statusLabel(status: string | null): string {
  if (!status) return '未执行'
  const map: Record<string, string> = {
    SUCCESS: '成功',
    FAILURE: '失败',
    TIMEOUT: '超时',
    PARTIAL: '部分',
    NOT_EXECUTED: '未执行',
  }
  return map[status] ?? status
}

function prettyJson(obj: unknown): string {
  try {
    return JSON.stringify(obj, null, 2)
  } catch {
    return String(obj)
  }
}

function formatTime(t: string): string {
  if (!t) return '—'
  try {
    return formatDateTime(t)
  } catch {
    return t
  }
}

onMounted(() => {
  loadTools()
})
</script>

<style scoped>
.agent-view {
  height: 100%;
  overflow-y: auto;
  padding: var(--space-6);
}

.page-header {
  margin-bottom: var(--space-5);
}

.page-header h2 {
  margin: 0;
  font-size: var(--text-xl);
  font-weight: 600;
  color: var(--color-text-primary);
}

.page-header p {
  margin: var(--space-1) 0 0;
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
}

.agent-tabs {
  width: 100%;
}

.section-card {
  margin-bottom: var(--space-4);
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.header-actions {
  display: flex;
  gap: var(--space-2);
}

.dag-input {
  margin-bottom: var(--space-3);
  font-family: var(--font-mono);
}

.dag-error {
  color: var(--color-danger);
  font-size: var(--text-sm);
  margin-bottom: var(--space-3);
}

.dag-result {
  margin-top: var(--space-3);
}

.result-title {
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--color-text-primary);
  margin-bottom: var(--space-2);
}

.dag-result pre,
.harness-box pre {
  background-color: var(--color-bg-tertiary);
  border-radius: var(--radius-md);
  padding: var(--space-3);
  font-size: var(--text-xs);
  font-family: var(--font-mono);
  overflow-x: auto;
  margin: 0 0 var(--space-3);
  color: var(--color-text-primary);
}

.model-summary {
  display: flex;
  gap: var(--space-6);
  margin-bottom: var(--space-4);
}

.model-line {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.model-key {
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
}

.model-muted {
  color: var(--color-text-tertiary);
  font-size: var(--text-sm);
}

.time-text {
  font-size: var(--text-xs);
  color: var(--color-text-secondary);
}
</style>
