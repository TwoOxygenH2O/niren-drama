<template>
  <div class="page-container">
    <!-- Back nav -->
    <div class="page-nav">
      <el-button text @click="$router.push(`/projects/${projectId}`)">
        <el-icon><ArrowLeft /></el-icon> 返回项目
      </el-button>
      <span class="page-title">🚀 合成导出</span>
    </div>

    <!-- Overview cards -->
    <div class="overview-row">
      <div class="ov-card">
        <div class="ov-icon" style="background:rgba(99,102,241,0.1)">🎬</div>
        <div class="ov-body">
          <div class="ov-value">{{ overview.totalShots }}</div>
          <div class="ov-label">总分镜数</div>
        </div>
      </div>
      <div class="ov-card">
        <div class="ov-icon" style="background:rgba(129,140,248,0.12)">✨</div>
        <div class="ov-body">
          <div class="ov-value">{{ overview.dynamicRecommended }}</div>
          <div class="ov-label">推荐动态镜头</div>
        </div>
      </div>
      <div class="ov-card">
        <div class="ov-icon" style="background:rgba(59,130,246,0.12)">🎞️</div>
        <div class="ov-body">
          <div class="ov-value">{{ overview.dynamicReady }} / {{ overview.dynamicSelected }}</div>
          <div class="ov-label">已选动态镜头</div>
        </div>
      </div>
      <div class="ov-card">
        <div class="ov-icon" style="background:rgba(16,185,129,0.1)">🖼️</div>
        <div class="ov-body">
          <div class="ov-value">{{ overview.imagesReady }} / {{ overview.totalShots }}</div>
          <div class="ov-label">图片已就绪</div>
        </div>
      </div>
      <div class="ov-card">
        <div class="ov-icon" style="background:rgba(245,158,11,0.1)">🎙️</div>
        <div class="ov-body">
          <div class="ov-value">{{ overview.audioReady }} / {{ overview.totalShots }}</div>
          <div class="ov-label">配音已就绪</div>
        </div>
      </div>
      <div class="ov-card">
        <div class="ov-icon" :style="{ background: overview.videoUrl ? 'rgba(16,185,129,0.1)' : 'rgba(156,163,175,0.1)' }">
          {{ overview.videoUrl ? '✅' : '⏳' }}
        </div>
        <div class="ov-body">
          <div class="ov-value">{{ overview.videoUrl ? '已完成' : '待合成' }}</div>
          <div class="ov-label">成片视频</div>
        </div>
      </div>
    </div>

    <!-- Action buttons -->
    <div class="action-section">
      <div class="action-title">生成流程</div>
      <div class="action-steps">
        <div class="action-step">
          <div class="step-num">1</div>
          <div class="step-info">
            <div class="step-name">生成分镜图片</div>
            <div class="step-desc">AI 为每个分镜生成关键帧画面</div>
            <div class="step-tip">{{ overview.imagesReady }} / {{ overview.totalShots }} 已就绪</div>
          </div>
          <el-button type="primary" :loading="imageLoading" @click="handleGenerateImages"
                     :disabled="overview.totalShots === 0">
            {{ overview.imagesReady === overview.totalShots && overview.totalShots > 0 ? '重新生成' : '开始生成' }}
          </el-button>
        </div>

        <div class="action-step">
          <div class="step-num">2</div>
          <div class="step-info">
            <div class="step-name">生成动态镜头</div>
            <div class="step-desc">只为你勾选的镜头生成动态片段</div>
            <div class="step-tip">{{ overview.dynamicReady }} / {{ overview.dynamicSelected }} 已生成</div>
          </div>
          <el-button type="primary" :loading="dynamicLoading" @click="handleGenerateDynamic"
                     :disabled="overview.dynamicSelected === 0 || overview.imagesReady === 0">
            {{ overview.dynamicReady === overview.dynamicSelected && overview.dynamicSelected > 0 ? '重新生成' : '开始生成' }}
          </el-button>
        </div>

        <div class="action-step">
          <div class="step-num">3</div>
          <div class="step-info">
            <div class="step-name">生成配音</div>
            <div class="step-desc">TTS 为台词和旁白配音</div>
            <div class="step-tip">{{ overview.audioReady }} / {{ overview.totalShots }} 已就绪</div>
          </div>
          <el-button type="primary" :loading="audioLoading" @click="handleGenerateAudio"
                     :disabled="overview.totalShots === 0">
            {{ overview.audioReady === overview.totalShots && overview.totalShots > 0 ? '重新生成' : '开始生成' }}
          </el-button>
        </div>

        <div class="action-step">
          <div class="step-num">4</div>
          <div class="step-info">
            <div class="step-name">合成视频</div>
            <div class="step-desc">优先使用动态片段，没有则回退到静态图片</div>
            <div class="step-tip">支持动态与静态镜头混合输出</div>
          </div>
          <el-button type="success" :loading="composeLoading" @click="handleCompose"
                     :disabled="overview.imagesReady === 0">
            {{ overview.videoUrl ? '重新合成' : '开始合成' }}
          </el-button>
        </div>
      </div>
    </div>

    <!-- Current task progress -->
    <div v-if="currentTask" class="task-progress-card">
      <div class="task-header">
        <span class="task-type">{{ taskTypeLabel(currentTask.taskType) }}</span>
        <span :class="`task-status status-${currentTask.status?.toLowerCase()}`">
          {{ currentTask.status }}
        </span>
      </div>
      <el-progress
        :percentage="currentTask.progress || 0"
        :status="currentTask.status === 'SUCCESS' ? 'success' : currentTask.status === 'FAILED' ? 'exception' : undefined"
        :stroke-width="10"
      />
      <div class="task-message">{{ currentTask.message }}</div>
    </div>

    <!-- Video preview -->
    <div v-if="overview.videoUrl" class="video-section">
      <div class="section-title">🎥 成片预览</div>
      <div class="video-wrapper">
        <video :src="overview.videoUrl" controls class="video-player">
          您的浏览器不支持视频播放
        </video>
      </div>
      <div class="video-actions">
        <el-button type="primary" size="large" @click="handleDownload">
          <el-icon><Download /></el-icon> 下载成片
        </el-button>
      </div>
    </div>

    <!-- Storyboard shots preview -->
    <div class="shots-section">
      <div class="section-title">📋 分镜预览 ({{ shots.length }} 个镜头)</div>
      <div v-if="shots.length === 0" class="empty-hint">
        暂无分镜数据，请先在「分镜制作」页面生成分镜脚本。
      </div>
      <div v-else class="shots-grid">
        <div v-for="shot in shots" :key="shot.id" class="shot-card">
          <div class="shot-header">
            <span class="shot-no">#{{ shot.shotNo }}</span>
            <span :class="`shot-status status-${shot.status}`">{{ shotStatusLabel(shot.status) }}</span>
          </div>
          <div class="shot-image">
            <img v-if="shot.imageUrl" :src="shot.imageUrl" :alt="`镜头${shot.shotNo}`" />
            <div v-else class="no-image">🖼️ 待生成</div>
          </div>
          <div class="shot-detail">
            <div class="shot-desc" :title="shot.description">{{ truncate(shot.description, 60) }}</div>
            <div class="shot-meta">
              <span v-if="shot.duration">⏱️ {{ shot.duration }}s</span>
              <span v-if="shot.dynamicSelected" class="dynamic-selected">
                {{ shot.videoUrl ? '🎞️ 动态已就绪' : '🎞️ 已选动态' }}
              </span>
              <span v-if="shot.audioUrl" class="audio-ready">🔊 有配音</span>
              <span v-else class="audio-pending">🔇 无配音</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Download } from '@element-plus/icons-vue'
