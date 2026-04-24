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
          <el-button type="primary" :loading="imageLoading" @click="openDialog('images')"
                     :disabled="imageEligibleShots.length === 0">
            {{ overview.imagesReady === overview.totalShots && overview.totalShots > 0 ? '重新生成' : '开始生成' }}
          </el-button>
        </div>

        <div class="action-step">
          <div class="step-num">2</div>
          <div class="step-info">
            <div class="step-name">生成动态镜头</div>
            <div class="step-desc">按镜头提示词调用视频接口生成动态片段</div>
            <div class="step-tip">{{ overview.dynamicReady }} / {{ overview.dynamicSelected }} 已生成</div>
          </div>
          <el-button type="primary" :loading="dynamicLoading" @click="openDialog('dynamic')"
                     :disabled="dynamicEligibleShots.length === 0">
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
          <el-button type="primary" :loading="audioLoading" @click="openDialog('audio')"
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
          <el-button type="success" :loading="composeLoading" @click="openDialog('compose')"
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
      <div v-if="taskTrace" class="task-trace-panel">
        <div class="task-trace-header">
          <div>
            <div class="task-trace-title">外部接口调用明细</div>
            <div class="task-trace-summary">
              已记录 {{ taskTrace.storedCalls || 0 }} 次调用
              <span v-if="taskTrace.omittedCalls">，另有 {{ taskTrace.omittedCalls }} 次未展示</span>
            </div>
          </div>
        </div>
        <div v-if="taskTrace.calls?.length" class="task-trace-list">
          <div v-for="(call, index) in taskTrace.calls" :key="`${index}-${call.url}`" class="task-trace-item">
            <div class="task-trace-item-head">
              <span class="task-trace-index">#{{ Number(index) + 1 }}</span>
              <span v-if="call.shotNo" class="task-trace-shot">镜头 {{ call.shotNo }}</span>
              <span class="task-trace-method">{{ call.method }}</span>
              <span class="task-trace-url">{{ call.url }}</span>
              <span :class="['task-trace-status', call.success ? 'is-success' : 'is-failed']">
                {{ call.statusCode || '-' }}
              </span>
            </div>
            <div class="task-trace-meta">
              <span>动作：{{ call.action || '-' }}</span>
              <span v-if="call.provider">服务商：{{ call.provider }}</span>
              <span v-if="call.responseBytes">响应大小：{{ call.responseBytes }} bytes</span>
              <span v-if="call.outputUrl">输出：{{ call.outputUrl }}</span>
            </div>
            <div v-if="call.requestHeaders" class="task-trace-block">
              <div class="trace-label">请求头</div>
              <pre>{{ formatTraceBody(call.requestHeaders) }}</pre>
            </div>
            <div v-if="call.requestBody" class="task-trace-block">
              <div class="trace-label">请求参数</div>
              <pre>{{ formatTraceBody(call.requestBody) }}</pre>
            </div>
            <div v-if="call.responseBody" class="task-trace-block">
              <div class="trace-label">响应内容</div>
              <pre>{{ formatTraceBody(call.responseBody) }}</pre>
            </div>
            <div v-if="call.error" class="task-trace-error">{{ call.error }}</div>
          </div>
        </div>
        <div v-if="asyncTaskDetails.length" class="task-async-panel">
          <div class="task-trace-title">阿里云任务明细</div>
          <div class="task-async-list">
            <div v-for="item in asyncTaskDetails" :key="`${item.shotId}-${item.taskId || item.statusUrl}`" class="task-async-item">
              <div class="task-async-head">
                <span class="task-trace-shot">镜头 {{ item.shotNo || '-' }}</span>
                <span v-if="item.provider" class="task-trace-method">{{ item.provider }}</span>
                <span :class="['task-trace-status', isTaskItemSuccess(item) ? 'is-success' : isTaskItemFailed(item) ? 'is-failed' : '']">
                  {{ item.taskStatus || item.renderStatus || '-' }}
                </span>
              </div>
              <div class="task-async-field">
                <span class="trace-label">taskId</span>
                <span class="task-async-value">{{ item.taskId || '-' }}</span>
              </div>
              <div class="task-async-field">
                <span class="trace-label">statusUrl</span>
                <span class="task-async-value">{{ item.statusUrl || '-' }}</span>
              </div>
              <div v-if="item.videoUrl" class="task-async-field">
                <span class="trace-label">videoUrl</span>
                <span class="task-async-value">{{ item.videoUrl }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>
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
        <el-button type="primary" size="large" @click="handleDownload" :loading="downloadLoading">
          <el-icon><Download /></el-icon> 下载成片
        </el-button>
        <div class="download-hint">
          <el-icon><InfoFilled /></el-icon> 下载完成后成片将自动从服务器删除以节约存储空间
        </div>
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

    
    <!-- Shot Selection Dialog -->
    <el-dialog
      v-model="showSelectDialog"
      :title="dialogTitle"
      width="800px"
      destroy-on-close
      :close-on-click-modal="false"
      @closed="resetDialogSelection"
    >
      <div v-if="dialogType === 'compose'">
        <el-table
          ref="composeTableRef"
          :key="dialogType"
          :data="dialogShots"
          @selection-change="handleComposeSelectionChange"
          height="500px"
          empty-text="当前没有可选分镜"
        >
          <el-table-column type="selection" width="55" :selectable="selectableMethod" />
          <el-table-column prop="shotNo" label="镜头号" width="80" />
          <el-table-column prop="description" label="画面描述" min-width="200" show-overflow-tooltip />
          <el-table-column label="状态 (图/音/动)" width="150" align="center">
            <template #default="{ row }">
              <span v-if="row.imageUrl" title="图片就绪">🖼️ </span>
              <span v-if="row.audioUrl" title="配音就绪">🎵 </span>
              <span v-if="row.videoUrl" title="动态就绪">🎬 </span>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <div v-else class="selection-groups">
        <div class="selection-tip">已生成内容默认折叠，展开后才可重新勾选；全选只作用于当前展开分组。</div>
        <el-collapse v-model="expandedDialogGroups" @change="handleDialogGroupChange">
          <el-collapse-item name="pending">
            <template #title>
              <div class="selection-group-title">
                <span>{{ pendingGroupTitle }}</span>
                <span class="selection-group-count">{{ pendingDialogShots.length }}</span>
              </div>
            </template>
            <el-table
              ref="pendingTableRef"
              :key="`${dialogType}-pending`"
              :data="pendingDialogShots"
              @selection-change="handlePendingSelectionChange"
              height="240px"
              empty-text="当前没有待生成分镜"
            >
              <el-table-column type="selection" width="55" :selectable="selectableMethod" />
              <el-table-column prop="shotNo" label="镜头号" width="80" />
              <el-table-column prop="description" label="画面描述" min-width="200" show-overflow-tooltip />
              <el-table-column label="状态 (图/音/动)" width="150" align="center">
                <template #default="{ row }">
                  <span v-if="row.imageUrl" title="图片就绪">🖼️ </span>
                  <span v-if="row.audioUrl" title="配音就绪">🎵 </span>
                  <span v-if="row.videoUrl" title="动态就绪">🎬 </span>
                </template>
              </el-table-column>
            </el-table>
          </el-collapse-item>

          <el-collapse-item name="generated" :disabled="generatedDialogShots.length === 0">
            <template #title>
              <div class="selection-group-title">
                <span>{{ generatedGroupTitle }}</span>
                <span class="selection-group-count">{{ generatedDialogShots.length }}</span>
              </div>
            </template>
            <div class="selection-group-hint">展开后可重新勾选，并在任务成功后覆盖当前已保存结果。</div>
            <el-table
              ref="generatedTableRef"
              :key="`${dialogType}-generated`"
              :data="generatedDialogShots"
              @selection-change="handleGeneratedSelectionChange"
              height="240px"
              empty-text="当前没有已生成分镜"
            >
              <el-table-column type="selection" width="55" :selectable="selectableMethod" />
              <el-table-column prop="shotNo" label="镜头号" width="80" />
              <el-table-column prop="description" label="画面描述" min-width="200" show-overflow-tooltip />
              <el-table-column label="状态 (图/音/动)" width="150" align="center">
                <template #default="{ row }">
                  <span v-if="row.imageUrl" title="图片就绪">🖼️ </span>
                  <span v-if="row.audioUrl" title="配音就绪">🎵 </span>
                  <span v-if="row.videoUrl" title="动态就绪">🎬 </span>
                </template>
              </el-table-column>
            </el-table>
          </el-collapse-item>
        </el-collapse>
      </div>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="showSelectDialog = false">取消</el-button>
          <el-button type="primary" @click="confirmGenerate" :loading="submitLoading" :disabled="selectedShotIds.length === 0">
            确定 (已选 {{ selectedShotIds.length }})
          </el-button>
        </div>
      </template>
    </el-dialog>

</template>

<script setup lang="ts">
import { ref, computed, nextTick, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Download, InfoFilled } from '@element-plus/icons-vue'
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
const downloadLoading = ref(false)

type DialogType = 'images' | 'dynamic' | 'audio' | 'compose'
type DialogGroup = 'pending' | 'generated'

const showSelectDialog = ref(false)
const dialogType = ref<DialogType>('images')
const expandedDialogGroups = ref<DialogGroup[]>(['pending'])
const pendingSelectedIds = ref<Array<number | string>>([])
const generatedSelectedIds = ref<Array<number | string>>([])
const composeSelectedIds = ref<Array<number | string>>([])
const pendingTableRef = ref<any>(null)
const generatedTableRef = ref<any>(null)
const composeTableRef = ref<any>(null)
const submitLoading = ref(false)

const selectedShotIds = computed(() => {
  if (dialogType.value === 'compose') {
    return composeSelectedIds.value
  }
  return Array.from(new Set([...pendingSelectedIds.value, ...generatedSelectedIds.value]))
})

const taskTrace = computed(() => parseTaskTrace(currentTask.value?.result))
const asyncTaskDetails = computed(() => {
  const items = taskTrace.value?.summary?.asyncTasks
  return Array.isArray(items) ? items : []
})

const dialogTitle = computed(() => {
  switch(dialogType.value) {
    case 'images': return '选择需要生成图片的分镜'
    case 'dynamic': return '选择需要生成动态的分镜'
    case 'audio': return '选择需要生成配音的分镜'
    case 'compose': return '选择参与合成的分镜'
    default: return '选择分镜'
  }
})

const pendingGroupTitle = computed(() => {
  switch (dialogType.value) {
    case 'images': return '待生成图片'
    case 'dynamic': return '待生成动态视频'
    case 'audio': return '待生成配音'
    default: return '待处理分镜'
  }
})

const generatedGroupTitle = computed(() => {
  switch (dialogType.value) {
    case 'images': return '已生成图片'
    case 'dynamic': return '已生成动态视频'
    case 'audio': return '已生成配音'
    default: return '已生成内容'
  }
})

const isImageEligibleShot = (shot: any) => !shot?.dynamicSelected

const isDynamicEligibleShot = (shot: any) => !!shot?.dynamicSelected

const isGeneratedShotForDialog = (shot: any) => {
  if (dialogType.value === 'images') return !!shot?.imageUrl
  if (dialogType.value === 'dynamic') return !!shot?.videoUrl
  if (dialogType.value === 'audio') return !!shot?.audioUrl
  return false
}

const imageEligibleShots = computed(() => shots.value.filter((shot) => isImageEligibleShot(shot)))

const dynamicEligibleShots = computed(() => shots.value.filter((shot) => isDynamicEligibleShot(shot)))

const dialogShots = computed(() => {
  if (dialogType.value === 'images') {
    return imageEligibleShots.value
  }
  if (dialogType.value === 'dynamic') {
    return dynamicEligibleShots.value.filter((shot) => !!shot.videoPrompt || !!shot.description)
  }
  if (dialogType.value === 'compose') {
    return shots.value.filter((shot) => !!shot.imageUrl || !!shot.videoUrl)
  }
  return shots.value
})

const pendingDialogShots = computed(() => dialogShots.value.filter((shot) => !isGeneratedShotForDialog(shot)))

const generatedDialogShots = computed(() => dialogShots.value.filter((shot) => isGeneratedShotForDialog(shot)))

const selectableMethod = (row: any) => {
  if (dialogType.value === 'images') return isImageEligibleShot(row)
  if (dialogType.value === 'dynamic') return isDynamicEligibleShot(row) && (!!row.videoPrompt || !!row.description)
  if (dialogType.value === 'compose') return !!row.imageUrl || !!row.videoUrl
  return true
}

const handlePendingSelectionChange = (rows: any[]) => {
  pendingSelectedIds.value = rows.map((row) => row.id)
}

const handleGeneratedSelectionChange = (rows: any[]) => {
  generatedSelectedIds.value = rows.map((row) => row.id)
}

const handleComposeSelectionChange = (rows: any[]) => {
  composeSelectedIds.value = rows.map((row) => row.id)
}

function handleDialogGroupChange(activeNames: string | string[]) {
  const groups = Array.isArray(activeNames) ? activeNames : activeNames ? [activeNames] : []
  expandedDialogGroups.value = groups as DialogGroup[]

  if (!groups.includes('pending')) {
    pendingSelectedIds.value = []
    nextTick(() => pendingTableRef.value?.clearSelection?.())
  }
  if (!groups.includes('generated')) {
    generatedSelectedIds.value = []
    nextTick(() => generatedTableRef.value?.clearSelection?.())
  }
}

const resetDialogSelection = () => {
  expandedDialogGroups.value = pendingDialogShots.value.length > 0 ? ['pending'] : []
  pendingSelectedIds.value = []
  generatedSelectedIds.value = []
  composeSelectedIds.value = []
  pendingTableRef.value?.clearSelection?.()
  generatedTableRef.value?.clearSelection?.()
  composeTableRef.value?.clearSelection?.()
}

const openDialog = (type: DialogType) => {
  dialogType.value = type
  resetDialogSelection()
  showSelectDialog.value = true
}

const confirmGenerate = async () => {
  if (selectedShotIds.value.length === 0) {
    ElMessage.warning('请至少选择一个分镜')
    return
  }

  const shotIds = [...selectedShotIds.value]
  showSelectDialog.value = false
  submitLoading.value = true
  
  try {
    let res
    if (dialogType.value === 'images') {
      imageLoading.value = true
      res = await videoApi.generateImages(projectId, shotIds)
      ElMessage.success('分镜图片生成任务已提交')
    } else if (dialogType.value === 'dynamic') {
      dynamicLoading.value = true
      res = await videoApi.generateDynamic(projectId, shotIds)
      ElMessage.success('动态镜头生成任务已提交')
    } else if (dialogType.value === 'audio') {
      audioLoading.value = true
      res = await videoApi.generateAudio(projectId, shotIds)
      ElMessage.success('分镜配音生成任务已提交')
    } else if (dialogType.value === 'compose') {
      composeLoading.value = true
      res = await videoApi.compose(projectId, shotIds)
      ElMessage.success('视频合成任务已提交')
    }
    if (res && res.data && res.data.data) {
      currentTask.value = res.data.data
      startPolling(currentTask.value.id)
      setTimeout(loadOverview, 2000)
    }
  } catch(e: any) {
     ElMessage.error(e.response?.data?.message || '提交失败')
  } finally {
     submitLoading.value = false
     imageLoading.value = false
     dynamicLoading.value = false
     audioLoading.value = false
     composeLoading.value = false
  }
}

const TASK_POLL_INTERVAL_MS = 5000
const TASK_POLL_EXPIRE_MS = 60 * 60 * 1000

let pollTimer: ReturnType<typeof setTimeout> | null = null
let pollStartedAt = 0
let pollInFlight = false

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





function handleDownload() {
  downloadLoading.value = true
  const url = videoApi.getDownloadUrl(projectId)
  // Create a hidden anchor element to trigger the download
  const a = document.createElement('a')
  a.href = url
  a.download = `niren_drama_${projectId}.mp4`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)

  ElMessage.success('下载已开始，成片导出完成后将自动从服务器删除')

  // Poll task status to detect when the server has cleared the result (file deleted)
  let pollCount = 0
  const maxPolls = 60 // up to 5 minutes (60 × 5s)
  const pollId = setInterval(async () => {
    try {
      pollCount++
      const res = await videoApi.getStatus(projectId)
      const task = res.data.data
      // Server clears task.result after deletion
      if (!task || task.result === null || task.result === '' || task.result === undefined) {
        clearInterval(pollId)
        downloadLoading.value = false
        await loadOverview()
      } else if (pollCount >= maxPolls) {
        clearInterval(pollId)
        downloadLoading.value = false
        await loadOverview()
      }
    } catch {
      clearInterval(pollId)
      downloadLoading.value = false
    }
  }, 5000)
}

