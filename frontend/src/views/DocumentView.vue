<template>
  <div class="document-view">
    <div class="page-header">
      <div class="header-content">
        <h1>文档管理</h1>
        <p>上传和管理知识库文档，支持 PDF、Word、TXT、Markdown、HTML 格式</p>
      </div>
    </div>

    <!-- 上传区域 -->
    <div class="upload-section">
      <div class="upload-card" :class="{ 'has-file': selectedFile }">
        <el-upload
          ref="uploadRef"
          drag
          :auto-upload="false"
          :on-change="handleFileChange"
          :before-upload="beforeUpload"
          accept=".pdf,.doc,.docx,.txt,.md,.html"
          :limit="5"
          class="upload-area"
        >
          <div class="upload-content">
            <div class="upload-icon-wrapper">
              <el-icon :size="40"><UploadFilled /></el-icon>
            </div>
            <div class="upload-text">
              <p class="upload-main">将文件拖到此处，或 <em>点击上传</em></p>
              <p class="upload-hint">支持 PDF、Word、TXT、Markdown、HTML 格式，单个文件不超过 50MB</p>
            </div>
          </div>
        </el-upload>
        <div class="upload-actions">
          <el-button
            type="primary"
            @click="handleUpload"
            :loading="uploading"
            :disabled="!selectedFile"
            size="large"
          >
            <el-icon><Upload /></el-icon>
            上传文档
          </el-button>
        </div>
      </div>
    </div>

    <!-- 文档列表 -->
    <div class="documents-section">
      <div class="section-header">
        <h2>文档列表</h2>
        <el-button @click="loadDocuments" :loading="loading" text>
          <el-icon><Refresh /></el-icon>
          刷新
        </el-button>
      </div>

      <!-- 骨架屏加载 -->
      <SkeletonLoader v-if="loading && documents.length === 0" variant="list" :count="5" />

      <!-- 文档列表 -->
      <div v-else class="documents-grid">
        <TransitionGroup name="list">
          <div
            v-for="doc in documents"
            :key="doc.id"
            class="document-card card-hover"
          >
            <div class="doc-icon" :class="getDocIconClass(doc.fileType)">
              <el-icon :size="24"><Document /></el-icon>
            </div>
            <div class="doc-content">
              <h3 class="doc-title">{{ doc.title }}</h3>
              <div class="doc-meta">
                <span class="doc-type">
                  <el-tag :type="getDocTypeTag(doc.fileType)" size="small" effect="plain">
                    {{ doc.fileType?.toUpperCase() }}
                  </el-tag>
                </span>
                <span class="doc-size">{{ formatFileSize(doc.fileSize) }}</span>
                <span class="doc-chunks">{{ doc.chunkCount }} 个分块</span>
              </div>
              <div class="doc-status">
                <el-tag :type="getStatusType(doc.status)" size="small" effect="light">
                  <el-icon v-if="doc.status === 1" class="spin" :size="12"><Loading /></el-icon>
                  {{ getStatusLabel(doc.status) }}
                </el-tag>
                <span class="doc-time">{{ formatRelativeTime(doc.createdAt) }}</span>
              </div>
            </div>
            <div class="doc-actions">
              <el-button
                type="primary"
                size="small"
                text
                @click="handleProcess(doc)"
                :disabled="doc.status === 1"
              >
                <el-icon><VideoPlay /></el-icon>
                处理
              </el-button>
              <el-button
                type="danger"
                size="small"
                text
                @click="handleDelete(doc)"
              >
                <el-icon><Delete /></el-icon>
                删除
              </el-button>
            </div>
          </div>
        </TransitionGroup>

        <!-- 空状态 -->
        <div v-if="documents.length === 0 && !loading" class="empty-state">
          <el-icon :size="64" color="var(--color-text-tertiary)"><Document /></el-icon>
          <h3>暂无文档</h3>
          <p>上传您的第一个文档，开始构建知识库</p>
        </div>
      </div>

      <!-- 分页 -->
      <div v-if="total > 0" class="pagination-wrapper">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @size-change="loadDocuments"
          @current-change="loadDocuments"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  UploadFilled,
  Upload,
  Refresh,
  Document,
  VideoPlay,
  Delete,
  Loading,
} from '@element-plus/icons-vue'
import * as documentApi from '@/api/document'
import type { Document as DocType } from '@/types'
import SkeletonLoader from '@/components/common/SkeletonLoader.vue'
import { formatFileSize, formatRelativeTime, getStatusType, getStatusLabel, documentStatusMap } from '@/utils/format'

