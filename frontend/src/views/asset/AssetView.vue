<template>
  <div class="asset-browser">
    <header class="asset-header">
      <div>
        <p>媒体浏览器</p>
        <h1>素材资产库</h1>
        <span>集中查看定妆图、场景参考、视频片段和声音素材。</span>
      </div>
      <el-upload
        :action="`/api/assets/upload?projectId=${projectId}`"
        :headers="uploadHeaders"
        multiple
        :on-success="onUploadSuccess"
        :on-error="onUploadError"
        :show-file-list="false"
      >
        <el-button type="primary" :icon="Upload">新增资产</el-button>
      </el-upload>
    </header>

    <section class="asset-shell">
      <aside class="asset-sidebar">
        <button type="button" :class="{ active: typeFilter === '' }" @click="typeFilter = ''; load()">
          <span>全部资产</span>
          <b>{{ total }}</b>
        </button>
        <button type="button" :class="{ active: typeFilter === 'image' }" @click="typeFilter = 'image'; load()">图片</button>
        <button type="button" :class="{ active: typeFilter === 'video' }" @click="typeFilter = 'video'; load()">视频</button>
        <button type="button" :class="{ active: typeFilter === 'audio' }" @click="typeFilter = 'audio'; load()">音频</button>
      </aside>

      <main class="asset-main">
        <div class="asset-toolbar">
          <span>{{ assetToolbarText }}</span>
          <div class="asset-filters">
            <button type="button">按类型</button>
            <button type="button">按大小</button>
            <button type="button">最近更新</button>
          </div>
        </div>

        <div class="asset-grid" v-if="assets.length">
          <article v-for="asset in assets" :key="asset.id" class="asset-card">
            <div class="asset-preview">
              <img v-if="asset.type === 'image'" :src="asset.url" :alt="asset.name" />
              <video v-else-if="asset.type === 'video'" :src="asset.url" controls />
              <div v-else class="asset-placeholder">
                <el-icon size="32"><Document /></el-icon>
              </div>
              <span class="asset-kind">{{ assetTypeLabel(asset.type) }}</span>
            </div>
            <div class="asset-info">
              <div class="asset-name" :title="asset.name">{{ asset.name }}</div>
              <div class="asset-line">
                <span>{{ formatSize(asset.fileSize) }}</span>
                <el-popconfirm title="确认删除？" @confirm="deleteAsset(asset.id)">
                  <template #reference>
                    <el-button size="small" type="danger" text @click.stop>删除</el-button>
                  </template>
                </el-popconfirm>
              </div>
            </div>
          </article>
        </div>

        <div v-else class="asset-empty">
          <h2>素材资产待导入</h2>
          <p>角色定妆图、场景参考和成片素材会在这里集中浏览。</p>
        </div>
      </main>

      <aside class="asset-inspector">
        <template v-if="activeAsset">
          <span class="inspector-kicker">当前选中</span>
          <h2>{{ activeAsset.name }}</h2>
          <dl>
            <div><dt>类型</dt><dd>{{ assetTypeLabel(activeAsset.type) }}</dd></div>
            <div><dt>体积</dt><dd>{{ formatSize(activeAsset.fileSize) }}</dd></div>
            <div><dt>编号</dt><dd>{{ String(activeAsset.id).padStart(8, '0') }}</dd></div>
          </dl>
        </template>
        <template v-else>
          <span class="inspector-kicker">资产信息</span>
          <h2>暂无选中素材</h2>
          <p>导入或生成素材后，这里显示基础信息。</p>
        </template>
      </aside>
    </section>

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
import { Document, Upload } from '@element-plus/icons-vue'
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
const activeAsset = computed(() => assets.value[0] || null)
const assetToolbarText = computed(() => {
  if (typeFilter.value) return `${assetTypeLabel(typeFilter.value)} · ${total.value} 个`
  return `全部资产 · ${total.value} 个`
})

const uploadHeaders = computed(() => ({
  Authorization: `Bearer ${userStore.token}`,
}))

const ASSET_TYPE_LABELS: Record<string, string> = {
  image: '角色/场景',
  video: '视频素材',
  audio: '音频素材',
}

const assetTypeLabel = (type: string) => ASSET_TYPE_LABELS[type] || '其他素材'

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
.asset-browser {
  min-height: 100%;
  padding: 30px;
  overflow: auto;
  background: var(--page-environment);
  color: var(--text-primary);
}

.asset-header,
.asset-shell {
  max-width: 1280px;
  margin: 0 auto;
}

.asset-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 22px;
}

.asset-header p {
  margin: 0 0 8px;
  color: var(--primary);
  font-size: 14px;
  font-weight: 800;
}

