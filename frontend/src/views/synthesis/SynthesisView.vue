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
        <div class="ov-icon" style="background:rgba(59,130,246,0.12)">🎞️</div>
        <div class="ov-body">
          <div class="ov-value">{{ videoReadyCount }} / {{ overview.totalShots }}</div>
          <div class="ov-label">分镜视频已就绪</div>
        </div>
      </div>
      <div class="ov-card">
        <div class="ov-icon" style="background:rgba(16,185,129,0.1)">🖼️</div>
        <div class="ov-body">
          <div class="ov-value">{{ overview.imagesReady }} / {{ overview.totalShots }}</div>
          <div class="ov-label">参考图片资产</div>
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

    <el-card class="mix-config-card" shadow="never">
      <template #header>
        <div class="copy-check-header">
          <span>分镜视频合成与旁白混音策略</span>
        </div>
      </template>
      <div class="mix-config-grid">
        <el-switch v-model="composeOptions.narrationEnabled" active-text="整集旁白轨" />
        <el-switch v-model="composeOptions.dialoguePriority" active-text="对白优先混音" />
        <div class="mix-volume">
          <span>旁白音量</span>
          <el-slider v-model="composeOptions.narrationVolume" :min="0.1" :max="0.5" :step="0.01" style="width: 180px" />
        </div>
      </div>
    </el-card>

    <el-card v-if="shots.length" class="copy-check-card" shadow="hover">
      <template #header>
        <div class="copy-check-header">
          <span>音画文案校对（只读，编辑请至「分镜制作」）</span>
          <el-button text type="primary" @click="$router.push(`/projects/${projectId}/storyboard`)">去分镜编辑</el-button>
        </div>
      </template>
      <el-table :data="shots" size="small" max-height="360" border>
        <el-table-column prop="shotNo" label="镜" width="56" />
        <el-table-column prop="duration" label="秒" width="64" />
        <el-table-column prop="resolvedSubtitle" label="上屏(生效)" min-width="140" show-overflow-tooltip />
        <el-table-column prop="resolvedTts" label="配音(生效)" min-width="140" show-overflow-tooltip />
        <el-table-column prop="dialogue" label="对白" min-width="100" show-overflow-tooltip />
        <el-table-column prop="narration" label="旁白" min-width="100" show-overflow-tooltip />
      </el-table>
    </el-card>

    <!-- Action buttons -->
    <div class="action-section">
      <div class="action-title">生成流程</div>
      <div class="action-steps">
        <div class="action-step action-step--asset">
          <div class="step-num">1</div>
          <div class="step-info">
            <div class="step-name">准备参考资产</div>
            <div class="step-desc">在角色和场景页维护形象图、场景图等一致性素材</div>
            <div class="step-tip">当前检测到 {{ overview.imagesReady }} 张旧分镜图，可作为参考资产兼容使用</div>
          </div>
          <el-button @click="$router.push(`/projects/${projectId}/characters`)">
            去准备资产
          </el-button>
        </div>

        <div class="action-step">
          <div class="step-num">2</div>
          <div class="step-info">
            <div class="step-name">生成分镜视频</div>
            <div class="step-desc">为每个分镜生成视频镜头，并自动带入可用参考资产</div>
            <div class="step-tip">{{ videoReadyCount }} / {{ overview.totalShots }} 已生成</div>
          </div>
          <el-button type="primary" :loading="dynamicLoading" @click="openDialog('dynamic')"
                     :disabled="dynamicEligibleShots.length === 0">
            {{ videoReadyCount === overview.totalShots && overview.totalShots > 0 ? '重新生成' : '开始生成' }}
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
            <div class="step-name">合成成片</div>
            <div class="step-desc">使用已生成的分镜视频、配音和字幕合成最终短剧</div>
            <div class="step-tip">{{ videoReadyCount > 0 ? `${videoReadyCount} / ${overview.totalShots} 已生成，可部分合成` : `还缺少 ${missingVideoCount} 个分镜视频` }}</div>
          </div>
          <el-button type="success" :loading="composeLoading" @click="openDialog('compose')"
                     :disabled="videoReadyCount === 0 || overview.totalShots === 0">
            {{ overview.videoUrl ? '重新合成' : '开始合成' }}
          </el-button>
        </div>
      </div>
    </div>

    <!-- Active tasks progress -->
    <div v-for="(task, taskIdx) in activeTasks" :key="task.id" class="task-progress-card" :class="{ 'is-current': task === currentTask }">
      <div class="task-header">
        <span class="task-type">{{ taskTypeLabel(task.taskType) }}</span>
        <span :class="`task-status status-${task.status?.toLowerCase()}`">{{ task.status }}</span>
      </div>
      <el-progress
        :percentage="task.progress || 0"
        :status="task.status === 'SUCCESS' ? 'success' : task.status === 'FAILED' ? 'exception' : undefined"
        :stroke-width="10"
      />
      <div class="task-message">{{ task.message }}</div>
      <template v-if="task === currentTask">
        <div v-if="hasTaskDiagnostics" class="task-diagnostics">
          <div v-if="task.totalElapsedMs != null">总耗时：{{ formatMs(task.totalElapsedMs) }}</div>
          <div v-if="task.externalApiCallCount != null">
            外部调用：{{ task.externalApiCallCount }} 次
            <span v-if="task.externalApiErrorRatio != null">，错误占比 {{ formatRatio(task.externalApiErrorRatio) }}</span>
          </div>
          <div v-if="failureDistributionText">失败类型分布：{{ failureDistributionText }}</div>
          <div v-if="stepDurationText">步骤耗时：{{ stepDurationText }}</div>
          <div v-if="composeSummaryText">合成摘要：{{ composeSummaryText }}</div>
        </div>
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
                <span :class="['task-trace-status', call.success ? 'is-success' : 'is-failed']">{{ call.statusCode || '-' }}</span>
              </div>
              <div class="task-trace-meta">
                <span>动作：{{ call.action || '-' }}</span>
                <span v-if="call.provider">服务商：{{ call.provider }}</span>
                <span v-if="call.responseBytes">响应大小：{{ call.responseBytes }} bytes</span>
                <span v-if="call.outputUrl">输出：{{ call.outputUrl }}</span>
              </div>
              <div v-if="call.requestHeaders" class="task-trace-block">
                <div class="trace-label">请求头</div><pre>{{ formatTraceBody(call.requestHeaders) }}</pre>
              </div>
              <div v-if="call.requestBody" class="task-trace-block">
                <div class="trace-label">请求参数</div><pre>{{ formatTraceBody(call.requestBody) }}</pre>
              </div>
              <div v-if="call.responseBody" class="task-trace-block">
                <div class="trace-label">响应内容</div><pre>{{ formatTraceBody(call.responseBody) }}</pre>
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
                <div class="task-async-field"><span class="trace-label">taskId</span><span class="task-async-value">{{ item.taskId || '-' }}</span></div>
                <div class="task-async-field"><span class="trace-label">statusUrl</span><span class="task-async-value">{{ item.statusUrl || '-' }}</span></div>
                <div v-if="item.videoUrl" class="task-async-field"><span class="trace-label">videoUrl</span><span class="task-async-value">{{ item.videoUrl }}</span></div>
              </div>
            </div>
          </div>
        </div>
      </template>
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
          <div class="shot-media">
            <video v-if="shot.videoUrl" :src="shot.videoUrl" controls preload="metadata" />
            <img v-else-if="shot.imageUrl" :src="shot.imageUrl" :alt="`镜头${shot.shotNo}参考图`" class="legacy-reference-image" />
            <div v-else class="no-video">🎞️ 待生成分镜视频</div>
          </div>
          <div class="shot-detail">
            <div class="shot-desc" :title="shot.description">{{ truncate(shot.description, 60) }}</div>
            <div class="shot-meta">
              <span v-if="shot.duration">⏱️ {{ shot.duration }}s</span>
              <span :class="shot.videoUrl ? 'video-ready' : 'video-pending'">
                {{ shot.videoUrl ? '🎞️ 分镜视频已就绪' : '🎞️ 待生成视频' }}
              </span>
              <span v-if="shot.imageUrl" class="reference-ready">🖼️ 有参考图</span>
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
          <el-table-column label="状态 (视频/音频)" width="150" align="center">
            <template #default="{ row }">
              <span v-if="row.videoUrl" title="分镜视频就绪">🎬 </span>
              <span v-if="row.audioUrl" title="配音就绪">🎵 </span>
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
              <el-table-column label="状态 (视频/音频)" width="150" align="center">
                <template #default="{ row }">
                  <span v-if="row.videoUrl" title="分镜视频就绪">🎬 </span>
                  <span v-if="row.audioUrl" title="配音就绪">🎵 </span>
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
              <el-table-column label="状态 (视频/音频)" width="150" align="center">
                <template #default="{ row }">
                  <span v-if="row.videoUrl" title="分镜视频就绪">🎬 </span>
                  <span v-if="row.audioUrl" title="配音就绪">🎵 </span>
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
const activeTasks = ref<any[]>([])
const currentTask = computed(() => activeTasks.value[0] ?? null)
const dynamicLoading = ref(false)
const audioLoading = ref(false)
const composeLoading = ref(false)
const downloadLoading = ref(false)
const composeOptions = ref({
  narrationEnabled: true,
  narrationVolume: 0.22,
  dialoguePriority: true,
})

