<template>
  <div class="page-container">
    <div class="page-header">
      <span class="page-title">📦 素材库</span>
      <el-upload
        :action="`/api/assets/upload?projectId=${projectId}`"
        :headers="uploadHeaders"
        multiple
        :on-success="onUploadSuccess"
        :on-error="onUploadError"
        :show-file-list="false"
      >
        <el-button type="primary" :icon="Upload">上传素材</el-button>
      </el-upload>
    </div>

    <div class="filter-bar">
      <el-radio-group v-model="typeFilter" @change="load">
        <el-radio-button value="">全部</el-radio-button>
        <el-radio-button value="image">图片</el-radio-button>
        <el-radio-button value="video">视频</el-radio-button>
        <el-radio-button value="audio">音频</el-radio-button>
      </el-radio-group>
    </div>

    <div class="asset-grid" v-if="assets.length">
      <div v-for="asset in assets" :key="asset.id" class="asset-card">
        <div class="asset-preview">
          <img v-if="asset.type === 'image'" :src="asset.url" :alt="asset.name" />
          <video v-else-if="asset.type === 'video'" :src="asset.url" controls style="width:100%;height:100%;object-fit:cover" />
          <div v-else class="asset-placeholder">
            <el-icon size="32" color="#a0aec0"><Document /></el-icon>
          </div>
        </div>
        <div class="asset-info">
          <div class="asset-name" :title="asset.name">{{ asset.name }}</div>
          <div class="asset-meta">
            <span class="asset-type">{{ asset.type }}</span>
            <span class="asset-size">{{ formatSize(asset.fileSize) }}</span>
          </div>
          <el-popconfirm title="确认删除？" @confirm="deleteAsset(asset.id)">
            <template #reference>
              <el-button size="small" type="danger" text style="width:100%">删除</el-button>
            </template>
          </el-popconfirm>
        </div>
      </div>
    </div>
    <el-empty v-else description="暂无素材" />

    <el-pagination
      v-if="total > pageSize"
      :total="total"
      :page-size="pageSize"
      :current-page="page"
      layout="prev, pager, next"
      style="margin-top: 20px; justify-content: center"
      @current-change="(p: number) => { page = p; load() }"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Upload } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import request from '@/api/request'

const route = useRoute()
const userStore = useUserStore()
const projectId = route.params.id

const assets = ref<any[]>([])
const typeFilter = ref('')
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)

const uploadHeaders = computed(() => ({
  Authorization: `Bearer ${userStore.token}`,
}))

const formatSize = (bytes: number) => {
  if (!bytes) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(1) + ' MB'
}

async function load() {
  const params: any = { page: page.value, size: pageSize.value }
  if (typeFilter.value) params.type = typeFilter.value
  const res = await request.get(`/assets/project/${projectId}`, { params })
  const data = res.data.data
  assets.value = data.records || []
  total.value = data.total || 0
}

function onUploadSuccess() {
  ElMessage.success('上传成功')
  load()
}

function onUploadError() {
  ElMessage.error('上传失败')
}

async function deleteAsset(id: number) {
  await request.delete(`/assets/${id}`)
  ElMessage.success('删除成功')
  load()
}

onMounted(load)
</script>

<style scoped>
.page-container { padding: 24px; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; }
.page-title { font-size: 20px; font-weight: 600; }
.filter-bar { margin-bottom: 20px; }

.asset-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 16px; }

.asset-card { background: #fff; border-radius: 10px; overflow: hidden; box-shadow: 0 2px 6px rgba(0,0,0,0.07); }
.asset-preview { height: 140px; background: #f7f8fa; overflow: hidden; }
.asset-preview img { width: 100%; height: 100%; object-fit: cover; }
.asset-placeholder { height: 100%; display: flex; align-items: center; justify-content: center; }
.asset-info { padding: 10px; }
.asset-name { font-size: 12px; color: #1a202c; font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; margin-bottom: 4px; }
.asset-meta { display: flex; justify-content: space-between; margin-bottom: 8px; }
.asset-type { font-size: 11px; color: #6366f1; background: #e0e7ff; padding: 1px 6px; border-radius: 6px; }
.asset-size { font-size: 11px; color: #718096; }
</style>