function startPolling(taskId: string | number) {
  stopPolling()
  pollStartedAt = Date.now()
  void startPollingCycle(taskId)
}

function stopPolling() {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
  pollInFlight = false
  pollStartedAt = 0
}

function scheduleNextPoll(taskId: string | number) {
  if (pollTimer) {
    clearTimeout(pollTimer)
  }
  pollTimer = setTimeout(() => {
    void startPollingCycle(taskId)
  }, TASK_POLL_INTERVAL_MS)
}

async function startPollingCycle(taskId: string | number) {
  if (pollInFlight) {
    scheduleNextPoll(taskId)
    return
  }
  if (Date.now() - pollStartedAt >= TASK_POLL_EXPIRE_MS) {
    stopPolling()
    ElMessage.error('任务轮询已超过 1 小时，请稍后刷新页面查看最新状态')
    return
  }

  pollInFlight = true
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
    } else {
      scheduleNextPoll(taskId)
    }
  } catch (e: any) {
    console.error('Poll error:', e)
    scheduleNextPoll(taskId)
  } finally {
    pollInFlight = false
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
  video_submitted: '动态任务已提交',
  video_polling: '动态生成中',
  video_generated: '动态片段就绪',
  audio_generated: '配音就绪',
  image_failed: '图片失败',
  video_failed: '动态失败',
  audio_failed: '配音失败',
  completed: '已完成',
}[s] || s)