type DialogType = 'dynamic' | 'audio' | 'compose'
type DialogGroup = 'pending' | 'generated'

const showSelectDialog = ref(false)
const dialogType = ref<DialogType>('dynamic')
const expandedDialogGroups = ref<DialogGroup[]>(['pending'])
const pendingSelectedIds = ref<Array<number | string>>([])
const generatedSelectedIds = ref<Array<number | string>>([])
const composeSelectedIds = ref<Array<number | string>>([])
const pendingTableRef = ref<any>(null)
const generatedTableRef = ref<any>(null)
const composeTableRef = ref<any>(null)
const submitLoading = ref(false)

const videoReadyCount = computed(() => shots.value.filter((shot) => !!shot?.videoUrl).length)
const missingVideoCount = computed(() => Math.max(0, Number(overview.value.totalShots || 0) - videoReadyCount.value))

const selectedShotIds = computed(() => {
  if (dialogType.value === 'compose') {
    return composeSelectedIds.value
  }
  return Array.from(new Set([...pendingSelectedIds.value, ...generatedSelectedIds.value]))
})

const taskTrace = computed(() => parseTaskTrace(currentTask.value?.result))
const hasTaskDiagnostics = computed(() => {
  const task = currentTask.value || {}
  return task.totalElapsedMs != null
    || task.externalApiCallCount != null
    || task.externalApiErrorRatio != null
    || (task.failureTypeDistribution && Object.keys(task.failureTypeDistribution).length > 0)
    || (task.stepDurationMs && Object.keys(task.stepDurationMs).length > 0)
})
const failureDistributionText = computed(() => {
  const dist = currentTask.value?.failureTypeDistribution
  if (!dist || typeof dist !== 'object') return ''
  return Object.entries(dist).map(([k, v]) => `${k}:${v}`).join('，')
})
const stepDurationText = computed(() => {
  const steps = currentTask.value?.stepDurationMs
  if (!steps || typeof steps !== 'object') return ''
  return Object.entries(steps)
    .map(([k, v]) => `${k}:${formatMs(Number(v || 0))}`)
    .join('，')
})
const composeSummaryText = computed(() => {
  const payload = parseAnyTaskResult(currentTask.value?.result)
  if (!payload) return ''
  const total = Number(payload.totalShots || 0)
  const videoShots = Number(payload.videoShots ?? payload.dynamicShots ?? 0)
  const ratio = Number(payload.videoRatio ?? payload.dynamicRatio ?? 0)
  if (total <= 0) return ''
  const narration = payload.globalNarrationEnabled
    ? `旁白轨 ${Number(payload.globalNarrationDurationSeconds || 0).toFixed(1)}s`
    : '无旁白轨'
  return `分镜视频 ${videoShots}/${total} (${formatRatio(ratio)})，${narration}`
})
const asyncTaskDetails = computed(() => {
  const items = taskTrace.value?.summary?.asyncTasks
  return Array.isArray(items) ? items : []
})

