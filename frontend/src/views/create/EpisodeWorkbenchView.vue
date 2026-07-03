<template>
  <div class="production-workbench">
    <header class="pw-header">
      <div class="pw-title-block">
        <button type="button" class="icon-button" title="返回策划" aria-label="返回策划" @click="goBack">
          <el-icon><ArrowLeft /></el-icon>
        </button>
        <div class="pw-title-copy">
          <div class="pw-kicker">短剧生产线</div>
          <h1>{{ projectName }}</h1>
        </div>
      </div>

      <div class="mode-switch" aria-label="生成模式">
        <button :class="{ active: mode === 'preview' }" type="button" aria-label="切换到预览版" @click="switchMode('preview')">
          预览版
        </button>
        <button :class="{ active: mode === 'publish' }" type="button" aria-label="切换到发布版" @click="switchMode('publish')">
          发布版
        </button>
      </div>

      <div class="pw-header-actions">
        <button type="button" class="text-button" @click="goStoryboard">分镜编辑</button>
        <button type="button" class="icon-button" title="刷新" aria-label="刷新生产线" :disabled="loading" @click="loadWorkspace()">
          <el-icon><Refresh /></el-icon>
        </button>
      </div>
    </header>

    <main class="pw-layout" :class="{ 'no-stages': !stages.length }">
      <aside v-if="stages.length" class="stage-rail">
        <div
          v-for="stage in stages"
          :key="stage.id"
          class="stage-item"
          :class="[`is-${stage.status}`]"
        >
          <div class="stage-topline">
            <span>{{ stage.label }}</span>
            <b>{{ stage.ready }}/{{ stage.total }}</b>
          </div>
          <div class="stage-track">
            <span :style="{ width: `${stage.percent || 0}%` }" />
          </div>
          <div class="stage-status">{{ stage.statusLabel }}</div>
        </div>
      </aside>

      <section class="center-pane">
        <div class="overview-band">
          <div class="overview-main">
            <div class="overview-label">当前动作</div>
            <div class="overview-title">{{ primaryAction.title }}</div>
            <p>{{ primaryAction.description }}</p>
          </div>
          <div class="overview-actions">
            <button
              type="button"
              class="primary-button"
              :disabled="!primaryAction.enabled || !!loadingAction"
              @click="executePrimaryAction"
            >
              {{ loadingAction ? '提交中' : primaryAction.title }}
            </button>
            <button type="button" class="secondary-button" :disabled="!!loadingAction" @click="runQualityCheck">
              <el-icon><DocumentChecked /></el-icon>
              运行质检
            </button>
          </div>
        </div>

        <div class="health-row">
          <div
            v-for="item in healthItems"
            :key="item.key"
            class="health-pill"
            :class="[`is-${item.status}`]"
          >
            <span class="health-dot" />
            <span>{{ item.label }}</span>
          </div>
        </div>

        <section class="timeline-section">
          <div class="section-head">
            <div>
              <h2>镜头时间线</h2>
              <p>{{ shots.length }} 个镜头 · {{ summary.videoReady || 0 }} 段视频 · {{ summary.audioReady || 0 }} 段配音</p>
            </div>
            <div class="section-actions">
              <button type="button" class="secondary-button" :disabled="!!loadingAction" @click="generateFirstFrames">
                <el-icon><Picture /></el-icon>
                补齐首帧
              </button>
              <button type="button" class="secondary-button" :disabled="!!loadingAction" @click="generateVideos">
                <el-icon><VideoPlay /></el-icon>
                {{ mode === 'preview' ? '生成预览视频' : '生成发布视频' }}
              </button>
              <button type="button" class="secondary-button" :disabled="!!loadingAction" @click="generateAudio">
                <el-icon><Microphone /></el-icon>
                补齐配音
              </button>
              <button type="button" class="primary-button" :disabled="!!loadingAction || !shots.length" @click="composeCurrent">
                <el-icon><Finished /></el-icon>
                合成{{ mode === 'preview' ? '预览' : '发布' }}
              </button>
            </div>
          </div>

          <div v-if="loading && !workspace" class="empty-state">正在加载生产线状态</div>
          <div v-else-if="!shots.length" class="empty-state empty-state--action">
            <span>当前项目暂无分镜</span>
            <button type="button" class="primary-button" @click="goStoryboard">生成分镜</button>
          </div>
          <template v-else>
            <div v-if="noVideosReady" class="inline-guide">
              <div>
                <b>分镜视频尚未生成</b>
                <p>{{ mode === 'preview' ? '预览版会先快测最多 3 个镜头。' : '发布版会为全部缺失镜头生成高一致性视频。' }}</p>
              </div>
              <button type="button" class="primary-button" :disabled="!!loadingAction" @click="generateVideos">
                {{ mode === 'preview' ? '生成预览视频' : '生成发布视频' }}
              </button>
            </div>
            <div class="shot-grid">
              <button
                v-for="shot in shots"
                :key="shot.id"
                type="button"
                class="shot-card"
                :class="{ active: selectedShot?.id === shot.id, 'has-issue': issueCount(shot) > 0 }"
                :aria-label="`选择镜头 ${shot.shotNo || '-'}`"
                @click="selectShot(shot)"
              >
                <div class="shot-thumb">
                  <video v-if="shot.videoUrl" :src="shot.videoUrl" muted preload="metadata" />
                  <img
                    v-else-if="shot.imageUrl"
                    :src="shot.imageUrl"
                    :alt="`镜头 ${shot.shotNo || '-'} 首帧缩略图`"
                    loading="lazy"
                    decoding="async"
                  />
                  <span v-else>待首帧</span>
                </div>
                <div class="shot-meta">
                  <div class="shot-number">镜头 {{ shot.shotNo || '-' }}</div>
                  <div class="shot-status-line">
                    <span>{{ shot.status?.video || '待视频' }}</span>
                    <b v-if="issueCount(shot)">{{ issueCount(shot) }}</b>
                  </div>
                </div>
              </button>
            </div>
          </template>
        </section>

        <div class="lower-grid">
          <section class="work-panel">
            <div class="section-head compact">
              <h2>活动任务</h2>
              <button type="button" class="link-button" @click="loadWorkspace()">刷新</button>
            </div>
            <div v-if="!activeTasks.length" class="panel-empty">暂无运行任务</div>
            <div v-for="task in activeTasks" :key="task.id" class="task-row">
              <div class="task-title">
                <span>{{ task.typeLabel }}</span>
                <b>{{ task.statusLabel }}</b>
              </div>
              <div class="task-track"><span :style="{ width: `${task.progress || 0}%` }" /></div>
              <p>{{ task.message || '任务处理中' }}</p>
            </div>
          </section>

          <section class="work-panel">
            <div class="section-head compact">
              <h2>失败与修复</h2>
              <div class="issue-tools">
                <div class="issue-filter" aria-label="问题严重性筛选">
                  <button
                    v-for="item in issueFilterOptions"
                    :key="item.value"
                    type="button"
                    :class="{ active: issueSeverityFilter === item.value }"
                    @click="issueSeverityFilter = item.value"
                  >
                    {{ item.label }}
                  </button>
                </div>
                <button type="button" class="link-button" :disabled="!!loadingAction" @click="clearStaleTasks">
                  清理陈旧任务
                </button>
              </div>
            </div>
            <div v-if="!filteredIssues.length" class="panel-empty">暂无待处理问题</div>
            <div
              v-for="issue in filteredIssues.slice(0, 6)"
              :key="issue.id"
              class="issue-row"
              :class="[`is-${issue.severity}`]"
              role="button"
              tabindex="0"
              @click="focusIssue(issue)"
              @keydown.enter.prevent="focusIssue(issue)"
            >
              <div class="issue-copy">
                <div class="issue-title-line">
                  <span>{{ issue.title }}</span>
                  <b>{{ issue.severityLabel || severityLabel(issue.severity) }}</b>
                </div>
                <p>{{ issue.message }}</p>
                <p v-if="issue.impact" class="issue-impact">{{ issue.impact }}</p>
                <small>
                  推荐：{{ issue.recommendedActionLabel || '查看详情' }}
                  <template v-if="issue.estimatedMinutes"> · 约 {{ issue.estimatedMinutes }} 分钟</template>
                </small>
              </div>
              <div class="issue-actions">
                <button
                  v-for="action in visibleIssueActions(issue)"
                  :key="`${issue.id}-${action.id}`"
                  type="button"
                  :disabled="!!loadingAction"
                  @click.stop="runRepair(action.id, issue.shotId ? [issue.shotId] : [])"
                >
                  {{ action.label }}
                </button>
              </div>
            </div>
          </section>

          <section class="work-panel casr-panel">
            <div class="section-head compact">
              <div>
                <h2>CASR 自修复实验室</h2>
                <p>连续性感知诊断、失败归因、成本敏感策略搜索</p>
              </div>
              <div class="casr-actions">
                <button type="button" class="secondary-button" :disabled="!!casrLoading" @click="runCasrAnalyze">
                  <el-icon><DocumentChecked /></el-icon>
                  运行诊断
                </button>
                <button type="button" class="primary-button" :disabled="!!casrLoading" @click="runCasrPlan">
                  <el-icon><Finished /></el-icon>
                  策略搜索
                </button>
              </div>
            </div>

            <div v-if="!casrAnalysis" class="panel-empty">CASR 尚未运行</div>
            <template v-else>
              <div class="casr-score-grid">
                <div>
                  <span>结构质量</span>
                  <b>{{ casrAnalysis.qualityScore }}</b>
                </div>
                <div>
                  <span>连续性</span>
                  <b>{{ casrAnalysis.continuityScore }}</b>
                </div>
                <div>
                  <span>综合分</span>
                  <b>{{ casrAnalysis.overallScore }}</b>
                </div>
                <div>
                  <span>风险类型</span>
                  <b>{{ casrFailureTypes.length }}</b>
                </div>
              </div>

              <div v-if="casrFailureTypes.length" class="casr-chip-row">
                <span v-for="item in casrFailureTypes" :key="item">{{ failureTypeLabel(item) }}</span>
              </div>

              <div class="casr-shot-graph">
                <button
                  v-for="shot in casrShotDiagnoses"
                  :key="shot.shotId || shot.shotNo"
                  type="button"
                  :class="[`is-${shot.severity}`]"
                  @click="shot.shotId && (selectedShotId = shot.shotId)"
                >
                  <span>镜头 {{ shot.shotNo || '-' }}</span>
                  <b>{{ shot.overallScore || Math.round(((shot.qualityScore || 0) + (shot.continuityScore || 0)) / 2) }}</b>
                  <small>{{ failureTypeLabel(shot.failureTypes?.[0]) || '通过' }}</small>
                </button>
              </div>

              <div v-if="casrPlan" class="casr-policy">
                <div class="casr-plan-head">
                  <div>
                    <span>推荐路径</span>
                    <b>{{ casrRecommendedOption?.label || '未生成' }}</b>
                  </div>
                  <div>
                    <span>预计节省</span>
                    <b>{{ casrPlan.estimatedSavings || 0 }}</b>
                  </div>
                </div>
                <div
                  v-for="option in casrPlan.options || []"
                  :key="option.id"
                  class="casr-option"
                  :class="{ active: casrSelectedOptionId === option.id }"
                  @click="casrSelectedOptionId = option.id"
                >
                  <div class="casr-option-title">
                    <span>{{ option.label }}</span>
                    <b>reward {{ option.reward }}</b>
                  </div>
                  <p>{{ option.explanation }}</p>
                  <div class="casr-option-metrics">
                    <span>增益 {{ option.scoreGain }}</span>
                    <span>成本 {{ option.costPenalty }}</span>
                    <span>风险 {{ option.riskPenalty }}</span>
                    <span>成功率 {{ Math.round((option.successProbability || 0) * 100) }}%</span>
                  </div>
                  <div class="casr-option-actions">
                    <button
                      v-for="action in option.actions || []"
                      :key="`${option.id}-${action.action}`"
                      type="button"
                      :disabled="!!casrLoading"
                      @click.stop="executeCasrAction(option.id, action.action)"
                    >
                      {{ action.label }}
                    </button>
                  </div>
                </div>
              </div>
            </template>
          </section>

          <section class="work-panel release-panel">
            <div class="section-head compact">
              <h2>发布包</h2>
              <select v-model="platformProfile" class="profile-select" aria-label="发布平台">
                <option v-for="item in PLATFORM_PROFILE_OPTIONS" :key="item.value" :value="item.value">
                  {{ item.label }}
                </option>
              </select>
            </div>
            <div class="release-state">
              <div>
                <span>成片</span>
                <b>{{ finalVideoUrl ? '已就绪' : '待合成' }}</b>
              </div>
              <div>
                <span>规格</span>
                <b>{{ currentProfile?.resolution || '1080x1920' }}</b>
              </div>
              <div>
                <span>质检</span>
                <b>{{ issues.length ? `${issues.length} 项待处理` : '可打包' }}</b>
              </div>
            </div>
            <div class="release-actions">
              <button type="button" class="secondary-button" :disabled="!finalVideoUrl" @click="downloadFinalVideo">
                <el-icon><Download /></el-icon>
                下载成片
              </button>
              <button type="button" class="primary-button" :disabled="!!loadingAction" @click="exportPackage">
                <el-icon><Upload /></el-icon>
                生成清单
              </button>
            </div>
            <pre v-if="exportManifest" class="manifest-box">{{ exportManifestText }}</pre>
          </section>
        </div>
      </section>

      <aside class="inspector">
        <template v-if="selectedShot">
          <div class="inspector-head">
            <div>
              <div class="pw-kicker">镜头检查器</div>
              <h2>镜头 {{ selectedShot.shotNo || '-' }}</h2>
            </div>
            <span class="tier-badge">档位 {{ selectedShot.motionTier || 'C' }}</span>
          </div>

          <div class="compare-grid">
            <div class="compare-cell">
              <span>首帧</span>
              <img
                v-if="selectedShot.imageUrl"
                :src="selectedShot.imageUrl"
                :alt="`镜头 ${selectedShot.shotNo || '-'} 首帧`"
                loading="lazy"
                decoding="async"
              />
              <b v-else>待生成</b>
            </div>
            <div class="compare-cell">
              <span>视频</span>
              <video v-if="selectedShot.videoUrl" :src="selectedShot.videoUrl" controls playsinline />
              <img
                v-else-if="selectedShot.imageUrl"
                :src="selectedShot.imageUrl"
                :alt="`镜头 ${selectedShot.shotNo || '-'} 视频替代首帧`"
                loading="lazy"
                decoding="async"
              />
              <b v-else>待生成</b>
            </div>
            <div class="compare-cell">
              <span>尾帧</span>
              <b>待抽帧</b>
            </div>
          </div>

          <div class="inspect-section">
            <h3>镜头状态</h3>
            <div class="status-list">
              <span>{{ selectedShot.status?.firstFrame }}</span>
              <span>{{ selectedShot.status?.video }}</span>
              <span>{{ selectedShot.status?.audio }}</span>
              <span>{{ selectedShot.status?.quality }}</span>
            </div>
          </div>

          <div class="inspect-section">
            <h3>对白与旁白</h3>
            <p>{{ selectedShot.resolvedTts || selectedShot.dialogue || selectedShot.narration || '暂无文本' }}</p>
          </div>

          <div class="inspect-section">
            <h3>修复动作</h3>
            <div class="repair-grid">
              <button type="button" :disabled="!!loadingAction" @click="runRepair(videoRepairAction, [selectedShot.id])">
                重跑视频
              </button>
              <button type="button" :disabled="!!loadingAction" @click="runRepair('useFirstFrameOnly', [selectedShot.id])">
                只用首帧
              </button>
              <button type="button" :disabled="!!loadingAction" @click="runRepair('regenerateFirstFrame', [selectedShot.id])">
                重生首帧
              </button>
              <button type="button" :disabled="!!loadingAction" @click="createSnapshot([selectedShot.id])">
                保存快照
              </button>
            </div>
          </div>

          <div class="inspect-section">
            <h3>资产血缘</h3>
            <dl class="lineage-list">
              <div><dt>任务</dt><dd>{{ selectedShot.lineage?.taskId || '无' }}</dd></div>
              <div><dt>工作流</dt><dd>{{ selectedShot.lineage?.workflow || healthVideoWorkflow || '未记录' }}</dd></div>
              <div><dt>模型</dt><dd>{{ workspace?.health?.videoConfig?.model || '未记录' }}</dd></div>
              <div><dt>Provider</dt><dd>{{ selectedShot.lineage?.videoProvider || workspace?.health?.videoConfig?.provider || '未记录' }}</dd></div>
            </dl>
          </div>

          <div class="inspect-section">
            <h3>历史版本</h3>
            <div v-if="!selectedShot.snapshots?.length" class="panel-empty">暂无快照</div>
            <div v-for="snapshot in selectedShot.snapshots?.slice(0, 5)" :key="snapshot.id" class="snapshot-row">
              <span>{{ assetTypeLabel(snapshot.assetType) }}</span>
              <button type="button" :disabled="!!loadingAction" @click="restoreSnapshot(snapshot.id)">回滚</button>
            </div>
          </div>
        </template>
        <div v-else class="panel-empty">请选择一个镜头</div>

        <div class="inspect-section consistency-section">
          <h3>一致性中心</h3>
          <div class="bible-list">
            <div v-for="item in consistencyItems.slice(0, 6)" :key="item.id" class="bible-row">
              <span>{{ bibleTypeLabel(item.bibleType) }}</span>
              <b>{{ item.title }}</b>
            </div>
          </div>
        </div>
      </aside>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowLeft,
  DocumentChecked,
  Download,
  Finished,
  Microphone,
  Picture,
  Refresh,
  Upload,
  VideoPlay,
} from '@element-plus/icons-vue'
import { productionApi } from '@/api/production'
import { videoApi } from '@/api/video'
import {
  DEFAULT_PLATFORM_PROFILE,
  DEFAULT_PRODUCTION_MODE,
  PLATFORM_PROFILE_OPTIONS,
} from '@/constants/project'

