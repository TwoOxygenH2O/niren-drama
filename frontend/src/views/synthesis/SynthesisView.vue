<template>
  <div class="page-container">
    <!-- Header nav -->
    <div class="project-nav">
      <el-button text @click="$router.push(`/projects/${projectId}`)">
        <el-icon><ArrowLeft /></el-icon> 返回项目
      </el-button>
      <span class="project-name">合成导出</span>
      <span :class="`status-badge status-${overview.videoUrl ? 'completed' : 'draft'}`">
        {{ overview.videoUrl ? '成片就绪' : '待合成' }}
      </span>
      <span class="syn-nav-meta">{{ overview.totalShots }} 个镜头 · {{ videoReadyCount }} 个视频就绪</span>
      <div class="syn-nav-actions">
        <el-button text @click="$router.push(`/projects/${projectId}/immersive`)">创作页</el-button>
        <el-button text @click="$router.push({ path: `/projects/${projectId}/immersive/workbench`, query: { tab: 'video' } })">
          镜头工作台
        </el-button>
      </div>
    </div>

    <!-- Stats row -->
    <div class="project-info-cards">
      <div class="info-card">
        <div class="info-label">分镜视频</div>
        <div class="info-value">{{ videoReadyCount }} / {{ overview.totalShots }}</div>
      </div>
      <div class="info-card">
        <div class="info-label">参考图片</div>
        <div class="info-value">{{ overview.imagesReady }} / {{ overview.totalShots }}</div>
      </div>
      <div class="info-card">
        <div class="info-label">配音就绪</div>
        <div class="info-value">{{ overview.audioReady }} / {{ overview.totalShots }}</div>
      </div>
      <div class="info-card">
        <div class="info-label">成片状态</div>
        <div class="info-value">{{ overview.videoUrl ? '已合成' : '待合成' }}</div>
      </div>
    </div>

    <!-- Action steps -->
    <div class="workflow-nav syn-steps">
      <div class="workflow-step" :class="{ active: !episodeStoryboardReady && dynamicEligibleShots.length > 0 }">
        <span class="wf-icon" v-html="videoIcon"></span>
        <span class="wf-label">1. 生成视频</span>
      </div>
      <div class="workflow-step">
        <span class="wf-icon" v-html="audioIcon"></span>
        <span class="wf-label">2. 生成配音</span>
      </div>
      <div class="workflow-step" :class="{ active: videoReadyCount > 0 }">
        <span class="wf-icon" v-html="composeIcon"></span>
        <span class="wf-label">3. 合成成片</span>
      </div>
    </div>

    <!-- Action buttons -->
    <div class="syn-action-bar">
      <el-button :loading="dynamicLoading" @click="openDialog('dynamic')" :disabled="dynamicEligibleShots.length === 0">
        {{ videoReadyCount > 0 ? '重新生成视频' : '生成分镜视频' }}
      </el-button>
      <el-button :loading="audioLoading" @click="openDialog('audio')" :disabled="overview.totalShots === 0">
        {{ overview.audioReady > 0 ? '重新生成配音' : '生成配音' }}
      </el-button>
      <el-button type="primary" :loading="composeLoading" @click="openDialog('compose')" :disabled="!canCompose">
        <el-icon><VideoPlay /></el-icon>
        {{ overview.videoUrl ? '重新合成' : '合成成片' }}
      </el-button>
      <div class="syn-compose-options">
        <el-checkbox v-model="composeOptions.narrationEnabled" size="small">旁白轨</el-checkbox>
        <el-checkbox v-model="composeOptions.dialoguePriority" size="small">对白优先</el-checkbox>
      </div>
    </div>

    <div class="syn-readiness-strip" :class="{ 'is-ready': canCompose }">
      <span>{{ readinessText }}</span>
      <span v-if="missingVideoCount > 0">缺少 {{ missingVideoCount }} 个视频镜头</span>
    </div>

    <!-- Task progress -->
    <div v-for="task in activeTasks" :key="task.id" class="syn-task-card">
      <div class="syn-task-head">
        <span class="syn-task-type">{{ taskTypeLabel(task.taskType) }}</span>
        <span :class="`status-badge status-${(task.status || '').toLowerCase()}`">{{ task.status }}</span>
      </div>
      <el-progress :percentage="task.progress || 0" :stroke-width="8" :show-text="false" style="margin-bottom: 8px" />
      <div class="syn-task-msg">{{ task.message }}</div>
    </div>

    <!-- Video preview -->
    <div v-if="overview.videoUrl" class="syn-video-card">
      <div class="syn-video-player">
        <video :src="overview.videoUrl" controls playsinline class="syn-video" />
      </div>
      <div class="syn-video-actions">
        <el-button type="primary" size="large" :loading="downloadLoading" @click="handleDownload">
          <el-icon><Download /></el-icon> 下载成片
        </el-button>
      </div>
    </div>

    <!-- Shot grid -->
    <div class="syn-shots-card">
      <div class="syn-shots-head">
        <span class="syn-shots-title">分镜预览</span>
        <span class="syn-shots-count">{{ shots.length }} 个镜头</span>
      </div>
      <div v-if="!shots.length" class="syn-empty">暂无分镜数据，请先到策划页生成。</div>
      <div v-else class="syn-shots-grid">
        <div
          v-for="shot in shots"
          :key="shot.id"
          class="syn-shot-item"
        >
          <div class="syn-shot-thumb">
            <video v-if="shot.videoUrl" :src="shot.videoUrl" muted preload="metadata" />
            <img v-else-if="shot.imageUrl" :src="shot.imageUrl" alt="" />
            <div v-else class="syn-shot-placeholder">🎞️</div>
          </div>
          <div class="syn-shot-body">
            <div class="syn-shot-no">镜头 {{ shot.shotNo }}</div>
            <div class="syn-shot-desc">{{ truncate(shot.description, 30) }}</div>
            <div class="syn-shot-meta">
              <span v-if="shot.duration">⏱ {{ shot.duration }}s</span>
              <span v-if="shot.videoUrl" class="syn-shot-tag syn-shot-tag--ok">🎬 视频就绪</span>
              <span v-else class="syn-shot-tag">⏳ 待生成</span>
              <span v-if="shot.audioUrl" class="syn-shot-tag syn-shot-tag--ok">🎵 配音就绪</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Shot selection dialog -->
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
          :data="dialogShots"
          @selection-change="handleComposeSelectionChange"
          height="500px"
          empty-text="当前没有可选分镜"
        >
          <el-table-column type="selection" width="55" :selectable="selectableMethod" />
          <el-table-column prop="shotNo" label="镜头号" width="80" />
          <el-table-column prop="description" label="画面描述" min-width="200" show-overflow-tooltip />
          <el-table-column label="状态" width="120" align="center">
            <template #default="{ row }">
              <span v-if="row.videoUrl">🎬 视频</span>
              <span v-if="row.audioUrl">🎵 配音</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <div v-else class="selection-groups">
        <div class="selection-tip">已生成内容默认折叠，展开后才可重新勾选</div>
        <el-collapse v-model="expandedDialogGroups">
          <el-collapse-item name="pending">
            <template #title>
              {{ pendingGroupTitle }}（{{ pendingDialogShots.length }}）
            </template>
            <el-table
              ref="pendingTableRef"
              :data="pendingDialogShots"
              @selection-change="handlePendingSelectionChange"
              height="240px"
              empty-text="当前没有待生成分镜"
            >
              <el-table-column type="selection" width="55" :selectable="selectableMethod" />
              <el-table-column prop="shotNo" label="镜头号" width="80" />
              <el-table-column prop="description" label="画面描述" min-width="200" show-overflow-tooltip />
              <el-table-column label="状态" width="120" align="center">
                <template #default="{ row }">
                  <span v-if="row.videoUrl">🎬</span>
                  <span v-if="row.audioUrl">🎵</span>
                </template>
              </el-table-column>
            </el-table>
          </el-collapse-item>
          <el-collapse-item name="generated" :disabled="generatedDialogShots.length === 0">
            <template #title>
              {{ generatedGroupTitle }}（{{ generatedDialogShots.length }}）
            </template>
            <div class="selection-group-hint">展开后可重新勾选，覆盖已保存结果</div>
            <el-table
              ref="generatedTableRef"
              :data="generatedDialogShots"
              @selection-change="handleGeneratedSelectionChange"
              height="240px"
              empty-text="当前没有已生成分镜"
            >
              <el-table-column type="selection" width="55" :selectable="selectableMethod" />
              <el-table-column prop="shotNo" label="镜头号" width="80" />
              <el-table-column prop="description" label="画面描述" min-width="200" show-overflow-tooltip />
              <el-table-column label="状态" width="120" align="center">
                <template #default="{ row }">
                  <span v-if="row.videoUrl">🎬</span>
                  <span v-if="row.audioUrl">🎵</span>
                </template>
              </el-table-column>
            </el-table>
          </el-collapse-item>
        </el-collapse>
      </div>
      <template #footer>
        <el-button @click="showSelectDialog = false">取消</el-button>
        <el-button type="primary" @click="confirmGenerate" :loading="submitLoading" :disabled="selectedShotIds.length === 0">
          确定 (已选 {{ selectedShotIds.length }})
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, nextTick, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Download, VideoPlay } from '@element-plus/icons-vue'
import { videoApi } from '@/api/video'
import { taskApi } from '@/api/task'