const dialogTitle = computed(() => {
  switch(dialogType.value) {
    case 'dynamic': return '选择需要生成分镜视频的镜头'
    case 'audio': return '选择需要生成配音的分镜'
    case 'compose': return '选择参与合成的分镜'
    default: return '选择分镜'
  }
})

const pendingGroupTitle = computed(() => {
  switch (dialogType.value) {
    case 'dynamic': return '待生成分镜视频'
    case 'audio': return '待生成配音'
    default: return '待处理分镜'
  }
})

const generatedGroupTitle = computed(() => {
  switch (dialogType.value) {
    case 'dynamic': return '已生成分镜视频'
    case 'audio': return '已生成配音'
    default: return '已生成内容'
  }
})

const isDynamicEligibleShot = (shot: any) => !!shot?.videoPrompt || !!shot?.description

const isGeneratedShotForDialog = (shot: any) => {
  if (dialogType.value === 'dynamic') return !!shot?.videoUrl
  if (dialogType.value === 'audio') return !!shot?.audioUrl
  return false
}

const dynamicEligibleShots = computed(() => shots.value.filter((shot) => isDynamicEligibleShot(shot)))

const dialogShots = computed(() => {
  if (dialogType.value === 'dynamic') {
    return dynamicEligibleShots.value
  }
  if (dialogType.value === 'compose') {
    return shots.value.filter((shot) => !!shot.videoUrl)
  }
  return shots.value
})