type Mode = 'preview' | 'publish'
type PlatformProfile = 'douyin' | 'hongguo'

const route = useRoute()
const router = useRouter()

const projectId = computed(() => String(route.params.id || ''))
const workspace = ref<any | null>(null)
const loading = ref(false)
const loadingAction = ref('')
const selectedShotId = ref<string | number | null>(null)
const mode = ref<Mode>(DEFAULT_PRODUCTION_MODE as Mode)
const platformProfile = ref<PlatformProfile>(DEFAULT_PLATFORM_PROFILE as PlatformProfile)
const issueSeverityFilter = ref<'all' | 'blocking' | 'warning' | 'info'>('all')
const exportManifest = ref<any | null>(null)
const casrAnalysis = ref<any | null>(null)
const casrPlan = ref<any | null>(null)
const casrLoading = ref('')
const casrSelectedOptionId = ref('')
let refreshTimer: ReturnType<typeof setInterval> | null = null
let workspaceRequestId = 0

const PLATFORM_PROFILE_KEY = 'niren.dashboard.platformProfile'
const PRODUCTION_MODE_KEY = 'niren.dashboard.productionMode'

const projectName = computed(() => workspace.value?.project?.name || '未命名项目')
const stages = computed<any[]>(() => workspace.value?.completion?.stages || [])
const summary = computed(() => workspace.value?.completion?.summary || {})
const shots = computed<any[]>(() => workspace.value?.shots || [])
const activeTasks = computed<any[]>(() => workspace.value?.activeTasks || [])
const issues = computed<any[]>(() => workspace.value?.issues || [])
const filteredIssues = computed<any[]>(() => {
  if (issueSeverityFilter.value === 'all') return issues.value
  return issues.value.filter((issue) => issue.severity === issueSeverityFilter.value)
})
const nextActions = computed<any[]>(() => workspace.value?.nextActions || [])
const finalVideoUrl = computed(() => workspace.value?.finalVideoUrl || '')
const consistencyItems = computed<any[]>(() => workspace.value?.consistency?.items || [])
const currentProfile = computed(() => (workspace.value?.exportProfiles || []).find((item: any) => item.id === platformProfile.value))
const healthVideoWorkflow = computed(() => workspace.value?.health?.videoConfig?.workflowFile || '')
const videoRepairAction = computed(() => (mode.value === 'publish' ? 'switchHunyuan' : 'switchLtx'))
const noVideosReady = computed(() => shots.value.length > 0 && Number(summary.value.videoReady || 0) <= 0)
const casrFailureTypes = computed<string[]>(() => Array.isArray(casrAnalysis.value?.failureTypes) ? casrAnalysis.value.failureTypes : [])
const casrShotDiagnoses = computed<any[]>(() => Array.isArray(casrAnalysis.value?.shotDiagnoses) ? casrAnalysis.value.shotDiagnoses : [])
const casrRecommendedOption = computed(() => casrPlan.value?.recommendedOption || null)
const issueFilterOptions = [
  { label: '全部', value: 'all' as const },
  { label: '阻塞', value: 'blocking' as const },
  { label: '优化', value: 'warning' as const },
]