const route = useRoute()
const projectId = route.params.id as string

const overview = ref<any>({ totalShots: 0, dynamicRecommended: 0, dynamicSelected: 0, dynamicReady: 0, imagesReady: 0, audioReady: 0, videoUrl: '' })
const shots = ref<any[]>([])
const activeTasks = ref<any[]>([])
const dynamicLoading = ref(false)
const audioLoading = ref(false)
const composeLoading = ref(false)
const downloadLoading = ref(false)
const composeOptions = ref({ narrationEnabled: true, narrationVolume: 0.22, dialoguePriority: true })

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
const episodeStoryboardReady = computed(() => shots.value.length > 0)
const missingVideoCount = computed(() => Math.max(0, overview.value.totalShots - videoReadyCount.value))
const canCompose = computed(() => videoReadyCount.value > 0 && overview.value.totalShots > 0)
const readinessText = computed(() => {
  if (!overview.value.totalShots) return '当前项目还没有分镜，请先在创作页完成剧本与分镜。'
  if (!videoReadyCount.value) return '先生成分镜视频，再合成成片。'
  if (missingVideoCount.value > 0) return '可先合成已生成的视频镜头，也可继续补齐剩余镜头。'
  return '视频镜头已就绪，可以合成导出。'
})

const selectedShotIds = computed(() => {
  if (dialogType.value === 'compose') return composeSelectedIds.value
  return Array.from(new Set([...pendingSelectedIds.value, ...generatedSelectedIds.value]))
})