import { videoApi } from '@/api/video'
import { taskApi } from '@/api/task'

const route = useRoute()
const projectId = route.params.id as string

const overview = ref<any>({
  totalShots: 0,
  dynamicRecommended: 0,
  dynamicSelected: 0,
  dynamicReady: 0,
  imagesReady: 0,
  audioReady: 0,
  videoUrl: '',
})
const shots = ref<any[]>([])
const currentTask = ref<any>(null)
const imageLoading = ref(false)
const dynamicLoading = ref(false)
const audioLoading = ref(false)
const composeLoading = ref(false)

let pollTimer: ReturnType<typeof setInterval> | null = null

async function loadOverview() {
  try {
    const res = await videoApi.getOverview(projectId)
    overview.value = res.data.data || overview.value
  } catch (e: any) {
    console.error('Failed to load overview:', e)
  }
}

async function loadShots() {
  try {
    const res = await videoApi.getStoryboards(projectId)
    shots.value = res.data.data || []
  } catch (e: any) {
    console.error('Failed to load shots:', e)
  }
}

async function handleGenerateImages() {
  imageLoading.value = true
  try {
    const res = await videoApi.generateImages(projectId)
    currentTask.value = res.data.data
    ElMessage.success('分镜图片生成任务已提交')
    startPolling(currentTask.value.id)
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || '提交失败')
  } finally {
    imageLoading.value = false
  }
}