const selectedShot = computed(() => {
  if (!shots.value.length) return null
  return shots.value.find((shot) => String(shot.id) === String(selectedShotId.value)) || shots.value[0]
})

const primaryAction = computed(() => {
  const action = nextActions.value[0] || {
    id: 'qualityCheck',
    title: '刷新生产线',
    description: '同步当前镜头、任务和质检状态。',
    enabled: true,
    type: 'quality',
  }
  if (action.id === 'retryVideo') {
    return {
      ...action,
      title: mode.value === 'preview' ? '生成预览视频' : '生成发布视频',
      description: mode.value === 'preview'
        ? '使用快测工作流先生成最多 3 个缺失镜头，快速确认节奏与画面方向。'
        : '使用高一致性工作流生成全部缺失镜头，面向平台发布质量。',
    }
  }
  if (action.id === 'generateImages') {
    return {
      ...action,
      title: '补齐参考首帧',
    }
  }
  return action
})

const healthItems = computed(() => {
  const health = workspace.value?.health || {}
  const video = health.videoConfig || {}
  return [
    { key: 'token', status: health.token?.status || 'ok', label: health.token?.label || '登录有效' },
    { key: 'csrf', status: health.csrf?.status || 'skipped', label: health.csrf?.label || 'CSRF 未检测' },
    { key: 'comfyui', status: health.comfyui?.status || 'skipped', label: health.comfyui?.label || 'ComfyUI 未检测' },
    { key: 'ffmpeg', status: health.ffmpeg?.status || 'skipped', label: health.ffmpeg?.label || 'FFmpeg 未检测' },
    { key: 'video', status: video.status || 'degraded', label: video.label || '视频配置未完成' },
  ]
})