const pendingDialogShots = computed(() => dialogShots.value.filter((shot) => !isGeneratedShotForDialog(shot)))

const generatedDialogShots = computed(() => dialogShots.value.filter((shot) => isGeneratedShotForDialog(shot)))

const selectableMethod = (row: any) => {
  if (dialogType.value === 'dynamic') return isDynamicEligibleShot(row)
  if (dialogType.value === 'compose') return !!row.videoUrl
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
  const currentDialogType = dialogType.value
  submitLoading.value = true

  try {
    let res
    if (currentDialogType === 'dynamic') {
      dynamicLoading.value = true
      res = await videoApi.generateStoryboardVideos(projectId, shotIds)
      ElMessage.success('分镜视频生成任务已提交')
    } else if (currentDialogType === 'audio') {
      audioLoading.value = true
      res = await videoApi.generateAudio(projectId, shotIds)
      ElMessage.success('分镜配音生成任务已提交')
    } else if (currentDialogType === 'compose') {
      composeLoading.value = true
      res = await videoApi.compose(projectId, shotIds, composeOptions.value)
      ElMessage.success('视频合成任务已提交')
    }
    showSelectDialog.value = false
    if (res && res.data && res.data.data) {
      const newTask = res.data.data
      activeTasks.value.unshift(newTask)
      startTaskPolling(newTask.id)
      setTimeout(loadOverview, 2000)
    }
  } catch(e: any) {
     ElMessage.error(e.response?.data?.message || '提交失败')
  } finally {
     submitLoading.value = false
     dynamicLoading.value = false
     audioLoading.value = false
     composeLoading.value = false
  }
}

const TASK_POLL_INTERVAL_MS = 5000
const TASK_POLL_EXPIRE_MS = 60 * 60 * 1000

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

// Per-task poll state: taskId -> { timer, startedAt, inFlight }
const taskPollState = new Map<string, { timer: ReturnType<typeof setTimeout> | null; startedAt: number; inFlight: boolean }>()

function startTaskPolling(taskId: string | number) {
  const key = String(taskId)
  if (taskPollState.has(key)) return
  taskPollState.set(key, { timer: null, startedAt: Date.now(), inFlight: false })
  void pollTaskCycle(key)
}

function stopTaskPolling(key: string) {
  const state = taskPollState.get(key)
  if (state?.timer) clearTimeout(state.timer)
  taskPollState.delete(key)
}

function stopPolling() {
  for (const key of taskPollState.keys()) stopTaskPolling(key)
}