.asset-header h1 {
  margin: 0;
  font-size: clamp(30px, 4vw, 46px);
  line-height: 1.08;
  letter-spacing: 0;
}

.asset-header span {
  display: block;
  margin-top: 10px;
  color: var(--text-secondary);
  font-size: 16px;
}

.asset-shell {
  display: grid;
  grid-template-columns: 190px minmax(0, 1fr) 290px;
  gap: 18px;
}

.asset-sidebar,
.asset-main,
.asset-inspector {
  border: 1px solid var(--border);
  border-radius: 20px;
  background: var(--surface-panel);
  backdrop-filter: blur(var(--glass-blur)) saturate(145%);
  box-shadow: var(--shadow-md), inset 0 1px 0 rgba(255, 255, 255, 0.055);
}

.asset-sidebar {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px;
}

.asset-sidebar button,
.asset-filters button {
  border: 1px solid transparent;
  border-radius: 14px;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  font-size: 15px;
}

.asset-sidebar button {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 44px;
  padding: 0 12px;
  text-align: left;
}

.asset-sidebar button.active {
  border-color: var(--border-strong);
  background: var(--surface-panel-strong);
  color: var(--text-primary);
}

.asset-sidebar b {
  color: var(--primary);
}

.asset-main {
  min-height: 690px;
  padding: 18px;
}

.asset-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  margin-bottom: 16px;
  color: var(--text-secondary);
  font-weight: 800;
}

.asset-filters {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.asset-filters button {
  height: 36px;
  padding: 0 12px;
  background: var(--bg-muted);
}

.asset-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 16px;
}

.asset-card {
  border: 1px solid var(--border);
  border-radius: 18px;
  overflow: hidden;
  background: rgba(255, 255, 255, 0.045);
  transition: transform 0.18s, border-color 0.18s, background 0.18s;
}

.asset-card:hover {
  transform: translateY(-1px);
  border-color: var(--border-strong);
  background: rgba(255, 255, 255, 0.075);
}

.asset-preview {
  position: relative;
  height: 170px;
  overflow: hidden;
  background:
    linear-gradient(rgba(255, 255, 255, 0.035) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.035) 1px, transparent 1px),
    radial-gradient(circle at 20% 18%, rgba(91, 208, 255, 0.1), transparent 36%),
    rgba(255, 255, 255, 0.035);
  background-size: 32px 32px;
}

.asset-preview img,
.asset-preview video {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.asset-placeholder {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-muted);
}

.asset-kind {
  position: absolute;
  left: 12px;
  bottom: 12px;
  padding: 5px 10px;
  border-radius: 999px;
  border: 1px solid rgba(255, 255, 255, 0.14);
  background: rgba(5, 9, 13, 0.56);
  color: #f7fbff;
  font-size: 14px;
  backdrop-filter: blur(24px);
}

.asset-info {
  padding: 13px 14px 14px;
}

.asset-name {
  margin-bottom: 10px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-primary);
  font-size: 16px;
  font-weight: 850;
}

.asset-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: var(--text-muted);
  font-size: 14px;
}

.asset-inspector {
  padding: 22px;
}

.inspector-kicker {
  color: var(--primary);
  font-size: 14px;
  font-weight: 850;
}

.asset-inspector h2 {
  margin: 12px 0 20px;
  font-size: 24px;
  line-height: 1.2;
}

.asset-inspector p {
  color: var(--text-secondary);
  line-height: 1.6;
}

.asset-inspector dl {
  display: grid;
  gap: 14px;
  margin: 0;
}

.asset-inspector dl div {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--border);
}

.asset-inspector dt {
  color: var(--text-muted);
}

.asset-inspector dd {
  margin: 0;
  color: var(--text-primary);
  text-align: right;
  font-weight: 800;
}

.asset-empty {
  min-height: 320px;
  display: grid;
  place-items: center;
  text-align: center;
  border: 1px dashed var(--border-strong);
  border-radius: 18px;
  color: var(--text-secondary);
}

.asset-empty h2 {
  margin: 0 0 10px;
  color: var(--text-primary);
  font-size: 26px;
}

.asset-empty p {
  margin: 0;
  font-size: 16px;
}

@media (max-width: 1120px) {
  .asset-shell {
    grid-template-columns: 180px 1fr;
  }

  .asset-inspector {
    grid-column: 1 / -1;
  }
}

@media (max-width: 760px) {
  .asset-browser {
    padding: 20px;
  }

  .asset-header,
  .asset-shell {
    display: flex;
    flex-direction: column;
    align-items: stretch;
  }

  .asset-sidebar {
    flex-direction: row;
    overflow-x: auto;
  }

  .asset-sidebar button {
    min-width: 108px;
  }
}
</style>