const exportManifestText = computed(() => JSON.stringify(exportManifest.value, null, 2))

async function loadWorkspace(silent = false) {
  if (!projectId.value) return
  const requestId = ++workspaceRequestId
  const requestedProjectId = projectId.value
  loading.value = true
  try {
    const res = await productionApi.getWorkspace(requestedProjectId)
    if (requestId !== workspaceRequestId || requestedProjectId !== projectId.value) return
    const data = (res as any).data?.data || {}
    workspace.value = data
    mode.value = data.mode === 'publish' ? 'publish' : mode.value
    if (!selectedShotId.value && Array.isArray(data.shots) && data.shots.length) {
      selectedShotId.value = data.shots[0].id
    }
    if (selectedShotId.value && Array.isArray(data.shots) && !data.shots.some((shot: any) => String(shot.id) === String(selectedShotId.value))) {
      selectedShotId.value = data.shots[0]?.id || null
    }
  } catch (error: any) {
    if (!silent) ElMessage.error(error?.message || '生产线加载失败')
  } finally {
    if (requestId === workspaceRequestId) {
      loading.value = false
    }
  }
}

function resetWorkspaceState() {
  workspace.value = null
  selectedShotId.value = null
  issueSeverityFilter.value = 'all'
  exportManifest.value = null
  casrAnalysis.value = null
  casrPlan.value = null
  casrLoading.value = ''
  casrSelectedOptionId.value = ''
}

function switchMode(nextMode: Mode) {
  mode.value = nextMode
}

function selectShot(shot: any) {
  selectedShotId.value = shot.id
}

function focusIssue(issue: any) {
  if (issue?.shotId) {
    selectedShotId.value = issue.shotId
  }
}

function issueCount(shot: any) {
  return Array.isArray(shot?.issues) ? shot.issues.length : 0
}

function missingShotIds(field: 'imageUrl' | 'videoUrl' | 'audioUrl') {
  return shots.value.filter((shot) => !shot[field]).map((shot) => shot.id)
}

function selectedOrMissing(field: 'imageUrl' | 'videoUrl' | 'audioUrl') {
  const missing = missingShotIds(field)
  return missing.length ? missing : shots.value.map((shot) => shot.id)
}

async function runRepair(action: string, shotIds: Array<string | number> = []) {
  if (!projectId.value || loadingAction.value) return
  loadingAction.value = action
  try {
    const res = await productionApi.repair(projectId.value, {
      action,
      shotIds,
      mode: mode.value,
      workflowPreset: mode.value === 'publish' ? 'hunyuan' : 'ltx',
      platformProfile: platformProfile.value,
    })
    const data = (res as any).data?.data || {}
    if (data.workspace) workspace.value = data.workspace
    const task = data.task
    ElMessage.success(task?.id ? `${task.typeLabel || '任务'}已提交` : '操作已完成')
  } catch (error: any) {
    ElMessage.error(error?.message || '操作失败')
  } finally {
    loadingAction.value = ''
  }
}

function executePrimaryAction() {
  const action = primaryAction.value
  if (!action?.id) return
  if (action.id === 'qualityCheck') return runQualityCheck()
  if (action.id === 'export') return exportPackage()
  if (action.id === 'runEpisodePipeline') return runRepair(action.id, [])
  if (action.type === 'route') return goStoryboard()
  if (action.id === 'retryVideo' || action.id === 'switchLtx' || action.id === 'switchWan' || action.id === 'switchHunyuan') return generateVideos()
  if (action.id === 'generateImages') return generateFirstFrames()
  if (action.id === 'generateAudio') return generateAudio()
  if (action.type === 'repair') return runRepair(action.id, [])
  if (action.id === 'quality') return runQualityCheck()
}