async function pollTaskCycle(key: string) {
  const state = taskPollState.get(key)
  if (!state || state.inFlight) return
  if (Date.now() - state.startedAt >= TASK_POLL_EXPIRE_MS) {
    stopTaskPolling(key)
    ElMessage.error('任务轮询已超过 1 小时，请稍后刷新页面查看最新状态')
    return
  }
  state.inFlight = true
  try {
    const res = await taskApi.get(key)
    const updated = res.data.data
    const idx = activeTasks.value.findIndex((t) => String(t.id) === key)
    if (idx !== -1) activeTasks.value[idx] = updated
    if (updated.status === 'SUCCESS') {
      stopTaskPolling(key)
      ElMessage.success(updated.message || '任务完成！')
      await loadOverview()
      await loadShots()
    } else if (updated.status === 'FAILED') {
      stopTaskPolling(key)
      ElMessage.error(updated.message || '任务失败')
    } else {
      state.inFlight = false
      state.timer = setTimeout(() => void pollTaskCycle(key), TASK_POLL_INTERVAL_MS)
      return
    }
  } catch (e: any) {
    console.error('Poll error:', e)
    if (taskPollState.has(key)) {
      state.inFlight = false
      state.timer = setTimeout(() => void pollTaskCycle(key), TASK_POLL_INTERVAL_MS)
      return
    }
  }
  state.inFlight = false
}

const taskTypeLabel = (t: string) => ({
  IMAGE_GEN: '🖼️ 参考资产生成',
  DYNAMIC_VIDEO_GEN: '🎞️ 分镜视频生成',
  AUDIO_GEN: '🎙️ 配音生成',
  VIDEO_COMPOSE: '🎬 视频合成',
}[t] || t)