const dialogTitle = computed(() => {
  switch (dialogType.value) {
    case 'dynamic': return '选择需要生成分镜视频的镜头'
    case 'audio': return '选择需要生成配音的分镜'
    case 'compose': return '选择参与合成的分镜'
    default: return '选择分镜'
  }
})
const pendingGroupTitle = computed(() => dialogType.value === 'dynamic' ? '待生成分镜视频' : '待生成配音')
const generatedGroupTitle = computed(() => dialogType.value === 'dynamic' ? '已生成分镜视频' : '已生成配音')

const isDynamicEligibleShot = (shot: any) => !!shot?.videoPrompt || !!shot?.description
const isGeneratedShotForDialog = (shot: any) => {
  if (dialogType.value === 'dynamic') return !!shot?.videoUrl
  if (dialogType.value === 'audio') return !!shot?.audioUrl
  return false
}
const dynamicEligibleShots = computed(() => shots.value.filter((shot) => isDynamicEligibleShot(shot)))
const dialogShots = computed(() => {
  if (dialogType.value === 'dynamic') return dynamicEligibleShots.value
  if (dialogType.value === 'compose') return shots.value.filter((shot) => !!shot.videoUrl)
  return shots.value
})
const pendingDialogShots = computed(() => dialogShots.value.filter((shot) => !isGeneratedShotForDialog(shot)))
const generatedDialogShots = computed(() => dialogShots.value.filter((shot) => isGeneratedShotForDialog(shot)))
const selectableMethod = (row: any) => {
  if (dialogType.value === 'dynamic') return isDynamicEligibleShot(row)
  if (dialogType.value === 'compose') return !!row.videoUrl
  return true
}