function generateFirstFrames() {
  runRepair('generateImages', selectedOrMissing('imageUrl'))
}

function generateVideos() {
  const ids = selectedOrMissing('videoUrl')
  const limited = mode.value === 'preview' ? ids.slice(0, 3) : ids
  runRepair(mode.value === 'publish' ? 'switchHunyuan' : 'switchLtx', limited)
}

function generateAudio() {
  runRepair('generateAudio', selectedOrMissing('audioUrl'))
}

function composeCurrent() {
  runRepair(mode.value === 'publish' ? 'composePublish' : 'composePreview', shots.value.map((shot) => shot.id))
}

function clearStaleTasks() {
  runRepair('clearStaleTasks')
}

async function runQualityCheck() {
  if (!projectId.value || loadingAction.value) return
  loadingAction.value = 'qualityCheck'
  try {
    const res = await productionApi.runQualityCheck(projectId.value, {})
    const data = (res as any).data?.data || {}
    if (data.workspace) workspace.value = data.workspace
    ElMessage.success(`质检完成，检查 ${data.checkedShots || 0} 个镜头`)
  } catch (error: any) {
    ElMessage.error(error?.message || '质检失败')
  } finally {
    loadingAction.value = ''
  }
}

async function runCasrAnalyze() {
  if (!projectId.value || casrLoading.value) return
  casrLoading.value = 'analyze'
  try {
    const res = await productionApi.analyzeCasr(projectId.value)
    const data = (res as any).data?.data || {}
    casrAnalysis.value = data.analysis || null
    casrPlan.value = null
    casrSelectedOptionId.value = ''
    ElMessage.success('CASR 诊断完成')
  } catch (error: any) {
    ElMessage.error(error?.message || 'CASR 诊断失败')
  } finally {
    casrLoading.value = ''
  }
}

async function runCasrPlan() {
  if (!projectId.value || casrLoading.value) return
  casrLoading.value = 'plan'
  try {
    const res = await productionApi.planCasr(projectId.value)
    const data = (res as any).data?.data || {}
    casrAnalysis.value = data.analysis || null
    casrPlan.value = data.plan || null
    casrSelectedOptionId.value = data.plan?.recommendedOption?.id || data.plan?.options?.[0]?.id || ''
    ElMessage.success('CASR 策略搜索完成')
  } catch (error: any) {
    ElMessage.error(error?.message || 'CASR 策略搜索失败')
  } finally {
    casrLoading.value = ''
  }
}

async function executeCasrAction(optionId: string, actionId: string) {
  if (!projectId.value || casrLoading.value) return
  casrLoading.value = actionId
  try {
    const res = await productionApi.executeCasr(projectId.value, {
      optionId,
      actionIds: [actionId],
    })
    const data = (res as any).data?.data || {}
    casrAnalysis.value = data.analysis || casrAnalysis.value
    casrPlan.value = data.plan || casrPlan.value
    casrSelectedOptionId.value = optionId
    await loadWorkspace(true)
    ElMessage.success('CASR 修复动作已提交')
  } catch (error: any) {
    ElMessage.error(error?.message || 'CASR 修复动作失败')
  } finally {
    casrLoading.value = ''
  }
}

async function createSnapshot(shotIds: Array<string | number>) {
  if (!projectId.value || loadingAction.value) return
  loadingAction.value = 'snapshot'
  try {
    const res = await productionApi.createSnapshot(projectId.value, { shotIds })
    const data = (res as any).data?.data || {}
    ElMessage.success(`已保存 ${data.snapshots?.length || 0} 个快照`)
    await loadWorkspace(true)
  } catch (error: any) {
    ElMessage.error(error?.message || '保存快照失败')
  } finally {
    loadingAction.value = ''
  }
}

async function restoreSnapshot(snapshotId: string | number) {
  if (!projectId.value || loadingAction.value) return
  loadingAction.value = 'restore'
  try {
    const res = await productionApi.restoreSnapshot(projectId.value, snapshotId)
    const data = (res as any).data?.data || {}
    if (data.workspace) workspace.value = data.workspace
    ElMessage.success('已回滚到历史版本')
  } catch (error: any) {
    ElMessage.error(error?.message || '回滚失败')
  } finally {
    loadingAction.value = ''
  }
}

async function exportPackage() {
  if (!projectId.value || loadingAction.value) return
  loadingAction.value = 'export'
  try {
    const res = await productionApi.exportPackage(projectId.value, { platformProfile: platformProfile.value })
    exportManifest.value = (res as any).data?.data || null
    ElMessage.success(exportManifest.value?.ready ? '发布清单已就绪' : '发布清单已生成，仍有待处理项')
  } catch (error: any) {
    ElMessage.error(error?.message || '发布包生成失败')
  } finally {
    loadingAction.value = ''
  }
}

function downloadFinalVideo() {
  if (!finalVideoUrl.value) return
  const a = document.createElement('a')
  a.href = videoApi.getDownloadUrl(projectId.value)
  a.download = `niren_drama_${projectId.value}.mp4`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
}

function visibleIssueActions(issue: any) {
  const actions = Array.isArray(issue.actions) ? issue.actions : []
  const seen = new Set<string>()
  return actions.filter((action: any) => {
    if (!action?.id || seen.has(action.id)) return false
    seen.add(action.id)
    return true
  }).slice(0, 4)
}

function severityLabel(severity: string) {
  return ({
    blocking: '阻塞发布',
    warning: '建议优化',
    info: '可忽略',
  } as Record<string, string>)[severity] || '待确认'
}

function failureTypeLabel(type?: string) {
  if (!type) return ''
  return ({
    missing_first_frame: '缺首帧',
    missing_media: '素材缺失',
    identity_drift_risk: '身份漂移',
    wardrobe_drift_risk: '服装漂移',
    scene_drift_risk: '场景漂移',
    motion_failure: '运动失败',
    black_frame: '黑屏',
    frozen_frame: '冻结',
    duration_out_of_range: '时长异常',
    wrong_aspect_ratio: '比例异常',
    video_task_failed: '视频失败',
    stale_task: '陈旧任务',
  } as Record<string, string>)[type] || type
}

function assetTypeLabel(type: string) {
  return ({
    first_frame: '首帧',
    video: '视频',
    audio: '配音',
    subtitle: '字幕',
    tts: '口播',
  } as Record<string, string>)[type] || type
}