async function handleGenerateAudio() {
  audioLoading.value = true
  try {
    const res = await videoApi.generateAudio(projectId)
    currentTask.value = res.data.data
    ElMessage.success('配音生成任务已提交')
    startPolling(currentTask.value.id)
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || '提交失败')
  } finally {
    audioLoading.value = false
  }
}

async function handleGenerateDynamic() {
  dynamicLoading.value = true
  try {
    const res = await videoApi.generateDynamic(projectId)
    currentTask.value = res.data.data
    ElMessage.success('动态镜头生成任务已提交')
    startPolling(currentTask.value.id)
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || '提交失败')
  } finally {
    dynamicLoading.value = false
  }
}

async function handleCompose() {
  composeLoading.value = true
  try {
    if (overview.value.dynamicSelected > overview.value.dynamicReady) {
      ElMessage.warning('部分已选动态镜头尚未生成片段，未生成部分将回退为静态图片')
    }
    const res = await videoApi.compose(projectId)
    currentTask.value = res.data.data
    ElMessage.success('视频合成任务已提交')
    startPolling(currentTask.value.id)
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || '提交失败')
  } finally {
    composeLoading.value = false
  }
}

function handleDownload() {
  const url = videoApi.getDownloadUrl(projectId)
  window.open(url, '_blank')
}

function startPolling(taskId: string | number) {
  stopPolling()
  pollTimer = setInterval(async () => {
    try {
      const res = await taskApi.get(taskId)
      currentTask.value = res.data.data
      if (currentTask.value.status === 'SUCCESS') {
        stopPolling()
        ElMessage.success(currentTask.value.message || '任务完成！')
        await loadOverview()
        await loadShots()
      } else if (currentTask.value.status === 'FAILED') {
        stopPolling()
        ElMessage.error(currentTask.value.message || '任务失败')
      }
    } catch (e: any) {
      console.error('Poll error:', e)
    }
  }, 5000)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

const taskTypeLabel = (t: string) => ({
  IMAGE_GEN: '🖼️ 分镜图片生成',
  DYNAMIC_VIDEO_GEN: '🎞️ 动态镜头生成',
  AUDIO_GEN: '🎙️ 配音生成',
  VIDEO_COMPOSE: '🎬 视频合成',
}[t] || t)

const shotStatusLabel = (s: string) => ({
  draft: '待处理',
  image_generated: '图片就绪',
  video_generated: '动态片段就绪',
  audio_generated: '配音就绪',
  completed: '已完成',
}[s] || s)

function truncate(str: string, len: number) {
  if (!str) return ''
  return str.length > len ? str.substring(0, len) + '...' : str
}

onMounted(async () => {
  await Promise.all([loadOverview(), loadShots()])
  // Check for ongoing tasks
  try {
    const res = await videoApi.getStatus(projectId)
    const task = res.data.data
    if (task && (task.status === 'PENDING' || task.status === 'RUNNING')) {
      currentTask.value = task
      startPolling(task.id)
    }
  } catch (e: any) {
    console.error('Failed to check task status:', e)
  }
})

onUnmounted(() => {
  stopPolling()
})
</script>

<style scoped>
.page-container { padding: 24px; }

.page-nav {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 24px;
}
.page-title {
  font-size: 20px;
  font-weight: 700;
  color: var(--text-primary);
}

/* Overview cards */
.overview-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 16px;
  margin-bottom: 24px;
}
.ov-card {
  background: #fff;
  border-radius: 12px;
  padding: 20px;
  display: flex;
  align-items: center;
  gap: 16px;
  border: 1px solid var(--border);
  box-shadow: var(--shadow-sm);
}
.ov-icon {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  flex-shrink: 0;
}
.ov-value {
  font-size: 20px;
  font-weight: 700;
  color: var(--text-primary);
}
.ov-label {
  font-size: 12px;
  color: var(--text-muted);
  margin-top: 2px;
}