const documents = ref<DocType[]>([])
const loading = ref(false)
const uploading = ref(false)
const selectedFile = ref<File | null>(null)
const currentPage = ref(1)
const pageSize = ref(20)
const total = ref(0)

async function loadDocuments() {
  loading.value = true
  try {
    const res = await documentApi.listDocuments(currentPage.value, pageSize.value)
    documents.value = (res.data.records as any[]).map((raw: any) => ({
      id: String(raw.id),
      title: raw.title || '',
      fileType: raw.fileType || '',
      fileSize: raw.fileSize || 0,
      status: raw.status,
      chunkCount: raw.chunkCount || 0,
      createdAt: raw.createdAt,
      updatedAt: raw.updatedAt,
      tags: raw.tags,
      source: raw.source,
    }))
    total.value = res.data.total
  } catch (error) {
    console.error('Failed to load documents:', error)
    ElMessage.error('加载文档列表失败')
  } finally {
    loading.value = false
  }
}

function handleFileChange(file: any) {
  selectedFile.value = file.raw
}

function beforeUpload(file: File) {
  const maxSize = 50 * 1024 * 1024
  if (file.size > maxSize) {
    ElMessage.error('文件大小不能超过 50MB')
    return false
  }
  return true
}

async function handleUpload() {
  if (!selectedFile.value) return
  uploading.value = true
  try {
    await documentApi.uploadDocument(selectedFile.value)
    ElMessage.success('文档上传成功')
    selectedFile.value = null
    await loadDocuments()
  } catch (error: any) {
    ElMessage.error(error.message || '文档上传失败')
  } finally {
    uploading.value = false
  }
}

async function handleProcess(doc: DocType) {
  try {
    await ElMessageBox.confirm(
      `确定处理文档 "${doc.title}" 吗？`,
      '处理确认',
      { type: 'info' }
    )
    await documentApi.processDocument(doc.id)
    ElMessage.success('文档处理已启动，请稍后刷新查看状态')
    await loadDocuments()
  } catch (error: any) {
    if (error !== 'cancel') {
      ElMessage.error(error.message || '文档处理失败')
    }
  }
}

async function handleDelete(doc: DocType) {
  try {
    await ElMessageBox.confirm(
      `确定删除文档 "${doc.title}" 吗？此操作不可恢复。`,
      '删除确认',
      { type: 'warning' }
    )
    await documentApi.deleteDocument(doc.id)
    ElMessage.success('文档已删除')
    await loadDocuments()
  } catch {
    // cancelled
  }
}

function getDocIconClass(fileType: string): string {
  const map: Record<string, string> = {
    pdf: 'icon-pdf',
    doc: 'icon-word',
    docx: 'icon-word',
    md: 'icon-markdown',
    txt: 'icon-text',
    html: 'icon-html',
  }
  return map[fileType?.toLowerCase()] || 'icon-default'
}

function getDocTypeTag(fileType: string): string {
  const map: Record<string, string> = {
    pdf: 'danger',
    doc: 'primary',
    docx: 'primary',
    md: 'success',
    txt: 'info',
    html: 'warning',
  }
  return map[fileType?.toLowerCase()] || 'info'
}

onMounted(() => {
  loadDocuments()
})
</script>

<style scoped>
.document-view {
  padding: var(--space-6);
  height: 100%;
  overflow-y: auto;
}

.page-header {
  margin-bottom: var(--space-6);
}

.header-content h1 {
  margin: 0 0 var(--space-2);
  font-size: var(--text-2xl);
  font-weight: 700;
  color: var(--color-text-primary);
}

.header-content p {
  margin: 0;
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
}