function bibleTypeLabel(type: string) {
  return ({
    character: '角色',
    scene: '场景',
    style: '风格',
  } as Record<string, string>)[type] || type
}

function goBack() {
  router.push({ path: `/projects/${projectId.value}/immersive`, query: { episode: String(route.query.episode || '1') } })
}

function goStoryboard() {
  router.push({ path: `/projects/${projectId.value}/storyboard`, query: { episode: String(route.query.episode || '1') } })
}

onMounted(() => {
  try {
    const savedPlatform = sessionStorage.getItem(PLATFORM_PROFILE_KEY)
    if (PLATFORM_PROFILE_OPTIONS.some((item) => item.value === savedPlatform)) {
      platformProfile.value = savedPlatform as PlatformProfile
    }
    const savedMode = sessionStorage.getItem(PRODUCTION_MODE_KEY)
    if (savedMode === 'preview' || savedMode === 'publish') {
      mode.value = savedMode
    }
  } catch { /* ignore */ }
  loadWorkspace()
  refreshTimer = setInterval(() => {
    if (activeTasks.value.length) loadWorkspace(true)
  }, 7000)
})

watch(projectId, (nextId, prevId) => {
  if (!nextId || nextId === prevId) return
  resetWorkspaceState()
  void loadWorkspace()
})

watch([mode, platformProfile], ([nextMode, nextPlatform]) => {
  try {
    sessionStorage.setItem(PRODUCTION_MODE_KEY, nextMode)
    sessionStorage.setItem(PLATFORM_PROFILE_KEY, nextPlatform)
  } catch { /* ignore */ }
})

onUnmounted(() => {
  if (refreshTimer) clearInterval(refreshTimer)
})
</script>

<style scoped>
.production-workbench {
  min-height: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--page-environment);
  color: var(--text-primary);
}

.pw-header {
  height: 58px;
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 0 16px;
  border-bottom: 1px solid var(--border);
  background: var(--bg-card);
}

.pw-title-block,
.pw-header-actions,
.overview-actions,
.section-actions,
.release-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.pw-title-copy {
  min-width: 0;
}

.pw-title-copy h1,
.section-head h2,
.inspector-head h2 {
  margin: 0;
  font-size: 16px;
  line-height: 1.2;
  font-weight: 700;
}

.pw-kicker,
.overview-label {
  font-size: 11px;
  color: var(--text-muted);
  line-height: 1.3;
}

.mode-switch {
  display: inline-flex;
  padding: 3px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg-muted);
}

.mode-switch button {
  border: 0;
  padding: 7px 12px;
  border-radius: 6px;
  background: transparent;
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
}

.mode-switch button.active {
  background: var(--bg-card);
  color: var(--primary);
  box-shadow: var(--shadow-sm);
}

.icon-button,
.text-button,
.secondary-button,
.primary-button,
.link-button {
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg-card);
  color: var(--text-primary);
  cursor: pointer;
  font-size: 12px;
  font-weight: 700;
}

.icon-button {
  width: 34px;
  height: 34px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.text-button,
.secondary-button,
.primary-button {
  height: 34px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 0 12px;
}

.primary-button {
  border-color: var(--primary);
  background: var(--primary);
  color: #fff;
}

.secondary-button:hover,
.text-button:hover,
.icon-button:hover,
.link-button:hover {
  border-color: var(--primary);
  color: var(--primary);
}

button:disabled {
  cursor: not-allowed;
  opacity: 0.48;
}

.pw-layout {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 230px minmax(0, 1fr) 340px;
  gap: 12px;
  padding: 12px;
}

.pw-layout.no-stages {
  grid-template-columns: minmax(0, 1fr) 340px;
}

.stage-rail,
.center-pane,
.inspector {
  min-height: 0;
}

.stage-rail {
  display: flex;
  flex-direction: column;
  gap: 8px;
  overflow: auto;
}

.stage-item,
.overview-band,
.timeline-section,
.work-panel,
.inspector {
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg-card);
}

.stage-item {
  padding: 12px;
}

.stage-topline,
.task-title,
.section-head,
.inspector-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.stage-topline span {
  font-size: 13px;
  font-weight: 700;
}

.stage-topline b,
.stage-status {
  font-size: 11px;
  color: var(--text-muted);
}

.stage-track,
.task-track {
  height: 5px;
  overflow: hidden;
  border-radius: 999px;
  background: var(--bg-muted);
}

.stage-track {
  margin: 9px 0 7px;
}

.stage-track span,
.task-track span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--primary);
}

.stage-item.is-review .stage-track span,
.issue-row.is-warning {
  border-color: rgba(245, 158, 11, 0.45);
}

.stage-item.is-review .stage-track span {
  background: #f59e0b;
}

.center-pane {
  min-width: 0;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.overview-band {
  padding: 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.overview-main {
  min-width: 0;
}

.overview-title {
  margin-top: 2px;
  font-size: 18px;
  font-weight: 800;
}

.overview-main p,
.section-head p,
.task-row p,
.issue-copy p,
.inspect-section p {
  margin: 4px 0 0;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.5;
}

.health-row {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.health-pill {
  min-height: 30px;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 0 10px;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: var(--bg-card);
  color: var(--text-secondary);
  font-size: 12px;
}

.health-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--text-muted);
}

.health-pill.is-ok .health-dot {
  background: #22c55e;
}

.health-pill.is-degraded .health-dot,
.health-pill.is-skipped .health-dot {
  background: #f59e0b;
}

.health-pill.is-down .health-dot {
  background: #ef4444;
}

.timeline-section,
.work-panel,
.inspector {
  padding: 14px;
}

.section-head {
  margin-bottom: 12px;
}

.section-head.compact {
  margin-bottom: 10px;
}

.section-head.compact h2 {
  font-size: 14px;
}

.inline-guide {
  min-height: 78px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 14px;
  border: 1px solid rgba(99, 102, 241, 0.28);
  border-radius: 8px;
  background: rgba(99, 102, 241, 0.08);
}

.inline-guide b {
  display: block;
  font-size: 14px;
}

.inline-guide p {
  margin: 4px 0 0;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.5;
}

.shot-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(132px, 1fr));
  gap: 10px;
}

.inline-guide + .shot-grid {
  margin-top: 10px;
}

.shot-card {
  min-width: 0;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 0;
  overflow: hidden;
  background: var(--bg-card);
  color: var(--text-primary);
  text-align: left;
  cursor: pointer;
}

.shot-card.active {
  border-color: var(--primary);
  box-shadow: 0 0 0 1px var(--primary);
}

.shot-card.has-issue {
  border-color: rgba(239, 68, 68, 0.45);
}