const handlePendingSelectionChange = (rows: any[]) => { pendingSelectedIds.value = rows.map((r) => r.id) }
const handleGeneratedSelectionChange = (rows: any[]) => { generatedSelectedIds.value = rows.map((r) => r.id) }
const handleComposeSelectionChange = (rows: any[]) => { composeSelectedIds.value = rows.map((r) => r.id) }

const resetDialogSelection = () => {
  expandedDialogGroups.value = ['pending']
  pendingSelectedIds.value = []; generatedSelectedIds.value = []; composeSelectedIds.value = []
  pendingTableRef.value?.clearSelection?.(); generatedTableRef.value?.clearSelection?.(); composeTableRef.value?.clearSelection?.()
}
const openDialog = async (type: DialogType) => {
  dialogType.value = type
  resetDialogSelection()
  showSelectDialog.value = true
  await nextTick()
  await nextTick()
  selectDefaultRows()
}

function selectDefaultRows() {
  if (dialogType.value === 'compose') {
    composeSelectedIds.value = dialogShots.value.map((row) => row.id)
    dialogShots.value.forEach((row) => composeTableRef.value?.toggleRowSelection?.(row, true))
    return
  }
  pendingSelectedIds.value = pendingDialogShots.value.map((row) => row.id)
  pendingDialogShots.value.forEach((row) => pendingTableRef.value?.toggleRowSelection?.(row, true))
}

const confirmGenerate = async () => {
  if (selectedShotIds.value.length === 0) { ElMessage.warning('请至少选择一个分镜'); return }
  const shotIds = [...selectedShotIds.value]
  submitLoading.value = true
  try {
    let res
    if (dialogType.value === 'dynamic') {
      dynamicLoading.value = true
      res = await videoApi.generateStoryboardVideos(projectId, shotIds)
      ElMessage.success('分镜视频生成任务已提交')
    } else if (dialogType.value === 'audio') {
      audioLoading.value = true
      res = await videoApi.generateAudio(projectId, shotIds)
      ElMessage.success('分镜配音生成任务已提交')
    } else {
      composeLoading.value = true
      res = await videoApi.compose(projectId, shotIds, composeOptions.value)
      ElMessage.success('视频合成任务已提交')
    }
    showSelectDialog.value = false
    if (res?.data?.data) {
      activeTasks.value.unshift(res.data.data)
      startTaskPolling(res.data.data.id)
      setTimeout(loadOverview, 2000)
    }
  } catch (e: any) { ElMessage.error(e.response?.data?.message || '提交失败') }
  finally { submitLoading.value = false; dynamicLoading.value = false; audioLoading.value = false; composeLoading.value = false }
}

async function loadOverview() {
  try { const res = await videoApi.getOverview(projectId); overview.value = res.data.data || overview.value } catch { /* */ }
}
async function loadShots() {
  try { const res = await videoApi.getStoryboards(projectId); shots.value = res.data.data || [] } catch { /* */ }
}

function handleDownload() {
  downloadLoading.value = true
  const a = document.createElement('a')
  a.href = videoApi.getDownloadUrl(projectId); a.download = `niren_drama_${projectId}.mp4`
  document.body.appendChild(a); a.click(); document.body.removeChild(a)
  ElMessage.success('下载已开始')
  let count = 0
  const id = setInterval(async () => {
    try { count++; const r = await videoApi.getStatus(projectId); if (!r.data.data?.result || count >= 60) { clearInterval(id); downloadLoading.value = false; await loadOverview() } }
    catch { clearInterval(id); downloadLoading.value = false }
  }, 5000)
}