/* Action section */
.action-section {
  background: #fff;
  border-radius: 14px;
  padding: 24px;
  margin-bottom: 24px;
  border: 1px solid var(--border);
  box-shadow: var(--shadow-sm);
}
.action-title {
  font-size: 16px;
  font-weight: 700;
  color: var(--text-primary);
  margin-bottom: 20px;
}
.action-steps {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 16px;
}
.action-step {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px;
  background: var(--bg-muted);
  border-radius: 10px;
}
.step-num {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: var(--primary);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  font-weight: 700;
  flex-shrink: 0;
}
.step-info { flex: 1; }
.step-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}
.step-desc {
  font-size: 12px;
  color: var(--text-muted);
  margin-top: 2px;
}
.step-tip {
  margin-top: 6px;
  font-size: 11px;
  color: var(--text-secondary);
}

/* Task progress */
.task-progress-card {
  background: #fff;
  border-radius: 14px;
  padding: 20px 24px;
  margin-bottom: 24px;
  border: 1px solid var(--border);
  box-shadow: var(--shadow-sm);
}
.task-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.task-type {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}
.task-status {
  font-size: 12px;
  font-weight: 600;
  padding: 2px 10px;
  border-radius: 12px;
}
.task-status.status-pending { background: #fef3c7; color: #92400e; }
.task-status.status-running { background: #dbeafe; color: #1e40af; }
.task-status.status-success { background: #d1fae5; color: #065f46; }
.task-status.status-failed { background: #fee2e2; color: #991b1b; }
.task-message {
  margin-top: 8px;
  font-size: 13px;
  color: var(--text-secondary);
}

/* Video section */
.video-section {
  background: #fff;
  border-radius: 14px;
  padding: 24px;
  margin-bottom: 24px;
  border: 1px solid var(--border);
  box-shadow: var(--shadow-sm);
}
.section-title {
  font-size: 16px;
  font-weight: 700;
  color: var(--text-primary);
  margin-bottom: 16px;
}
.video-wrapper {
  display: flex;
  justify-content: center;
  background: #000;
  border-radius: 10px;
  overflow: hidden;
  margin-bottom: 16px;
}
.video-player {
  max-width: 400px;
  max-height: 711px;
  width: 100%;
}
.video-actions {
  display: flex;
  justify-content: center;
}

/* Shots section */
.shots-section {
  background: #fff;
  border-radius: 14px;
  padding: 24px;
  border: 1px solid var(--border);
  box-shadow: var(--shadow-sm);
}
.empty-hint {
  text-align: center;
  padding: 40px;
  color: var(--text-muted);
  font-size: 14px;
}
.shots-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 16px;
}
.shot-card {
  border: 1px solid var(--border);
  border-radius: 10px;
  overflow: hidden;
  transition: transform 0.18s, box-shadow 0.18s;
}
.shot-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-md);
}
.shot-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  background: var(--bg-muted);
}
.shot-no {
  font-size: 13px;
  font-weight: 700;
  color: var(--primary);
}
.shot-status {
  font-size: 11px;
  font-weight: 600;
  padding: 1px 8px;
  border-radius: 8px;
}
.shot-status.status-draft { background: #f3f4f6; color: #6b7280; }
.shot-status.status-image_generated { background: #dbeafe; color: #1e40af; }
.shot-status.status-video_generated { background: #ede9fe; color: #6d28d9; }
.shot-status.status-audio_generated { background: #fef3c7; color: #92400e; }
.shot-status.status-completed { background: #d1fae5; color: #065f46; }

.shot-image {
  aspect-ratio: 9/16;
  background: #f3f4f6;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}
.shot-image img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.no-image {
  font-size: 24px;
  color: var(--text-muted);
}
.shot-detail {
  padding: 10px 12px;
}
.shot-desc {
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.4;
  margin-bottom: 6px;
}
.shot-meta {
  flex-wrap: wrap;
  display: flex;
  gap: 8px;
  font-size: 11px;
  color: var(--text-muted);
}
.dynamic-selected { color: #7c3aed; }
.audio-ready { color: var(--color-success); }
.audio-pending { color: var(--text-muted); }

/* Status badge */
.status-badge {
  font-size: 12px;
  font-weight: 600;
  padding: 2px 10px;
  border-radius: 12px;
}
.status-draft { background: #f3f4f6; color: #6b7280; }
.status-generating { background: #dbeafe; color: #1e40af; }
.status-completed { background: #d1fae5; color: #065f46; }
.status-failed { background: #fee2e2; color: #991b1b; }
</style>