.shot-thumb {
  width: 100%;
  aspect-ratio: 9 / 16;
  max-height: 220px;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: #050505;
  color: rgba(255, 255, 255, 0.7);
  font-size: 12px;
}

.shot-thumb img,
.shot-thumb video {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.shot-meta {
  padding: 9px 10px 10px;
}

.shot-number {
  font-size: 13px;
  font-weight: 800;
}

.shot-status-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 5px;
  color: var(--text-secondary);
  font-size: 11px;
}

.shot-status-line b {
  min-width: 18px;
  height: 18px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  background: #ef4444;
  color: #fff;
}

.lower-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.casr-panel {
  grid-column: span 2;
}

.casr-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.casr-score-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}

.casr-score-grid div,
.casr-plan-head div {
  padding: 10px;
  border-radius: 8px;
  background: var(--bg-muted);
}

.casr-score-grid span,
.casr-plan-head span {
  display: block;
  color: var(--text-muted);
  font-size: 11px;
}

.casr-score-grid b,
.casr-plan-head b {
  display: block;
  margin-top: 4px;
  font-size: 18px;
  line-height: 1.15;
}

.casr-chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}

.casr-chip-row span {
  padding: 4px 8px;
  border-radius: 999px;
  background: rgba(37, 99, 235, 0.1);
  color: #2563eb;
  font-size: 11px;
  font-weight: 800;
}

.casr-shot-graph {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(92px, 1fr));
  gap: 8px;
  margin-top: 12px;
}

.casr-shot-graph button {
  min-height: 82px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg-card);
  color: var(--text-primary);
  cursor: pointer;
  text-align: left;
  padding: 9px;
}

.casr-shot-graph button.is-blocking {
  border-color: rgba(239, 68, 68, 0.5);
}

.casr-shot-graph button.is-warning {
  border-color: rgba(245, 158, 11, 0.55);
}

.casr-shot-graph span,
.casr-shot-graph small {
  display: block;
  color: var(--text-secondary);
  font-size: 11px;
}

.casr-shot-graph b {
  display: block;
  margin: 6px 0 4px;
  font-size: 20px;
}

.casr-policy {
  margin-top: 12px;
}

.casr-plan-head {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 120px;
  gap: 8px;
  margin-bottom: 8px;
}

.casr-plan-head b {
  font-size: 13px;
}

.casr-option {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 10px;
  background: var(--bg-muted);
  cursor: pointer;
}

.casr-option + .casr-option {
  margin-top: 8px;
}

.casr-option.active {
  border-color: var(--primary);
  box-shadow: 0 0 0 1px var(--primary);
}

.casr-option-title,
.casr-option-metrics,
.casr-option-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.casr-option-title {
  justify-content: space-between;
}

.casr-option-title span {
  font-size: 13px;
  font-weight: 800;
}

.casr-option-title b,
.casr-option-metrics span {
  color: var(--text-muted);
  font-size: 11px;
}

.casr-option p {
  margin: 6px 0;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.5;
}

.casr-option-actions {
  margin-top: 8px;
}

.casr-option-actions button {
  min-height: 28px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--bg-card);
  color: var(--text-primary);
  padding: 0 9px;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
}

.panel-empty,
.empty-state {
  min-height: 72px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-muted);
  font-size: 12px;
  border: 1px dashed var(--border);
  border-radius: 8px;
  background: var(--bg-muted);
}

.empty-state--action {
  flex-direction: column;
  gap: 10px;
}

.task-row,
.issue-row,
.snapshot-row,
.bible-row {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 10px;
  background: var(--bg-muted);
}

.task-row + .task-row,
.issue-row + .issue-row,
.snapshot-row + .snapshot-row,
.bible-row + .bible-row {
  margin-top: 8px;
}

.task-title span,
.task-title b {
  font-size: 12px;
}

.task-title b {
  color: var(--primary);
}

.task-track {
  margin-top: 8px;
}

.issue-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px;
  align-items: center;
  cursor: pointer;
}

.issue-title-line {
  display: flex;
  align-items: center;
  gap: 8px;
  justify-content: space-between;
}

.issue-title-line span {
  font-size: 13px;
  font-weight: 800;
}

.issue-title-line b {
  flex-shrink: 0;
  padding: 2px 7px;
  border-radius: 999px;
  background: rgba(100, 116, 139, 0.16);
  color: var(--text-secondary);
  font-size: 11px;
}

.issue-row.is-blocking .issue-title-line b {
  background: rgba(239, 68, 68, 0.14);
  color: var(--color-danger);
}

.issue-row.is-warning .issue-title-line b {
  background: rgba(245, 158, 11, 0.14);
  color: var(--color-warning);
}

.issue-impact {
  color: var(--text-primary) !important;
}

.issue-copy small {
  display: block;
  margin-top: 6px;
  color: var(--text-muted);
  font-size: 11px;
  line-height: 1.4;
}

.issue-tools {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.issue-filter {
  display: inline-flex;
  padding: 2px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg-muted);
}

.issue-filter button {
  min-height: 26px;
  border: 0;
  border-radius: 6px;
  padding: 0 8px;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  font-size: 11px;
  font-weight: 800;
}

.issue-filter button.active {
  background: var(--bg-card);
  color: var(--primary);
  box-shadow: var(--shadow-sm);
}

.issue-actions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.issue-actions button,
.repair-grid button,
.snapshot-row button,
.link-button {
  min-height: 28px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--bg-card);
  color: var(--text-primary);
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
}

.issue-actions button,
.snapshot-row button,
.link-button {
  padding: 0 9px;
}

.release-state {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}

.release-state div {
  padding: 10px;
  border-radius: 8px;
  background: var(--bg-muted);
}

.release-state span,
.lineage-list dt,
.bible-row span {
  display: block;
  color: var(--text-muted);
  font-size: 11px;
}

.release-state b,
.lineage-list dd,
.bible-row b {
  margin-top: 4px;
  display: block;
  font-size: 12px;
}

.release-actions {
  margin-top: 12px;
}

.profile-select {
  height: 30px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--bg-card);
  color: var(--text-primary);
  font-size: 12px;
}

.manifest-box {
  max-height: 180px;
  overflow: auto;
  margin: 12px 0 0;
  padding: 10px;
  border-radius: 8px;
  background: #0b1020;
  color: #dbeafe;
  font-size: 11px;
  line-height: 1.5;
}

.inspector {
  overflow: auto;
}

.tier-badge {
  padding: 4px 8px;
  border-radius: 999px;
  background: var(--bg-muted);
  color: var(--text-secondary);
  font-size: 11px;
  font-weight: 800;
}

.compare-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin-top: 14px;
}