function truncate(str: string, len: number) {
  if (!str) return ''
  return str.length > len ? str.substring(0, len) + '...' : str
}

function parseTaskTrace(raw: any) {
  if (!raw || typeof raw !== 'string') return null
  try {
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed?.calls) ? parsed : null
  } catch {
    return null
  }
}

function formatTraceBody(value: any) {
  if (!value) return ''
  if (typeof value === 'string') {
    try {
      return JSON.stringify(JSON.parse(value), null, 2)
    } catch {
      return value
    }
  }
  return JSON.stringify(value, null, 2)
}

function normalizeTaskState(value: any) {
  return typeof value === 'string' ? value.trim().toLowerCase() : ''
}

function isTaskItemSuccess(item: any) {
  const state = normalizeTaskState(item?.taskStatus || item?.renderStatus)
  return ['success', 'succeeded', 'completed', 'done', 'finished', 'video_generated'].includes(state)
}

function isTaskItemFailed(item: any) {
  const state = normalizeTaskState(item?.taskStatus || item?.renderStatus)
  return ['failed', 'error', 'cancelled', 'canceled', 'rejected', 'video_failed'].includes(state)
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
.task-trace-panel {
  margin-top: 16px;
  border-top: 1px solid #e5e7eb;
  padding-top: 16px;
}
.task-trace-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.task-trace-title {
  font-size: 14px;
  font-weight: 700;
  color: var(--text-primary);
}
.task-trace-summary {
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-muted);
}
.task-trace-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.task-trace-item {
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  padding: 12px;
  background: #fafafa;
}
.task-trace-item-head {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}
.task-trace-index,
.task-trace-shot,
.task-trace-method,
.task-trace-status {
  font-size: 12px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 999px;
}
.task-trace-index { background: #e5e7eb; color: #374151; }
.task-trace-shot { background: #dbeafe; color: #1d4ed8; }
.task-trace-method { background: #ede9fe; color: #6d28d9; }
.task-trace-url {
  flex: 1;
  min-width: 280px;
  font-size: 12px;
  color: #111827;
  word-break: break-all;
}
.task-trace-status.is-success { background: #d1fae5; color: #065f46; }
.task-trace-status.is-failed { background: #fee2e2; color: #991b1b; }
.task-trace-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 10px;
  font-size: 12px;
  color: var(--text-muted);
}
.task-trace-block {
  margin-top: 10px;
}
.trace-label {
  margin-bottom: 6px;
  font-size: 12px;
  font-weight: 700;
  color: var(--text-secondary);
}
.task-trace-block pre {
  margin: 0;
  padding: 10px;
  border-radius: 8px;
  background: #111827;
  color: #e5e7eb;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}
.task-trace-error {
  margin-top: 10px;
  padding: 10px;
  border-radius: 8px;
  background: #fef2f2;
  color: #b91c1c;
  font-size: 12px;
  white-space: pre-wrap;
}
.task-async-panel {
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px dashed #d1d5db;
}
.task-async-list {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 12px;
  margin-top: 12px;
}
.task-async-item {
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  padding: 12px;
  background: #fff;
}
.task-async-head {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 10px;
}
.task-async-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 8px;
}
.task-async-value {
  font-size: 12px;
  color: #111827;
  line-height: 1.5;
  word-break: break-all;
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
  flex-direction: column;
  align-items: center;
  gap: 10px;
}
.download-hint {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: var(--text-muted);
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
.shot-status.status-image_failed { background: #fee2e2; color: #991b1b; }
.shot-status.status-video_failed { background: #fee2e2; color: #991b1b; }
.shot-status.status-audio_failed { background: #fee2e2; color: #991b1b; }
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

.selection-groups {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.selection-tip {
  font-size: 12px;
  color: var(--text-muted);
  line-height: 1.5;
}

.selection-group-title {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
}

.selection-group-count {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 22px;
  height: 22px;
  padding: 0 8px;
  border-radius: 999px;
  background: var(--bg-muted);
  color: var(--text-secondary);
  font-size: 12px;
}

.selection-group-hint {
  margin-bottom: 10px;
  font-size: 12px;
  color: var(--text-muted);
}

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
    