const shotStatusLabel = (s: string) => ({
  draft: '待处理',
  image_generated: '参考图就绪',
  video_submitted: '分镜视频任务已提交',
  video_polling: '分镜视频生成中',
  video_generated: '分镜视频就绪',
  audio_generated: '配音就绪',
  image_failed: '参考图失败',
  video_failed: '分镜视频失败',
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

function parseAnyTaskResult(raw: any) {
  if (!raw || typeof raw !== 'string') return null
  try {
    return JSON.parse(raw)
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

function formatMs(value: number) {
  if (!Number.isFinite(value) || value < 0) return '-'
  if (value < 1000) return `${Math.round(value)}ms`
  const seconds = value / 1000
  if (seconds < 60) return `${seconds.toFixed(1)}s`
  const mins = Math.floor(seconds / 60)
  const rem = Math.round(seconds % 60)
  return `${mins}m${rem}s`
}

function formatRatio(value: number) {
  if (!Number.isFinite(value)) return '-'
  return `${(Math.max(0, Math.min(1, value)) * 100).toFixed(1)}%`
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
  try {
    const res = await taskApi.listByProject(projectId)
    const tasks = res.data.data || []
    const running = tasks.filter((t: any) => t.status === 'PENDING' || t.status === 'RUNNING')
    if (running.length > 0) {
      activeTasks.value = running
      for (const task of running) {
        startTaskPolling(task.id)
      }
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

.copy-check-card {
  margin-bottom: 20px;
}
.mix-config-card {
  margin-bottom: 16px;
}
.mix-config-grid {
  display: flex;
  align-items: center;
  gap: 20px;
  flex-wrap: wrap;
}
.mix-volume {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--text-secondary);
  font-size: 13px;
}
.copy-check-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  width: 100%;
}

/* Overview cards */
.overview-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 16px;
  margin-bottom: 24px;
}
.ov-card {
  background: var(--bg-card);
  border-radius: var(--radius-md);
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
  border-radius: var(--radius-md);
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
  background: var(--bg-card);
  border-radius: var(--radius-md);
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
  border-radius: var(--radius-md);
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
.action-step--asset {
  border: 1px dashed var(--border);
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
  background: var(--bg-card);
  border-radius: var(--radius-md);
  padding: 20px 24px;
  margin-bottom: 24px;
  border: 1px solid var(--border);
  box-shadow: var(--shadow-sm);
  opacity: 0.7;
  transition: opacity 0.2s, border-color 0.2s;
}
.task-progress-card.is-current {
  opacity: 1;
  border-color: var(--primary-light);
  box-shadow: var(--shadow-sm), 0 0 0 1px var(--primary-light);
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
  border-radius: var(--radius-md);
}
.task-status.status-pending { background: rgba(245, 158, 11, 0.15); color: #fbbf24; }
.task-status.status-running { background: rgba(59, 130, 246, 0.15); color: #60a5fa; }
.task-status.status-success { background: rgba(16, 185, 129, 0.15); color: #34d399; }
.task-status.status-failed { background: rgba(239, 68, 68, 0.15); color: #f87171; }
.task-message {
  margin-top: 8px;
  font-size: 13px;
  color: var(--text-secondary);
}
.task-diagnostics {
  margin-top: 10px;
  padding: 10px 12px;
  border-radius: var(--radius-md);
  border: 1px dashed var(--border);
  background: var(--bg-muted);
  color: var(--text-secondary);
  font-size: 12px;
  display: grid;
  gap: 4px;
}
.task-trace-panel {
  margin-top: 16px;
  border-top: 1px solid var(--border);
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
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: 12px;
  background: var(--bg-card);
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
  border-radius: var(--radius-full);
}
.task-trace-index { background: var(--bg-muted); color: var(--text-secondary); }
.task-trace-shot { background: #dbeafe; color: #1d4ed8; }
.task-trace-method { background: #ede9fe; color: #6d28d9; }
.task-trace-url {
  flex: 1;
  min-width: 280px;
  font-size: 12px;
  color: var(--text-primary);
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
  border-radius: var(--radius-sm);
  background: var(--bg-muted);
  color: var(--text-primary);
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}
.task-trace-error {
  margin-top: 10px;
  padding: 10px;
  border-radius: var(--radius-sm);
  background: rgba(239, 68, 68, 0.1);
  color: #f87171;
  font-size: 12px;
  white-space: pre-wrap;
}
.task-async-panel {
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px dashed var(--border);
}
.task-async-list {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 12px;
  margin-top: 12px;
}
.task-async-item {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: 12px;
  background: var(--bg-card);
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
  color: var(--text-primary);
  line-height: 1.5;
  word-break: break-all;
}

/* Video section */
.video-section {
  background: var(--bg-card);
  border-radius: var(--radius-lg);
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
  border-radius: var(--radius-md);
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
  color: var(--el-color-warning);
  font-weight: 600;
}

/* Shots section */
.shots-section {
  background: var(--bg-card);
  border-radius: var(--radius-lg);
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
  border-radius: var(--radius-sm);
}
.shot-status.status-draft { background: var(--bg-muted); color: var(--text-muted); }
.shot-status.status-image_generated { background: rgba(59, 130, 246, 0.15); color: #60a5fa; }
.shot-status.status-video_generated { background: var(--primary-glow); color: var(--primary-light); }
.shot-status.status-audio_generated { background: rgba(245, 158, 11, 0.15); color: #fbbf24; }
.shot-status.status-image_failed { background: rgba(239, 68, 68, 0.15); color: #f87171; }
.shot-status.status-video_failed { background: rgba(239, 68, 68, 0.15); color: #f87171; }
.shot-status.status-audio_failed { background: rgba(239, 68, 68, 0.15); color: #f87171; }
.shot-status.status-completed { background: rgba(16, 185, 129, 0.15); color: #34d399; }

.shot-media {
  position: relative;
  aspect-ratio: 9/16;
  background: var(--bg-muted);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}
.shot-media video,
.shot-media img.legacy-reference-image {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.legacy-reference-image {
  opacity: 0.68;
}
.no-video {
  padding: 14px;
  text-align: center;
  font-size: 13px;
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
.video-ready { color: var(--secondary); }
.video-pending { color: var(--text-muted); }
.reference-ready { color: var(--color-success); }
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
  border-radius: var(--radius-md);
}
.status-draft { background: var(--bg-muted); color: var(--text-muted); }
.status-generating { background: rgba(59, 130, 246, 0.15); color: #60a5fa; }
.status-completed { background: rgba(16, 185, 129, 0.15); color: #34d399; }
.status-failed { background: rgba(239, 68, 68, 0.15); color: #f87171; }
</style>
    