.compare-cell {
  position: relative;
  aspect-ratio: 9 / 16;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: #050505;
  display: flex;
  align-items: center;
  justify-content: center;
  color: rgba(255, 255, 255, 0.72);
  font-size: 12px;
}

.compare-cell span {
  position: absolute;
  left: 6px;
  top: 6px;
  z-index: 1;
  padding: 2px 6px;
  border-radius: 6px;
  background: rgba(0, 0, 0, 0.55);
  color: #fff;
  font-size: 10px;
}

.compare-cell img,
.compare-cell video {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.inspect-section {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid var(--border);
}

.inspect-section h3 {
  margin: 0 0 10px;
  font-size: 13px;
}

.status-list,
.repair-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.status-list span {
  padding: 8px;
  border-radius: 8px;
  background: var(--bg-muted);
  color: var(--text-secondary);
  font-size: 12px;
}

.repair-grid button {
  padding: 8px;
}

.lineage-list {
  margin: 0;
  display: grid;
  grid-template-columns: 1fr;
  gap: 8px;
}

.lineage-list div,
.snapshot-row {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: center;
}

.lineage-list dd {
  margin: 0;
  word-break: break-all;
}

.bible-list {
  display: grid;
  gap: 8px;
}

@media (max-width: 1120px) {
  .pw-layout {
    grid-template-columns: 190px minmax(0, 1fr);
  }

  .inspector {
    grid-column: 1 / -1;
  }

  .lower-grid {
    grid-template-columns: 1fr;
  }

  .casr-panel {
    grid-column: auto;
  }
}

@media (max-width: 860px) {
  .pw-header {
    height: auto;
    min-height: 58px;
    align-items: flex-start;
    flex-wrap: wrap;
    padding: 10px 12px;
  }

  .pw-layout {
    display: flex;
    flex-direction: column;
  }

  .stage-rail {
    flex-direction: row;
    overflow-x: auto;
  }

  .stage-item {
    min-width: 150px;
  }

  .overview-band,
  .section-head,
  .issue-row,
  .inline-guide {
    align-items: stretch;
    flex-direction: column;
    display: flex;
  }

  .section-actions,
  .issue-tools,
  .overview-actions {
    flex-wrap: wrap;
  }

  .shot-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .casr-score-grid,
  .casr-plan-head {
    grid-template-columns: 1fr 1fr;
  }
}
.production-workbench {
  min-height: 100%;
  background: var(--page-environment);
  color: #f7fbff;
}

.pw-header {
  height: 58px;
  border-bottom: 1px solid rgba(150, 190, 255, 0.14);
  background: rgba(11, 20, 27, 0.68);
  backdrop-filter: blur(28px) saturate(145%);
}

.pw-kicker {
  color: var(--primary);
}

.pw-title-copy h1,
.section-head h2,
.inspector-head h2 {
  color: #f7fbff;
}

.icon-button,
.text-button,
.secondary-button,
.mode-switch,
.profile-select,
.issue-filter,
.stage-item,
.overview-band,
.work-panel,
.inspector,
.health-pill,
.shot-card,
.compare-cell,
.inspect-section {
  border: 1px solid rgba(150, 190, 255, 0.16);
  border-radius: 8px;
  background: var(--surface-panel);
  color: #dbe8ff;
  backdrop-filter: blur(var(--glass-blur)) saturate(145%);
}

.primary-button {
  border: 0;
  border-radius: 8px;
  background: linear-gradient(100deg, #f7fbff, var(--primary), var(--secondary));
  color: #03101d;
  box-shadow: var(--shadow-primary);
}

.mode-switch {
  padding: 4px;
}

.mode-switch button {
  border-radius: 6px;
  color: #9aa8bd;
}

.mode-switch button.active {
  background: rgba(24, 216, 255, 0.16);
  color: #fff;
}

.pw-layout {
  gap: 12px;
  padding: 12px;
}

.stage-rail {
  gap: 10px;
}

.stage-item {
  box-shadow: none;
}

.stage-track,
.task-track {
  background: rgba(255, 255, 255, 0.08);
}

.stage-track span,
.task-track span {
  background: linear-gradient(90deg, var(--primary), var(--secondary));
  box-shadow: 0 0 18px rgba(103, 232, 249, 0.24);
}

.overview-band {
  border-color: rgba(103, 232, 249, 0.22);
}

.overview-label,
.link-button,
.section-head p,
.panel-empty,
.issue-row small,
.task-row p {
  color: #9aa8bd;
}

.overview-title {
  color: #f7fbff;
}

.health-pill.is-ok,
.health-pill.is-ready,
.health-pill.is-success {
  color: #40d28f;
}

.timeline-section {
  border: 1px solid rgba(150, 190, 255, 0.16);
  border-radius: 8px;
  background: var(--glass-fill);
  backdrop-filter: blur(var(--glass-blur)) saturate(145%);
}

.shot-grid {
  gap: 10px;
}

.shot-card {
  overflow: hidden;
  transition: border-color 0.18s, transform 0.18s;
}

.shot-card:hover,
.shot-card.active {
  border-color: rgba(103, 232, 249, 0.3);
  transform: translateY(-2px);
}

.shot-thumb {
  background:
    linear-gradient(rgba(255, 255, 255, 0.035) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.035) 1px, transparent 1px),
    rgba(255, 255, 255, 0.04);
  background-size: 28px 28px;
}

.shot-number {
  color: #f7fbff;
}

.shot-status-line,
.release-state span,
.lineage-list dt {
  color: #9aa8bd;
}

.lower-grid {
  gap: 12px;
}

.task-row,
.issue-row,
.casr-option,
.snapshot-row,
.bible-row {
  border-color: rgba(150, 190, 255, 0.12);
  background: rgba(255, 255, 255, 0.04);
}

.issue-row.is-blocking {
  border-color: rgba(255, 111, 134, 0.32);
}

.issue-row.is-warning {
  border-color: rgba(242, 191, 102, 0.32);
}

.inspector {
  box-shadow: 0 18px 54px rgba(0, 0, 0, 0.3);
}

.tier-badge,
.casr-chip-row span {
  border-color: rgba(139, 92, 246, 0.28);
  background: rgba(139, 92, 246, 0.16);
  color: #c4b5fd;
}

.compare-cell {
  overflow: hidden;
}

.compare-cell span,
.inspect-section h3 {
  color: var(--primary);
}

.repair-grid button,
.casr-option-actions button {
  border: 1px solid rgba(150, 190, 255, 0.16);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.045);
  color: #dbe8ff;
}

.manifest-box {
  border-color: rgba(150, 190, 255, 0.14);
  background: rgba(3, 7, 15, 0.6);
  color: #dbe8ff;
}
</style>