/* ── 上传区域 ── */
.upload-section {
  margin-bottom: var(--space-8);
}

.upload-card {
  background-color: var(--color-bg-primary);
  border: 2px dashed var(--color-border);
  border-radius: var(--radius-xl);
  padding: var(--space-6);
  transition: all var(--transition-normal);
}

.upload-card:hover,
.upload-card.has-file {
  border-color: var(--color-primary);
  background-color: var(--color-primary-bg);
}

.upload-area :deep(.el-upload-dragger) {
  border: none;
  background: transparent;
  padding: var(--space-8);
}

.upload-content {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-4);
}

.upload-icon-wrapper {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 80px;
  height: 80px;
  border-radius: var(--radius-xl);
  background: linear-gradient(135deg, var(--color-primary-bg), var(--color-primary-border));
  color: var(--color-primary);
}

.upload-text {
  text-align: center;
}

.upload-main {
  font-size: var(--text-base);
  color: var(--color-text-primary);
  margin: 0 0 var(--space-2);
}

.upload-main em {
  color: var(--color-primary);
  font-style: normal;
  font-weight: 500;
  cursor: pointer;
}

.upload-hint {
  font-size: var(--text-sm);
  color: var(--color-text-tertiary);
  margin: 0;
}

.upload-actions {
  display: flex;
  justify-content: center;
  margin-top: var(--space-4);
}

/* ── 文档列表 ── */
.documents-section {
  background-color: var(--color-bg-primary);
  border-radius: var(--radius-xl);
  padding: var(--space-6);
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-6);
}

.section-header h2 {
  margin: 0;
  font-size: var(--text-lg);
  font-weight: 600;
  color: var(--color-text-primary);
}

.documents-grid {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.document-card {
  display: flex;
  align-items: center;
  gap: var(--space-4);
  padding: var(--space-4);
  background-color: var(--color-bg-primary);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  transition: all var(--transition-normal);
}

.document-card:hover {
  border-color: var(--color-primary);
  box-shadow: var(--shadow-md);
}

.doc-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  border-radius: var(--radius-lg);
  flex-shrink: 0;
}

.icon-pdf {
  background-color: rgba(239, 68, 68, 0.1);
  color: var(--color-danger);
}

.icon-word {
  background-color: rgba(59, 130, 246, 0.1);
  color: var(--color-info);
}

.icon-markdown {
  background-color: rgba(16, 185, 129, 0.1);
  color: var(--color-success);
}

.icon-text {
  background-color: rgba(100, 116, 139, 0.1);
  color: var(--color-text-secondary);
}

.icon-html {
  background-color: rgba(245, 158, 11, 0.1);
  color: var(--color-warning);
}

.icon-default {
  background-color: var(--color-bg-tertiary);
  color: var(--color-text-secondary);
}

.doc-content {
  flex: 1;
  min-width: 0;
}

.doc-title {
  margin: 0 0 var(--space-2);
  font-size: var(--text-base);
  font-weight: 500;
  color: var(--color-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.doc-meta {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-bottom: var(--space-2);
}

.doc-size,
.doc-chunks {
  font-size: var(--text-xs);
  color: var(--color-text-tertiary);
}

.doc-status {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.doc-time {
  font-size: var(--text-xs);
  color: var(--color-text-tertiary);
}

.doc-actions {
  display: flex;
  gap: var(--space-2);
  flex-shrink: 0;
}

/* ── 空状态 ── */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: var(--space-12);
  text-align: center;
}

.empty-state h3 {
  margin: var(--space-4) 0 var(--space-2);
  font-size: var(--text-lg);
  color: var(--color-text-primary);
}

.empty-state p {
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
}

/* ── 分页 ── */
.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--space-6);
  padding-top: var(--space-4);
  border-top: 1px solid var(--color-border-light);
}

/* ── 响应式设计 ── */
@media (max-width: 768px) {
  .document-view {
    padding: var(--space-4);
  }

  .document-card {
    flex-direction: column;
    align-items: flex-start;
  }

  .doc-actions {
    width: 100%;
    justify-content: flex-end;
  }
}
</style>
