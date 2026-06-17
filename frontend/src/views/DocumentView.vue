<template>
  <div class="document-view">
    <div class="page-header">
      <h2>文档管理</h2>
      <p>上传和管理知识库文档</p>
    </div>

    <el-card class="upload-card" shadow="hover">
      <el-upload
        ref="uploadRef"
        drag
        :auto-upload="false"
        :on-change="handleFileChange"
        :before-upload="beforeUpload"
        accept=".pdf,.doc,.docx,.txt,.md,.html"
        :limit="5"
      >
        <el-icon class="upload-icon"><UploadFilled /></el-icon>
        <div class="upload-text">将文件拖到此处，或<em>点击上传</em></div>
        <template #tip>
          <div class="upload-tip">
            支持 PDF、Word、TXT、Markdown、HTML 格式，单个文件不超过 50MB
          </div>
        </template>
      </el-upload>
      <div class="upload-actions">
        <el-button type="primary" @click="handleUpload" :loading="uploading" :disabled="!selectedFile">
          <el-icon><Upload /></el-icon>
          上传文档
        </el-button>
      </div>
    </el-card>

    <el-card class="table-card" shadow="hover">
      <template #header>
        <div class="table-header">
          <span>文档列表</span>
          <el-button @click="loadDocuments" :loading="loading">
            <el-icon><Refresh /></el-icon>
            刷新
          </el-button>
        </div>
      </template>

      <el-table :data="documents" v-loading="loading" stripe style="width: 100%">
        <el-table-column prop="title" label="文档名称" min-width="200">
          <template #default="{ row }">
            <div class="doc-name">
              <el-icon><Document /></el-icon>
              <span>{{ row.title }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="fileType" label="类型" width="100">
          <template #default="{ row }">
            <el-tag size="small">{{ row.fileType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="大小" width="100">
          <template #default="{ row }">
            {{ formatSize(row.fileSize) }}
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)" size="small">
              {{ getStatusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="chunkCount" label="分块数" width="80" />
        <el-table-column label="创建时间" width="160">
          <template #default="{ row }">
            {{ formatTime(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button
              type="primary"
              size="small"
              text
              @click="handleProcess(row)"
              :disabled="row.status === 'PROCESSING' || row.status === 1"
            >
              <el-icon><VideoPlay /></el-icon>
              处理
            </el-button>
            <el-button
              type="danger"
              size="small"
              text
              @click="handleDelete(row)"
            >
              <el-icon><Delete /></el-icon>
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrapper">
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
    </el-card>
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
} from '@element-plus/icons-vue'
import * as documentApi from '@/api/document'
import type { Document as DocType } from '@/types'
import dayjs from 'dayjs'

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
      status: raw.status,  // Keep as number, view handles both
      chunkCount: raw.chunkCount || 0,
      createdAt: raw.createdAt,
      updatedAt: raw.updatedAt,
      tags: raw.tags,
      source: raw.source,
    }))
    total.value = res.data.total
  } catch (error) {
    console.error('Failed to load documents:', error)
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
  } catch (error) {
    console.error('Upload failed:', error)
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
    ElMessage.success('文档处理已启动，请稍后点击刷新按钮查看状态')
    await loadDocuments()
  } catch (error: any) {
    if (error !== 'cancel' && error?.message) {
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

function formatSize(bytes: number) {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

function formatTime(date: string) {
  return dayjs(date).format('YYYY-MM-DD HH:mm')
}

function getStatusType(status: string | number) {
  const map: Record<string, string> = {
    0: 'info',
    1: 'warning',
    2: 'success',
    3: 'danger',
    pending: 'info',
    PENDING: 'info',
    processing: 'warning',
    PROCESSING: 'warning',
    completed: 'success',
    COMPLETED: 'success',
    failed: 'danger',
    FAILED: 'danger',
  }
  return map[String(status)] || 'info'
}

function getStatusLabel(status: string | number) {
  const map: Record<string, string> = {
    0: '待处理',
    1: '处理中',
    2: '已完成',
    3: '失败',
    pending: '待处理',
    PENDING: '待处理',
    processing: '处理中',
    PROCESSING: '处理中',
    completed: '已完成',
    COMPLETED: '已完成',
    failed: '失败',
    FAILED: '失败',
  }
  return map[String(status)] || String(status)
}

onMounted(() => {
  loadDocuments()
})
</script>

<style scoped>
.document-view {
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

.upload-card {
  margin-bottom: 24px;
}

.upload-icon {
  font-size: 48px;
  color: #c0c4cc;
  margin-bottom: 8px;
}

.upload-text {
  color: #606266;
  font-size: 14px;
}

.upload-text em {
  color: #409eff;
  font-style: normal;
}

.upload-tip {
  font-size: 12px;
  color: #909399;
  margin-top: 8px;
}

.upload-actions {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}

.table-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
}

.doc-name {
  display: flex;
  align-items: center;
  gap: 8px;
}

.pagination-wrapper {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
</style>