const taskPollState = new Map<string, { timer: any; startedAt: number; inFlight: boolean }>()
function startTaskPolling(taskId: string | number) {
  const key = String(taskId)
  if (taskPollState.has(key)) return
  taskPollState.set(key, { timer: null, startedAt: Date.now(), inFlight: false })
  void pollTaskCycle(key)
}
function stopPolling() { for (const [k, s] of taskPollState) { if (s.timer) clearTimeout(s.timer); taskPollState.delete(k) } }

async function pollTaskCycle(key: string) {
  const state = taskPollState.get(key)
  if (!state || state.inFlight) return
  if (Date.now() - state.startedAt >= 3600000) { taskPollState.delete(key); return }
  state.inFlight = true
  try {
    const res = await taskApi.get(key)
    const updated = res.data.data
    const idx = activeTasks.value.findIndex(t => String(t.id) === key)
    if (idx !== -1) activeTasks.value[idx] = updated
    if (updated.status === 'SUCCESS') { taskPollState.delete(key); ElMessage.success('任务完成！'); await loadOverview(); await loadShots() }
    else if (updated.status === 'FAILED') { taskPollState.delete(key); ElMessage.error(updated.message || '任务失败') }
    else { state.inFlight = false; state.timer = setTimeout(() => pollTaskCycle(key), 5000); return }
  } catch { if (taskPollState.has(key)) { state.inFlight = false; state.timer = setTimeout(() => pollTaskCycle(key), 5000); return } }
  state.inFlight = false
}

const taskTypeLabel = (t: string) => ({ IMAGE_GEN: '参考资产生成', DYNAMIC_VIDEO_GEN: '分镜视频生成', AUDIO_GEN: '配音生成', VIDEO_COMPOSE: '视频合成' }[t] || t)
function truncate(str: string, len: number) { if (!str) return ''; return str.length > len ? str.substring(0, len) + '...' : str }

const videoIcon = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><rect x="2" y="3" width="20" height="14" rx="2"/><polyline points="8 21 12 17 16 21"/></svg>'
const audioIcon = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/></svg>'
const composeIcon = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"><polygon points="5 3 19 12 5 21"/></svg>'

onMounted(async () => {
  await Promise.all([loadOverview(), loadShots()])
  try { const tasks = (await taskApi.listByProject(projectId)).data.data || []; const running = tasks.filter((t: any) => t.status === 'PENDING' || t.status === 'RUNNING'); if (running.length) { activeTasks.value = running; running.forEach((t: any) => startTaskPolling(t.id)) } } catch { /* */ }
})
onUnmounted(() => stopPolling())
</script>

<style scoped>
.page-container { padding: 24px; }

.project-nav { display: flex; align-items: center; gap: 16px; margin-bottom: 24px; flex-wrap: wrap; }
.project-name { font-size: 20px; font-weight: 600; color: var(--text-primary); }
.syn-nav-meta { font-size: 13px; color: var(--text-muted); margin-left: 4px; }
.syn-nav-actions { margin-left: auto; display: flex; align-items: center; gap: 6px; }

.project-info-cards { display: flex; gap: 16px; margin-bottom: 20px; flex-wrap: wrap; }
.info-card { background: var(--bg-card); border-radius: var(--radius-md); padding: 16px 24px; box-shadow: var(--shadow-sm); min-width: 120px; }
.info-label { font-size: 12px; color: var(--text-muted); margin-bottom: 4px; }
.info-value { font-size: 18px; font-weight: 600; color: var(--text-primary); }

.syn-steps { margin-bottom: 20px; }
.syn-steps .workflow-step { cursor: default; }
.syn-steps .workflow-step.active { background: var(--primary-glow); color: var(--primary); }
.syn-steps .workflow-step.active .wf-icon { opacity: 1; }
.workflow-nav { display: flex; gap: 12px; background: var(--bg-card); padding: 16px; border-radius: var(--radius-md); box-shadow: var(--shadow-sm); }
.workflow-step { display: flex; flex-direction: column; align-items: center; gap: 6px; padding: 12px 20px; border-radius: var(--radius-sm); min-width: 80px; }
.wf-icon { font-size: 22px; opacity: 0.5; }
.wf-label { font-size: 13px; font-weight: 500; color: var(--text-secondary); }
.workflow-step.active .wf-label { color: var(--primary); font-weight: 600; }

.syn-action-bar { display: flex; align-items: center; gap: 10px; margin-bottom: 20px; flex-wrap: wrap; }
.syn-compose-options { display: flex; gap: 14px; margin-left: auto; }
.syn-readiness-strip { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 10px 14px; margin: -8px 0 20px; border-radius: var(--radius-sm); background: rgba(245,158,11,0.1); color: var(--text-secondary); font-size: 12px; }
.syn-readiness-strip.is-ready { background: rgba(16,185,129,0.1); color: var(--text-primary); }

.syn-task-card { background: var(--bg-card); border-radius: var(--radius-md); padding: 16px 20px; margin-bottom: 20px; box-shadow: var(--shadow-sm); }
.syn-task-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.syn-task-type { font-size: 14px; font-weight: 600; color: var(--text-primary); }
.syn-task-msg { font-size: 12px; color: var(--text-muted); }

.status-badge { font-size: 12px; font-weight: 600; padding: 2px 10px; border-radius: var(--radius-md); }
.status-draft, .status-pending { background: rgba(156,163,175,0.15); color: var(--text-muted); }
.status-running { background: rgba(59,130,246,0.15); color: #60a5fa; }
.status-success, .status-completed { background: rgba(16,185,129,0.15); color: #34d399; }
.status-failed { background: rgba(239,68,68,0.15); color: #f87171; }

.syn-video-card { background: var(--bg-card); border-radius: var(--radius-md); padding: 24px; margin-bottom: 20px; box-shadow: var(--shadow-sm); display: flex; flex-direction: column; align-items: center; gap: 16px; }
.syn-video-player { background: #000; border-radius: var(--radius-md); overflow: hidden; max-width: 360px; width: 100%; }
.syn-video { width: 100%; max-height: 640px; display: block; }
.syn-video-actions { display: flex; gap: 12px; }

.syn-shots-card { background: var(--bg-card); border-radius: var(--radius-md); padding: 24px; box-shadow: var(--shadow-sm); }
.syn-shots-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.syn-shots-title { font-size: 15px; font-weight: 600; color: var(--text-primary); }
.syn-shots-count { font-size: 13px; color: var(--text-muted); }
.syn-empty { text-align: center; padding: 48px; color: var(--text-muted); font-size: 14px; }

.syn-shots-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 14px; }
.syn-shot-item { border: 1px solid var(--border); border-radius: var(--radius-md); overflow: hidden; transition: transform 0.15s, box-shadow 0.15s; }
.syn-shot-item:hover { transform: translateY(-2px); box-shadow: var(--shadow-md); }
.syn-shot-thumb { aspect-ratio: 9/16; background: var(--bg-muted); display: flex; align-items: center; justify-content: center; overflow: hidden; }
.syn-shot-thumb video, .syn-shot-thumb img { width: 100%; height: 100%; object-fit: cover; }
.syn-shot-placeholder { font-size: 28px; opacity: 0.4; }
.syn-shot-body { padding: 10px 12px; }
.syn-shot-no { font-size: 12px; font-weight: 700; color: var(--primary); margin-bottom: 4px; }
.syn-shot-desc { font-size: 12px; color: var(--text-secondary); line-height: 1.4; margin-bottom: 8px; }
.syn-shot-meta { display: flex; flex-wrap: wrap; gap: 5px; font-size: 11px; color: var(--text-muted); }
.syn-shot-tag { padding: 1px 6px; border-radius: var(--radius-sm); background: var(--bg-muted); color: var(--text-muted); }
.syn-shot-tag--ok { background: rgba(16,185,129,0.12); color: #34d399; }

.selection-groups { display: flex; flex-direction: column; gap: 12px; }
.selection-tip { font-size: 12px; color: var(--text-muted); margin-bottom: 4px; }
.selection-group-hint { margin-bottom: 10px; font-size: 12px; color: var(--text-muted); }
</style>
