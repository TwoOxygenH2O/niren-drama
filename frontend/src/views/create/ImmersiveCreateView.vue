<template>
  <div class="immersive-root">
    <header class="immersive-top">
      <div class="immersive-top-left">
      <button type="button" class="top-logo" title="返回剧集列表" aria-label="返回剧集列表" @click="goProjectEpisodes">
        <svg width="26" height="26" viewBox="0 0 32 32" fill="none" aria-hidden="true">
          <path
            d="M8 6c0-1.1.9-2 2-2h6a6 6 0 016 6v4a4 4 0 01-4 4h-4a2 2 0 00-2 2v6a2 2 0 01-2 2H6a2 2 0 01-2-2V18c0-2.2 1.8-4 4-4h4a2 2 0 002-2V8a2 2 0 00-2-2H8z"
            stroke="white"
            stroke-width="1.6"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
          <path
            d="M18 14h4a6 6 0 016 6v6a2 2 0 01-2 2h-4a2 2 0 01-2-2v-4a4 4 0 00-4-4h-2"
            stroke="white"
            stroke-width="1.6"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
        </svg>
      </button>
      <span v-if="project?.name" class="top-project-title">{{ project.name }}</span>
      </div>
      <div class="top-right">
        <button
          v-if="hasAnyShotVideo"
          type="button"
          class="top-compose-btn"
          @click="goPreview"
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
            <polygon points="5,3 19,12 5,21" />
          </svg>
          成片预览
        </button>
        <button type="button" class="vip-link" @click="noopVip">开通会员</button>
        <button type="button" class="icon-btn" title="通知" aria-label="通知" @click="noopBell">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
            <path d="M18 8a6 6 0 10-12 0c0 7-3 7-3 7h18s-3 0-3-7M13.73 21a2 2 0 01-3.46 0" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </button>
      </div>
    </header>

    <div class="immersive-body" :class="{ 'has-plan': showPlanPanel }">
      <aside class="episode-rail" aria-label="剧集">
        <span class="episode-rail-label">剧集</span>
        <div class="episode-pills-scroll">
          <div class="episode-pills">
            <button
              v-for="ep in episodeDisplay"
              :key="ep"
              type="button"
              class="episode-dot"
              :class="{ active: ep === activeEpisode }"
              :aria-label="`切换到第 ${ep} 集`"
              @click="activeEpisode = ep"
            >
              {{ String(ep).padStart(2, '0') }}
            </button>
          </div>
        </div>
        <button type="button" class="episode-add" title="新增剧集" aria-label="新增剧集" @click="openAddEpisodeDialog">
          +
        </button>
      </aside>

      <main class="immersive-main">
        <div ref="scrollRef" class="chat-scroll">
          <div v-if="userPrompt" class="msg-user-wrap">
            <div class="bubble-user">{{ userPrompt }}</div>
          </div>

          <div v-if="activeEpisode > 1" class="continuation-block">
            <h3 class="continuation-title">继续策划下一集</h3>
            <div class="continuation-seko">
              <span class="ai-brand small"><span class="ai-brand-dot" /> 泥人</span>
              <p class="continuation-lead">提炼前面故事的情节走向</p>
              <p class="continuation-desc">
                承接上一集的悬念与人物状态，在下方说明本集开场、冲突或反转；也可让我先梳理前几集的伏笔与节奏。
              </p>
            </div>
          </div>

          <div v-if="generating" class="ai-status-row">
            <span class="ai-brand">
              <span class="ai-brand-dot" />
              泥人
            </span>
            <span class="ai-status">
              <span class="spin" aria-hidden="true" />
              {{ outlineContent ? '正在流式输出…' : '策划剧本大纲' }}
            </span>
            <p v-if="!outlineContent" class="ttft-hint">
              正在等待模型首包：会先写「项目通用信息」，大模型首字通常需数秒至几十秒，请稍候。
            </p>
          </div>

          <div v-if="outlineContent" class="outline-card">
            <pre v-if="generating" class="outline-plain">{{ outlinePlainDisplay }}</pre>
            <div v-else class="outline-md" v-html="outlineHtml" />
            <div v-if="showOutlineConfirmActions" class="outline-card-actions step-confirm-actions">
              <button
                type="button"
                class="btn-confirm-step"
                :disabled="outlineSaving || scriptWorkflowLoading"
                @click="confirmOutline"
              >
                确认大纲
              </button>
            </div>
          </div>

          <div v-for="(m, i) in chatTail" :key="i" class="chat-extra" :class="m.role">
            <template v-if="m.role === 'user'">
              <div class="bubble-user">{{ m.text }}</div>
            </template>
            <template v-else>
              <div class="ai-line">
                <span class="ai-brand small"><span class="ai-brand-dot" /> 泥人</span>
                <p class="ai-text">{{ m.text }}</p>
              </div>
            </template>
          </div>

          <!-- 角色确认按钮 -->
          <div v-if="charactersReady && !charactersConfirmed" class="chat-action-row step-confirm-actions">
            <button type="button" class="btn-confirm-step" @click="() => confirmCharacters()">
              确认角色，生成剧本
            </button>
          </div>

          <!-- 剧本确认按钮 -->
          <div v-if="scriptReady && !scriptConfirmed" class="chat-action-row step-confirm-actions">
            <button type="button" class="btn-confirm-step" @click="confirmScript">
              确认剧本
            </button>
          </div>

          <div v-if="streamError" class="stream-err">{{ streamError }}</div>
        </div>

        <div class="composer-bottom">
          <div class="composer-inner">
            <button type="button" class="attach-btn" title="附件" aria-label="附件" @click="noopAttach">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
                <path d="M21.44 11.05l-9.19 9.19a6 6 0 01-8.49-8.49l9.19-9.19a4 4 0 015.66 5.66l-9.2 9.19a2 2 0 01-2.83-2.83l8.49-8.48" />
              </svg>
            </button>
            <textarea
              v-model="bottomInput"
              class="bottom-textarea"
              :placeholder="composerPlaceholder"
              rows="1"
              @keydown="onBottomKeydown"
            />
            <button
              type="button"
              class="send-round"
              :disabled="
                bottomSending ||
                outlineSaving ||
                scriptWorkflowLoading ||
                (workflowPhase === 'outline' && !outlineContent.trim())
              "
              title="发送（Cmd/Ctrl + Enter）"
              aria-label="发送"
              @click="sendFollowUp"
            >
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                <line x1="12" y1="19" x2="12" y2="5" />
                <polyline points="5 12 12 5 19 12" />
              </svg>
            </button>
          </div>
        </div>
      </main>

      <aside v-if="showPlanPanel" class="immersive-plan" :aria-label="`第${activeEpisode}集策划`">
        <div class="plan-scroll">
          <header class="plan-head">
            <span class="plan-ep-badge">第 {{ String(activeEpisode).padStart(2, '0') }} 集</span>
            <h2 class="plan-title">{{ activePlanScript?.title || '—' }}</h2>
            <p class="plan-ai-note">内容由 AI 生成</p>
            <button type="button" class="plan-close-btn" title="收起策划栏" aria-label="收起策划栏" @click="workflowPhase = 'outline'">✕</button>
          </header>

          <div v-if="scriptWorkflowLoading" class="plan-loading">
            <span class="spin plan-spin" aria-hidden="true" />
            <p>{{ scriptWorkflowPhaseText }}</p>
          </div>

          <template v-else>
            <section class="plan-block">
              <h3 class="plan-block-title">梗概</h3>
              <p class="plan-block-body">{{ activePlanScript?.summary || '暂无梗概，可在剧本页补充。' }}</p>
            </section>

            <section class="plan-block">
              <h3 class="plan-block-title">主体列表</h3>
              <p v-if="!planCharacters.length" class="plan-muted">暂无主体，大纲保存后将同步角色库。</p>
              <ul v-else class="plan-subject-list">
                <li v-for="c in planCharacters" :key="c.id" class="plan-subject-item" @click="openCharGallery(c)">
                  <div class="plan-subject-avatar">
                    <img v-if="c.imageUrl" :src="c.imageUrl" :alt="c.name" />
                    <span v-else-if="portraitRefreshing" class="plan-avatar-ph plan-avatar-ph--loading">生成中</span>
                    <span v-else class="plan-avatar-ph">·</span>
                  </div>
                  <div class="plan-subject-meta">
                    <span class="plan-subject-name">{{ c.name }}</span>
                    <span v-if="c.appearance" class="plan-subject-desc">{{ c.appearance }}</span>
                  </div>
                </li>
              </ul>
            </section>

            <section class="plan-block">
              <h3 class="plan-block-title">分镜剧本</h3>
              <p v-if="!episodeScriptBody.trim()" class="plan-muted">
                暂无本集剧本正文；完成角色确认后自动生成。
              </p>
              <pre v-else class="plan-script-body">{{ episodeScriptBody }}</pre>
            </section>

            <!-- 视频工作台入口卡片（项目有成片时显示） -->
            <div v-if="hasProjectVideo" class="video-workbench-entry" @click="goVideoWorkbench">
              <div class="vwe-glow" />
              <div class="vwe-card">
                <div class="vwe-icon-ring">
                  <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                    <polygon points="5,3 19,12 5,21" fill="currentColor" />
                  </svg>
                </div>
                <div class="vwe-body">
                  <h4 class="vwe-title">成片已就绪</h4>
                  <p class="vwe-desc">进入视频工作台，逐镜查看与微调</p>
                </div>
                <span class="vwe-arrow" aria-hidden="true">
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
                    <polyline points="9,18 15,12 9,6" />
                  </svg>
                </span>
              </div>
            </div>
          </template>
        </div>

        <div v-if="showGenVideoFab && !scriptWorkflowLoading" class="plan-actions plan-actions--dock">
          <p v-if="episodeStoryboardGenerating" class="plan-action-hint">正在拆解本集分镜并准备视频镜头，请稍候…</p>
          <p v-else-if="episodeStoryboardReady" class="plan-action-hint plan-action-hint--ok">本集分镜已就绪；点击下方将为每个分镜生成视频镜头。</p>
          <p v-else-if="episodeStoryboardErr" class="plan-action-hint plan-action-hint--err">{{ episodeStoryboardErr }}</p>
          <p v-else class="plan-action-hint">等待分镜任务…</p>
          <button
            type="button"
            class="btn-plan-prompts"
            :disabled="!activePlanScript?.id || !episodeScriptBody.trim()"
            @click="videoPromptsOpen = true"
          >
            生成视频提示词
          </button>
          <button
            type="button"
            class="btn-plan-video"
            :disabled="primaryVideoDisabled"
            @click="onPrimaryVideoAction"
          >
            <span class="btn-plan-video-ico" aria-hidden="true">✦</span>
            {{ primaryVideoLabel }}
          </button>
          <el-popover
            v-if="!hasProjectVideo && episodeStoryboardReady && episodeShots.length > 0"
            placement="top"
            :width="280"
            trigger="click"
            :show-arrow="false"
            popper-class="shot-select-popover"
          >
            <template #reference>
              <button type="button" class="btn-shot-select" aria-label="选择生成镜头" :class="{ 'has-selection': selectedShotIds.length > 0 && !allSelected }">
                {{ shotSelectLabel }}
                <span class="btn-shot-select-arrow">▾</span>
              </button>
            </template>
            <div class="shot-select-panel">
              <div class="shot-select-header">
                <button type="button" class="shot-select-all-btn" @click="toggleSelectAll">
                  {{ allSelected ? '取消全选' : '全选' }}
                </button>
                <span class="shot-select-count">{{ selectedShotIds.length }} / {{ episodeShots.length }}</span>
              </div>
              <el-checkbox-group v-model="selectedShotIds" class="shot-select-list">
                <el-checkbox
                  v-for="shot in episodeShots"
                  :key="shot.id"
                  :value="String(shot.id)"
                  class="shot-select-item"
                >
                  <span class="shot-select-item-no">镜头 {{ shot.shotNo }}</span>
                  <span v-if="shot.videoUrl" class="shot-select-item-status shot-select-item-status--done">已有视频</span>
                  <span v-else class="shot-select-item-status">动态</span>
                </el-checkbox>
              </el-checkbox-group>
            </div>
          </el-popover>
          <div v-if="mediaSubmitLoading && mediaTaskMessage" class="plan-video-progress">
            <span class="plan-video-progress-text">{{ mediaTaskMessage }}{{ mediaTaskProgress > 0 ? ` ${mediaTaskProgress}%` : '' }}</span>
            <div class="plan-video-progress-bar" :style="{ width: mediaTaskProgress + '%' }" />
          </div>
        </div>
      </aside>
    </div>

    <el-dialog
      v-model="addEpisodeDialogVisible"
      title="选择关联剧本？"
      width="380px"
      align-center
      append-to-body
      destroy-on-close
      class="immersive-episode-dialog"
      @opened="onAddEpisodeDialogOpen"
    >
      <p class="ep-dialog-desc">当前选中集已关联的剧本将作为下一集的承接参考；确认后将扩展项目总集数。</p>
      <div class="ep-dialog-ref">
        <template v-if="dialogReferenceScript">
          <div class="ep-dialog-ref-title">
            第 {{ activeEpisode }} 集：{{ dialogReferenceScript.title || '未命名' }}
          </div>
          <div class="ep-dialog-ref-meta">
            <span>{{ formatScriptDate(dialogReferenceScript) }}</span>
            <span class="ep-dialog-ver">V1</span>
          </div>
        </template>
        <template v-else>
          <div class="ep-dialog-empty">
            当前集暂无已入库剧本，仍可扩展总集数后，在「剧本生成」撰写该集。
          </div>
        </template>
      </div>
      <template #footer>
        <div class="ep-dialog-footer-inner">
          <span class="ep-dialog-footer-hint">项目总集数 +1，并选中最新一集</span>
          <div class="ep-dialog-footer-btns">
            <button type="button" class="btn-ep-cancel" @click="addEpisodeDialogVisible = false">取消</button>
            <button type="button" class="btn-ep-primary" :disabled="creatingNextEpisode" @click="createNextEpisode">
              {{ creatingNextEpisode ? '处理中…' : '创建下一集' }}
            </button>
          </div>
        </div>
      </template>
    </el-dialog>

    <VideoPromptsExportDialog
      v-model="videoPromptsOpen"
      :project-id="projectId"
      :episode-no="activeEpisode"
      :script-id="activePlanScript?.id != null ? Number(activePlanScript.id) : null"
      :project="project"
    />

    <!-- 角色图片画廊 -->
    <el-dialog v-model="charGalleryVisible" :title="charGalleryChar?.name + ' — 角色形象'" width="720px" destroy-on-close append-to-body>
      <div v-if="charGalleryImages.length" class="char-gallery-grid">
        <div v-for="(url, idx) in charGalleryImages" :key="idx" class="char-gallery-item">
          <img :src="url" :alt="`${charGalleryChar?.name} 形象 ${idx + 1}`" />
          <span v-if="idx === 0" class="char-gallery-tag">主图</span>
        </div>
      </div>
      <el-empty v-else description="暂无角色图片" />
    </el-dialog>

  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { characterApi } from '@/api/character'
import { projectApi } from '@/api/project'
import { scriptApi } from '@/api/script'
import { storyboardApi } from '@/api/storyboard'
import { taskApi } from '@/api/task'
import { videoApi } from '@/api/video'
import { formatOutlinePlainDividers, renderAiOutlineMarkdown } from '@/utils/renderMarkdown'
import VideoPromptsExportDialog from '@/components/create/VideoPromptsExportDialog.vue'

const INSPIRATION_KEY = 'niren.dashboard.inspiration'

const route = useRoute()
const router = useRouter()

const projectId = computed(() => String(route.params.id || ''))

const project = ref<any>(null)
const userPrompt = ref('')
const outlineContent = ref('')
const generating = ref(false)
const outlineSaving = ref(false)
/** 用户点击「确认大纲」或发送确认触发词后置 true，用于隐藏卡片下方按钮（保存失败会复位） */
const outlineConfirmBarDismissed = ref(false)
/** outline | script_gen | plan_ready */
const workflowPhase = ref<'outline' | 'script_gen' | 'plan_ready'>('outline')
const scriptWorkflowLoading = ref(false)
const scriptWorkflowPhaseText = ref('正在生成第 1 集剧本…')
/** 项目内各集剧本摘要（右侧策划栏按 activeEpisode 切换） */
const projectScripts = ref<any[]>([])
const planCharacters = ref<any[]>([])
const portraitRefreshing = ref(false)
/** 角色确认流程 */
const charactersReady = ref(false)
const charactersConfirmed = ref(false)
/** 剧本确认流程 */
const scriptReady = ref(false)
const scriptConfirmed = ref(false)
/** 角色图片画廊 */
const charGalleryVisible = ref(false)
const charGalleryChar = ref<any>(null)
const charGalleryImages = ref<string[]>([])
const streamError = ref('')
const scrollRef = ref<HTMLElement | null>(null)

const activeEpisode = ref(1)
const bottomInput = ref('')
const bottomSending = ref(false)
const chatTail = ref<Array<{ role: 'user' | 'ai'; text: string }>>([])

const addEpisodeDialogVisible = ref(false)
const dialogScripts = ref<any[]>([])
const creatingNextEpisode = ref(false)

const dialogReferenceScript = computed(() =>
  dialogScripts.value.find((s: any) => Number(s.episodeNo) === activeEpisode.value),
)

const composerPlaceholder = computed(() => {
  if (workflowPhase.value === 'outline') {
    return '补充说明以调整大纲；发送「确认分镜大纲」进入剧本阶段。Enter 换行，Cmd/Ctrl + Enter 发送'
  }
  if (workflowPhase.value === 'plan_ready') {
    return '例如：重新生成分镜、重写本集剧本；会用 AI 结合项目信息处理。Cmd/Ctrl + Enter 发送'
  }
  return '输入你的问题，Enter 换行，Cmd/Ctrl + Enter 发送'
})

const activePlanScript = computed(() =>
  projectScripts.value.find((s: any) => Number(s.episodeNo) === activeEpisode.value) ?? null,
)

/** 当前集完整剧本正文（右侧「分镜剧本」区块） */
const episodeScriptBody = computed(() => String(activePlanScript.value?.content ?? '').trim())

/** 流式阶段：分隔标记换成轻符号行，不展示 ###…### */
const outlinePlainDisplay = computed(() => formatOutlinePlainDividers(outlineContent.value))

/** 流式阶段不做 Markdown 解析，避免每帧全文 parse 卡死界面；结束后再渲染版式 */
const outlineHtml = computed(() => {
  if (generating.value) return ''
  return renderAiOutlineMarkdown(outlineContent.value)
})

const showPlanPanel = computed(
  () => workflowPhase.value === 'script_gen' || workflowPhase.value === 'plan_ready',
)

const showOutlineConfirmActions = computed(
  () =>
    !generating.value &&
    workflowPhase.value === 'outline' &&
    outlineContent.value.trim().length > 0 &&
    !outlineConfirmBarDismissed.value,
)

/** 策划就绪后右侧栏底部「生成视频」（分镜就绪后可点；已合成成片则「查看视频」） */
const showGenVideoFab = computed(
  () => workflowPhase.value === 'plan_ready' && !scriptWorkflowLoading.value,
)

/** 打开策划栏后异步拆解的本集分镜 */
const episodeStoryboardGenerating = ref(false)
const episodeStoryboardReady = ref(false)
const episodeStoryboardErr = ref('')
const hasProjectVideo = ref(false)
const mediaSubmitLoading = ref(false)
const mediaTaskProgress = ref(0)
const mediaTaskMessage = ref('')
const activeMediaTaskId = ref('')
let mediaPollTimer: ReturnType<typeof setTimeout> | null = null
const VIDEO_TASK_POLL_TIMEOUT_MS = 12 * 60 * 60 * 1000
/** 剧集切换或重复调度时递增，用于取消过期的分镜拉取/生成流程 */
let sbEnsureGeneration = 0

const videoPromptsOpen = ref(false)

/** 只要有任意分镜已生成视频，就展示合成入口 */
const hasAnyShotVideo = computed(() => episodeShots.value.some((s: any) => s.videoUrl))

function goPreview() {
  router.push({
    path: `/projects/${projectId.value}/immersive/workbench`,
    query: { episode: String(activeEpisode.value), tab: 'video' },
  })
}

function clearMediaTaskState() {
  if (mediaPollTimer) {
    clearTimeout(mediaPollTimer)
    mediaPollTimer = null
  }
  activeMediaTaskId.value = ''
  mediaSubmitLoading.value = false
  mediaTaskProgress.value = 0
  mediaTaskMessage.value = ''
}

/** 分镜视频生成 - 镜头多选 */
const episodeShots = ref<any[]>([])
const selectedShotIds = ref<string[]>([])
const allShotIds = computed(() => episodeShots.value.map((s: any) => String(s.id)))
const pendingVideoShotIds = computed(() => episodeShots.value.filter((s: any) => !s.videoUrl).map((s: any) => String(s.id)))
const defaultVideoShotIds = computed(() => pendingVideoShotIds.value.length > 0 ? pendingVideoShotIds.value : allShotIds.value)
const allSelected = computed(() => episodeShots.value.length > 0 && selectedShotIds.value.length === episodeShots.value.length)
const shotSelectLabel = computed(() => {
  if (!selectedShotIds.value.length) return '选择镜头'
  if (allSelected.value) return `全部 ${episodeShots.value.length} 镜`
  return `已选 ${selectedShotIds.value.length} / ${episodeShots.value.length} 镜`
})

function dedupeAndSortShots(shots: any[]) {
  const byShotNo = new Map<string, any>()
  for (const shot of shots) {
    const key = shot?.shotNo != null ? String(shot.shotNo) : String(shot?.id ?? byShotNo.size)
    const prev = byShotNo.get(key)
    const prevId = Number(prev?.id || 0)
    const nextId = Number(shot?.id || 0)
    if (!prev || nextId >= prevId) {
      byShotNo.set(key, shot)
    }
  }
  return Array.from(byShotNo.values()).sort((a: any, b: any) => {
    const shotDiff = (Number(a.shotNo) || 0) - (Number(b.shotNo) || 0)
    if (shotDiff !== 0) return shotDiff
    return (Number(a.id) || 0) - (Number(b.id) || 0)
  })
}

function pickCurrentEpisodeShots(allShots: any[], scriptId: string) {
  const exactScriptShots = allShots.filter((shot: any) => shot?.scriptId != null && String(shot.scriptId) === scriptId)
  const source = exactScriptShots.length > 0
    ? exactScriptShots
    : allShots.filter((shot: any) => Number(shot?.episodeNo) === Number(activeEpisode.value))
  return dedupeAndSortShots(source)
}

function isAuthFlowError(error: unknown) {
  const err = error as { code?: unknown; message?: unknown }
  const message = String(err?.message || '')
  return err?.code === 401 || err?.code === 403 || message.includes('登录') || message.includes('无权限')
}

function toggleSelectAll() {
  if (allSelected.value) {
    selectedShotIds.value = []
  } else {
    selectedShotIds.value = [...allShotIds.value]
  }
}

const primaryVideoLabel = computed(() => (hasProjectVideo.value ? '查看成片' : '生成分镜视频'))

const primaryVideoDisabled = computed(() => {
  // 剧本存在即可操作
  if (!episodeScriptBody.value.trim() || !activePlanScript.value?.id) return true
  if (hasProjectVideo.value) return false
  return (
    episodeStoryboardGenerating.value ||
    mediaSubmitLoading.value
  )
})

/** 左侧剧集条：展示当前季起始集，具体集数以项目为准 */
const episodeDisplay = computed(() => {
  const n = project.value?.episodes
  if (typeof n === 'number' && n > 0) {
    return Array.from({ length: Math.min(n, 24) }, (_, i) => i + 1)
  }
  return [1]
})

function scrollToBottom() {
  nextTick(() => {
    const el = scrollRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

watch([outlineContent, chatTail, generating, charactersReady, scriptReady], () => scrollToBottom())

watch(activeEpisode, async () => {
  episodeStoryboardReady.value = false
  episodeStoryboardErr.value = ''
  episodeShots.value = []
  selectedShotIds.value = []
  sbEnsureGeneration += 1
  outlineContent.value = ''
  if (workflowPhase.value === 'plan_ready') {
    try {
      await loadPlanSideData()
      await refreshVideoOverview()
    } catch {
      /* ignore */
    }
  } else {
    workflowPhase.value = 'outline'
  }
})

async function loadProject() {
  const res = await projectApi.get(projectId.value)
  project.value = res.data?.data ?? res.data
}

function readSeedFromStorage() {
  try {
    const s = sessionStorage.getItem(INSPIRATION_KEY) || ''
    userPrompt.value = s.trim()
  } catch {
    userPrompt.value = ''
  }
}

async function startOutlineStream() {
  const idea = userPrompt.value
  if (!idea) {
    ElMessage.warning('未找到创作灵感，请从首页重新发起')
    return
  }
  generating.value = true
  streamError.value = ''
  outlineContent.value = ''
  try {
    await scriptApi.generateOutlinePreviewStream(
      {
        projectId: projectId.value,
        idea,
      },
      {
        onChunk: (chunk) => {
          outlineContent.value += chunk
        },
        onDone: async () => {
          generating.value = false
          try {
            await loadProject()
          } catch {
            /* 列表名等以后端为准，刷新失败可忽略 */
          }
          chatTail.value.push({
            role: 'ai',
            text: '您好！根据您提供的创意，我已为您构思全剧大纲与人物信息，请浏览上方内容。若需调整，可在下方说明，我会尝试优化大纲。',
          })
        },
        onError: (msg) => {
          streamError.value = msg
          generating.value = false
        },
      },
    )
  } catch (e: any) {
    streamError.value = e?.message || '大纲生成失败'
    generating.value = false
  }
}

async function pollTaskUntilDone(taskId: string, timeoutMs = VIDEO_TASK_POLL_TIMEOUT_MS) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const ax = await taskApi.get(taskId)
    const payload = ax.data as { data?: Record<string, unknown> }
    const task = payload?.data ?? (payload as Record<string, unknown>)
    const st = task?.status as string | undefined
    if (st === 'SUCCESS') return task
    if (st === 'FAILED') throw new Error(String(task?.message || '后台任务失败'))
    await new Promise((r) => setTimeout(r, 2000))
  }
  throw new Error('任务等待超时，请稍后在「剧本生成」页查看进度')
}

function extractTaskId(res: unknown): string | null {
  const r = res as { data?: { data?: { id?: unknown } } }
  const id = r?.data?.data?.id
  return id != null ? String(id) : null
}

async function refreshVideoOverview() {
  try {
    const res = await videoApi.getOverview(projectId.value)
    const o = (res as any).data?.data ?? {}
    const u = o.videoUrl
    hasProjectVideo.value = !!(u && String(u).trim())
  } catch {
    hasProjectVideo.value = false
  }
}

async function loadEpisodeShots() {
  const sid = activePlanScript.value?.id
  if (!sid) { episodeShots.value = []; selectedShotIds.value = []; return }
  try {
    const res = await storyboardApi.listByProject(projectId.value)
    const all = (res as any).data?.data ?? []
    episodeShots.value = pickCurrentEpisodeShots(all, String(sid))
    // 默认只选择未生成视频的镜头，避免误覆盖已经可用的分镜视频。
    selectedShotIds.value = [...defaultVideoShotIds.value]
  } catch { episodeShots.value = []; selectedShotIds.value = [] }
}

async function ensureEpisodeStoryboard() {
  const gen = ++sbEnsureGeneration
  episodeStoryboardErr.value = ''
  if (!showPlanPanel.value || workflowPhase.value !== 'plan_ready') return
  const script = activePlanScript.value as { id?: number | string } | null
  const sidRaw = script?.id
  if (sidRaw == null || !episodeScriptBody.value.trim()) {
    episodeStoryboardReady.value = false
    return
  }
  const scriptId = String(sidRaw)
  episodeStoryboardGenerating.value = true
  try {
    // 用 listByProject + 前端过滤代替 listByScript，避免大整数精度丢失
    const allRes = await storyboardApi.listByProject(projectId.value)
    if (gen !== sbEnsureGeneration) return
    const allStoryboards = (allRes as any).data?.data ?? []
    const existing = pickCurrentEpisodeShots(allStoryboards, scriptId)
    if (existing.length > 0) {
      episodeStoryboardReady.value = true
      await loadEpisodeShots()
      await refreshVideoOverview()
      return
    }

    const genRes = await storyboardApi.generate({ projectId: projectId.value, scriptId })
    const taskId = extractTaskId(genRes)
    if (!taskId) throw new Error('未返回分镜任务')
    await pollTaskUntilDone(taskId)
    if (gen !== sbEnsureGeneration) return

    const verifyRes = await storyboardApi.listByProject(projectId.value)
    const allRows = (verifyRes as any).data?.data ?? []
    const rows = pickCurrentEpisodeShots(allRows, scriptId)
    if (rows.length === 0) {
      throw new Error('分镜生成完成但未查到镜头，请稍后重试')
    }
    episodeStoryboardReady.value = true
    await loadEpisodeShots()
    await refreshVideoOverview()
  } catch (e: unknown) {
    if (gen !== sbEnsureGeneration) return
    episodeStoryboardReady.value = false
    episodeStoryboardErr.value = e instanceof Error ? e.message : '分镜生成失败'
  } finally {
    if (gen === sbEnsureGeneration) episodeStoryboardGenerating.value = false
  }
}

watch(
  () =>
    [showPlanPanel.value, workflowPhase.value, activePlanScript.value?.id, episodeScriptBody.value] as const,
  () => {
    if (showPlanPanel.value && workflowPhase.value === 'plan_ready') {
      void ensureEpisodeStoryboard()
    }
  },
)

// 切换集数时如果已在 plan_ready 状态，需确认后再重新生成分镜
watch(activeEpisode, async (_newEp, oldEp) => {
  if (oldEp == null) return // 初始加载不弹
  if (!showPlanPanel.value || workflowPhase.value !== 'plan_ready') return
  try {
    await ElMessageBox.confirm(
      `切换到第 ${_newEp} 集，需要重新生成分镜，是否继续？`,
      '切换集数',
      { confirmButtonText: '生成', cancelButtonText: '稍后', type: 'info' },
    )
    void ensureEpisodeStoryboard()
  } catch {
    // 用户取消
  }
})

function goVideoWorkbench() {
  router.push({
    path: `/projects/${projectId.value}/immersive/workbench`,
    query: { episode: String(activeEpisode.value), tab: 'video' },
  })
}

async function onPrimaryVideoAction() {
  if (hasProjectVideo.value) {
    goVideoWorkbench()
    return
  }
  if (!activePlanScript.value?.id) return
  if (!episodeStoryboardReady.value) {
    await ensureEpisodeStoryboard()
    await loadEpisodeShots()
    if (!episodeStoryboardReady.value) {
      ElMessage.warning('分镜尚未准备就绪，请稍后重试')
      return
    }
  }
  const shotIds = selectedShotIds.value.length > 0
    ? [...selectedShotIds.value]
    : allShotIds.value
  if (!shotIds.length) {
    ElMessage.warning('暂无本集分镜数据，请先生成分镜')
    return
  }
  mediaSubmitLoading.value = true
  mediaTaskProgress.value = 0
  mediaTaskMessage.value = `正在提交 ${shotIds.length} 个镜头的视频生成任务…`
  activeMediaTaskId.value = ''
  if (mediaPollTimer) {
    clearTimeout(mediaPollTimer)
    mediaPollTimer = null
  }
  try {

    const dynRes = await videoApi.generateStoryboardVideos(projectId.value, shotIds)
    const tid = extractTaskId(dynRes)
    if (!tid) throw new Error('未返回任务 ID')
    activeMediaTaskId.value = tid

    ElMessage.success('视频生成任务已提交，可在成片预览查看进度')
    mediaTaskMessage.value = '视频生成中，请稍候…'

    // 非阻塞轮询：更新进度但不阻塞 UI
    const deadline = Date.now() + VIDEO_TASK_POLL_TIMEOUT_MS
    const poll = async () => {
      if (!mediaSubmitLoading.value || activeMediaTaskId.value !== tid) return
      if (Date.now() > deadline) {
        clearMediaTaskState()
        ElMessage.warning('视频生成轮询已达到 12 小时，请到成片预览查看进度')
        return
      }
      try {
        const ax = await taskApi.get(tid)
        const task = ax.data?.data ?? ax.data
        mediaTaskProgress.value = Number(task?.progress ?? 0)
        mediaTaskMessage.value = task?.message || '视频生成中…'
        const status = String(task?.status || '').toUpperCase()
        if (status === 'SUCCESS') {
          mediaTaskProgress.value = 100
          mediaTaskMessage.value = task?.message || '视频生成完成'
          await Promise.all([loadEpisodeShots(), refreshVideoOverview()])
          clearMediaTaskState()
          router.push({
            path: `/projects/${projectId.value}/immersive/workbench`,
            query: { episode: String(activeEpisode.value), tab: 'video' },
          })
          return
        }
        if (status === 'FAILED') {
          clearMediaTaskState()
          ElMessage.error(task?.message || '视频生成失败')
          return
        }
      } catch (error) {
        if (isAuthFlowError(error)) {
          clearMediaTaskState()
          ElMessage.error(error instanceof Error ? error.message : '登录已过期，请重新登录')
          return
        }
        mediaTaskMessage.value = '正在同步视频生成进度…'
      }
      mediaPollTimer = setTimeout(poll, 3000)
    }
    mediaPollTimer = setTimeout(poll, 3000)
  } catch (e: unknown) {
    clearMediaTaskState()
    ElMessage.error(e instanceof Error ? e.message : '分镜视频生成失败')
  }
}

async function loadPlanSideData() {
  const [scriptsRes, charRes] = await Promise.all([
    scriptApi.listByProject(projectId.value),
    characterApi.listByProject(projectId.value),
  ])
  const scripts = (scriptsRes as any).data?.data ?? (scriptsRes as any).data ?? []
  projectScripts.value = Array.isArray(scripts) ? scripts : []
  planCharacters.value = (charRes as any).data?.data ?? (charRes as any).data ?? []
}

async function refreshCharactersOnly() {
  const charRes = await characterApi.listByProject(projectId.value)
  planCharacters.value = (charRes as any).data?.data ?? (charRes as any).data ?? []
}

async function triggerCharacterPortraits() {
  const list = planCharacters.value.filter((c: any) => !c.imageUrl)
  if (!list.length) return
  portraitRefreshing.value = true
  try {
    await Promise.all(list.map((c: any) => characterApi.generateImage(c.id)))
    const deadline = Date.now() + 180000
    while (Date.now() < deadline) {
      await refreshCharactersOnly()
      const ok = planCharacters.value.every((c: any) => c.imageUrl)
      if (ok) break
      await new Promise((r) => setTimeout(r, 2500))
    }
  } finally {
    portraitRefreshing.value = false
  }
}

async function runScriptAndAssetsPipeline() {
  scriptWorkflowLoading.value = true
  charactersReady.value = false
  charactersConfirmed.value = false
  scriptReady.value = false
  scriptConfirmed.value = false
  try {
    // Step 1: 生成角色 + 加载策划信息
    scriptWorkflowPhaseText.value = '正在生成角色与策划信息…'
    await loadPlanSideData()
    workflowPhase.value = 'plan_ready'

    // Step 2: 生成角色形象
    scriptWorkflowPhaseText.value = '正在生成人物形象…'
    await triggerCharacterPortraits().catch(() => {
      ElMessage.warning('部分角色形象生成失败，可在「角色管理」页手动重试')
    })
    await refreshCharactersOnly()
    charactersReady.value = true
    scriptWorkflowLoading.value = false
    scriptWorkflowPhaseText.value = ''
    chatTail.value.push({
      role: 'ai',
      text: '角色形象已生成完毕，请在右侧查看角色列表。确认角色无误后，点击下方「确认角色」按钮生成剧本。',
    })
  } catch (e: unknown) {
    workflowPhase.value = 'outline'
    scriptWorkflowLoading.value = false
    scriptWorkflowPhaseText.value = ''
    const msg = e instanceof Error ? e.message : '生成失败'
    throw new Error(msg)
  }
}

/** 用户确认角色后，生成剧本 */
async function confirmCharacters(skipUserMessage = false) {
  if (charactersConfirmed.value) return
  charactersConfirmed.value = true
  if (!skipUserMessage) {
    chatTail.value.push({ role: 'user', text: '确认角色' })
  }
  await nextTick()
  scrollToBottom()

  scriptWorkflowLoading.value = true
  scriptWorkflowPhaseText.value = '正在生成第 1 集剧本…'
  try {
    const genRes = await scriptApi.generate({
      projectId: projectId.value,
      episodeNo: 1,
      idea: userPrompt.value,
    })
    const taskPayload = (genRes as any).data?.data ?? (genRes as any).data
    const taskId = taskPayload?.id
    if (!taskId) throw new Error('未返回剧本生成任务')
    await pollTaskUntilDone(String(taskId))
    await loadPlanSideData()
    scriptReady.value = true
    scriptWorkflowLoading.value = false
    scriptWorkflowPhaseText.value = ''
    chatTail.value.push({
      role: 'ai',
      text: '剧本已生成完毕，请在右侧查看剧本内容。确认无误后，点击下方「确认剧本」按钮保存。',
    })
  } catch (e: unknown) {
    scriptWorkflowLoading.value = false
    scriptWorkflowPhaseText.value = ''
    charactersConfirmed.value = false
    const msg = e instanceof Error ? e.message : '剧本生成失败'
    ElMessage.error(msg)
  }
}

  /** 用户确认剧本后，保存剧本并触发分镜拆解 */
  async function confirmScript() {
    if (scriptConfirmed.value) return
    scriptConfirmed.value = true
    chatTail.value.push({ role: 'user', text: '确认剧本' })
    await nextTick()
    scrollToBottom()

    if (showPlanPanel.value && workflowPhase.value === 'plan_ready' && activePlanScript.value?.id) {
      chatTail.value.push({
        role: 'ai',
        text: '剧本已保存，正在拆解分镜…',
      })
      await ensureEpisodeStoryboard()
      if (episodeStoryboardReady.value) {
        chatTail.value.push({
          role: 'ai',
          text: '分镜已就绪。右侧「生成分镜视频」按钮已可点击，可开始生成分镜视频。',
        })
      } else if (episodeStoryboardErr.value) {
        chatTail.value.push({
          role: 'ai',
          text: '分镜生成遇到问题：' + episodeStoryboardErr.value + '，可稍后重试。',
        })
      }
    } else {
      chatTail.value.push({
        role: 'ai',
        text: '剧本已保存。右侧「生成分镜视频」按钮已可点击，可开始生成分镜视频。',
      })
    }
  }


async function saveOutlineAndAdvance() {
  if (!outlineContent.value.trim()) return
  if (workflowPhase.value !== 'outline') {
    ElMessage.info('大纲已确认，请使用右侧「生成视频」或到剧本/分镜页继续')
    return
  }
  outlineSaving.value = true
  try {
    await scriptApi.saveOutlinePreview({
      projectId: projectId.value,
      content: outlineContent.value,
      idea: userPrompt.value,
    })
    ElMessage.success('大纲已保存')
    await loadProject()
    workflowPhase.value = 'script_gen'
    chatTail.value.push({
      role: 'ai',
      text: '已确认大纲，正在生成第 1 集完整剧本并同步角色与场景信息…',
    })
    await runScriptAndAssetsPipeline()
  } catch (e: unknown) {
    workflowPhase.value = 'outline'
    outlineConfirmBarDismissed.value = false
    ElMessage.error(e instanceof Error ? e.message : '处理失败')
  } finally {
    outlineSaving.value = false
  }
}

/** 与底部发送「确认分镜大纲」一致，便于对话区留下确认记录 */
const OUTLINE_CONFIRM_CHAT_TEXT = '确认分镜大纲'

async function confirmOutline() {
  if (!outlineContent.value.trim()) return
  if (workflowPhase.value !== 'outline') {
    ElMessage.info('大纲已确认，请使用右侧「生成视频」或到剧本/分镜页继续')
    return
  }
  outlineConfirmBarDismissed.value = true
  chatTail.value.push({ role: 'user', text: OUTLINE_CONFIRM_CHAT_TEXT })
  await nextTick()
  scrollToBottom()
  await saveOutlineAndAdvance()
}

function onBottomKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
    e.preventDefault()
    sendFollowUp()
  }
}

const CONFIRM_OUTLINE_TRIGGERS = ['确认分镜大纲', '确认大纲']
const CONFIRM_CHARACTER_TRIGGERS = ['确认角色']

async function sendFollowUp() {
  const t = bottomInput.value.trim()
  if (!t) return
  if (workflowPhase.value === 'outline' && !outlineContent.value.trim()) return

  if (CONFIRM_OUTLINE_TRIGGERS.includes(t)) {
    outlineConfirmBarDismissed.value = true
    bottomSending.value = true
    chatTail.value.push({ role: 'user', text: t })
    bottomInput.value = ''
    try {
      await saveOutlineAndAdvance()
    } catch {
      /* toast、phase、outlineConfirmBarDismissed 已在 saveOutlineAndAdvance 处理 */
    } finally {
      bottomSending.value = false
      scrollToBottom()
    }
    return
  }

  if (CONFIRM_CHARACTER_TRIGGERS.includes(t)) {
    bottomSending.value = true
    chatTail.value.push({ role: 'user', text: t })
    bottomInput.value = ''
    if (!charactersReady.value && !planCharacters.value.length) {
      chatTail.value.push({
        role: 'ai',
        text: '暂无角色数据，请先完成大纲确认流程。',
      })
      bottomSending.value = false
      scrollToBottom()
      return
    }
    if (charactersConfirmed.value) {
      chatTail.value.push({
        role: 'ai',
        text: '角色已确认，剧本已生成。如需重新生成角色，请输入"重新生成角色"。',
      })
      bottomSending.value = false
      scrollToBottom()
      return
    }
    try {
      await confirmCharacters(true)
    } catch {
      /* errors handled in confirmCharacters */
    } finally {
      bottomSending.value = false
      scrollToBottom()
    }
    return
  }

  bottomSending.value = true
  chatTail.value.push({ role: 'user', text: t })
  bottomInput.value = ''
  try {
    const res = await projectApi.immersiveChat(projectId.value, {
      message: t,
      episodeNo: activeEpisode.value,
      workflowPhase: workflowPhase.value,
      outlineContent: workflowPhase.value === 'outline' ? outlineContent.value : undefined,
    })
    const data = (res as any).data?.data ?? (res as any).data ?? {}
    const reply = typeof data.reply === 'string' ? data.reply : '已处理。'
    if (typeof data.outlineContent === 'string' && data.outlineContent.trim() && workflowPhase.value === 'outline') {
      outlineContent.value = data.outlineContent
    }
    chatTail.value.push({ role: 'ai', text: reply })

    const tid = data.taskId
    const ttype = data.taskType as string | undefined
    if (tid != null && (ttype === 'STORYBOARD_GEN' || ttype === 'SCRIPT_GEN' || ttype === 'CHARACTER_REGENERATE')) {
      try {
        await pollTaskUntilDone(String(tid))
        await loadPlanSideData()
        if (ttype === 'STORYBOARD_GEN') {
          episodeStoryboardReady.value = true
          episodeStoryboardErr.value = ''
          await loadEpisodeShots()
          await refreshVideoOverview()
        }
        if (ttype === 'CHARACTER_REGENERATE') {
          await refreshCharactersOnly()
          chatTail.value.push({
            role: 'ai',
            text: '角色已重新生成完毕，正在生成角色形象图片…',
          })
          await triggerCharacterPortraits().catch(() => {
            ElMessage.warning('部分角色形象生成失败，可在「角色管理」页手动重试')
          })
          await refreshCharactersOnly()
          chatTail.value.push({
            role: 'ai',
            text: '角色形象已更新完毕，请在右侧策划栏查看。',
          })
        } else {
          chatTail.value.push({
            role: 'ai',
            text:
              ttype === 'STORYBOARD_GEN'
                ? '后台任务已完成：本集分镜已写入，可在右侧策划栏查看。'
                : '后台任务已完成：本集剧本已刷新，请查看右侧与剧本页。',
          })
        }
      } catch (te: unknown) {
        ElMessage.error(te instanceof Error ? te.message : '后台任务失败')
        if (ttype === 'STORYBOARD_GEN') {
          episodeStoryboardReady.value = false
          episodeStoryboardErr.value = te instanceof Error ? te.message : '分镜任务失败'
        }
      }
    }
  } catch (e: unknown) {
    ElMessage.error(e instanceof Error ? e.message : '发送失败')
    chatTail.value.push({
      role: 'ai',
      text: '请求失败，请稍后重试或到剧本/分镜页操作。',
    })
  } finally {
    bottomSending.value = false
    scrollToBottom()
  }
}

function goProjectEpisodes() {
  router.push(`/projects/${projectId.value}/episodes`)
}

async function hydrateWorkspaceWithoutNewStream() {
  try {
    const scriptsRes = await scriptApi.listByProject(projectId.value)
    const list = (scriptsRes as any).data?.data ?? (scriptsRes as any).data ?? []
    projectScripts.value = Array.isArray(list) ? list : []

    const ep = activeEpisode.value
    const sc = projectScripts.value.find((s: any) => Number(s.episodeNo) === ep)

    if (sc && (String(sc.summary || '').trim() || String(sc.content || '').trim())) {
      workflowPhase.value = 'plan_ready'
      const blob = String(sc.summary || sc.content || '').trim()
      outlineContent.value = blob.slice(0, 24000)
      await loadPlanSideData()
      return
    }

    const ci = project.value?.commonInfo
    if (typeof ci === 'string' && ci.trim()) {
      outlineContent.value = ci.trim()
      workflowPhase.value = 'outline'
    }
  } catch {
    /* ignore */
  }
}

function noopVip() {
  ElMessage.info('会员能力敬请期待')
}

function noopBell() {
  ElMessage.info('暂无新通知')
}

function parseImageUrls(raw: any): string[] {
  if (!raw) return []
  if (Array.isArray(raw)) return raw.filter(Boolean)
  if (typeof raw === 'string') {
    try {
      const arr = JSON.parse(raw)
      if (Array.isArray(arr)) return arr.filter(Boolean)
    } catch { /* not JSON */ }
    return raw.split(',').map((s: string) => s.trim()).filter(Boolean)
  }
  return []
}

function openCharGallery(char: any) {
  charGalleryChar.value = char
  const urls = parseImageUrls(char.imageUrls)
  charGalleryImages.value = urls.length > 0 ? urls : (char.imageUrl ? [char.imageUrl] : [])
  charGalleryVisible.value = true
}

function noopAttach() {
  ElMessage.info('附件能力敬请期待')
}

async function onAddEpisodeDialogOpen() {
  try {
    const res = await scriptApi.listByProject(projectId.value)
    dialogScripts.value = (res as any).data?.data ?? (res as any).data ?? []
  } catch {
    dialogScripts.value = []
  }
}

function formatScriptDate(s: Record<string, unknown>) {
  const raw = s.updateTime ?? s.createTime
  if (raw == null) return '—'
  const d = new Date(String(raw))
  if (Number.isNaN(d.getTime())) return '—'
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}/${m}/${day}`
}

function openAddEpisodeDialog() {
  addEpisodeDialogVisible.value = true
}

async function createNextEpisode() {
  const p = project.value
  if (!p?.name) {
    ElMessage.warning('项目信息未加载完整')
    return
  }
  const cap = typeof p.episodes === 'number' && p.episodes > 0 ? p.episodes : 1
  if (cap >= 120) {
    ElMessage.warning('已达到当前上限，请在项目详情中调整')
    return
  }
  creatingNextEpisode.value = true
  try {
    await projectApi.update(projectId.value, {
      name: p.name,
      description: p.description ?? '',
      projectType: p.projectType ?? '',
      genre: p.genre ?? '',
      episodes: cap + 1,
      episodeDuration: typeof p.episodeDuration === 'number' && p.episodeDuration > 0 ? p.episodeDuration : 60,
    })
    await loadProject()
    activeEpisode.value = cap + 1
    addEpisodeDialogVisible.value = false
    chatTail.value.push({
      role: 'ai',
      text: `已进入第 ${cap + 1} 集策划。可先让我提炼前面故事的情节走向，或在下方直接写下本集目标、开场与冲突。`,
    })
    ElMessage.success(`已扩展至 ${cap + 1} 集，左侧已切换到第 ${cap + 1} 集`)
  } catch (e: unknown) {
    ElMessage.error(e instanceof Error ? e.message : '更新失败')
  } finally {
    creatingNextEpisode.value = false
  }
}

onMounted(async () => {
  readSeedFromStorage()
  const epQ = Number(route.query.episode)
  if (Number.isFinite(epQ) && epQ >= 1) {
    activeEpisode.value = epQ
  }

  try {
    await loadProject()
  } catch {
    ElMessage.error('加载项目失败')
    return
  }

  const hasCachedInspiration = userPrompt.value.trim().length > 0
  const commonReady = !!(project.value?.commonInfo && String(project.value.commonInfo).trim())

  // 仅在本项目尚无通用信息时使用工作台缓存灵感触发流式大纲（避免从剧集入口进入时误用其它会话缓存）
  if (hasCachedInspiration && !commonReady) {
    await startOutlineStream()
    return
  }

  await hydrateWorkspaceWithoutNewStream()

  if (workflowPhase.value === 'plan_ready') {
    await refreshVideoOverview()
  }

  if (!outlineContent.value.trim() && workflowPhase.value === 'outline') {
    // 静默：新项目或从剧集入口进入时无大纲属正常状态，不弹 warning
  }
})

onUnmounted(() => {
  clearMediaTaskState()
})
</script>

<style scoped>
.immersive-root {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background: var(--bg-page);
  color: var(--text-primary);
  animation: immersive-in 0.55s cubic-bezier(0.22, 1, 0.36, 1) both;
}

@keyframes immersive-in {
  from {
    opacity: 0;
    transform: translateY(12px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.immersive-top {
  flex-shrink: 0;
  height: 52px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px 0 12px;
  border-bottom: 1px solid var(--border);
  background: var(--bg-card);
}

.immersive-top-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
  flex: 1;
}

.top-project-title {
  font-size: 15px;
  font-weight: 700;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.top-logo {
  background: none;
  border: none;
  cursor: pointer;
  padding: 6px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: inherit;
}
.top-logo:hover {
  background: var(--bg-muted);
}

.top-right {
  display: flex;
  align-items: center;
  gap: 14px;
}
.top-compose-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 32px;
  padding: 0 14px;
  border: none;
  border-radius: var(--radius-full);
  cursor: pointer;
  font-size: 12px;
  font-weight: 700;
  color: #fff;
  background: linear-gradient(135deg, var(--primary), #a855f7);
  box-shadow: 0 2px 10px rgba(99,102,241,0.35);
  transition: transform 0.15s, box-shadow 0.15s;
  white-space: nowrap;
}
.top-compose-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 16px rgba(99,102,241,0.5);
}
.vip-link {
  background: none;
  border: none;
  cursor: pointer;
  font-size: 13px;
  font-weight: 600;
  color: var(--color-warning);
  padding: 4px 6px;
}
.vip-link:hover {
  text-decoration: underline;
}
.icon-btn {
  width: 36px;
  height: 36px;
  border-radius: var(--radius-sm);
  border: none;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}
.icon-btn:hover {
  background: var(--bg-muted);
}

.immersive-body {
  flex: 1;
  display: flex;
  min-height: 0;
  min-width: 0;
}
.immersive-body.has-plan .immersive-main {
  border-right: 1px solid rgba(255, 255, 255, 0.06);
}

.immersive-plan {
  flex: 0 0 clamp(280px, 34vw, 420px);
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: var(--bg-card);
  border-left: 1px solid var(--border);
}
.plan-scroll {
  flex: 1;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 18px 16px 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.plan-actions--dock {
  flex-shrink: 0;
  padding: 12px 16px calc(14px + env(safe-area-inset-bottom, 0px));
  border-top: 1px solid var(--border);
  background: var(--bg-card);
  box-shadow: var(--shadow-sm);
}
.plan-head {
  position: relative;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--border);
}
.plan-close-btn {
  position: absolute;
  top: 0;
  right: 0;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--text-muted);
  font-size: 16px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.15s, color 0.15s;
}
.plan-close-btn:hover {
  background: var(--bg-muted);
  color: var(--text-primary);
}
.plan-ep-badge {
  display: inline-block;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.06em;
  color: var(--primary-light);
  background: var(--primary-glow);
  border: 1px solid var(--primary-light);
  padding: 4px 10px;
  border-radius: var(--radius-full);
}
.plan-title {
  margin: 10px 0 6px;
  font-size: 1.05rem;
  font-weight: 700;
  color: var(--text-primary);
  line-height: 1.35;
}
.plan-ai-note {
  margin: 0;
  font-size: 11px;
  color: var(--text-muted);
}
.plan-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 32px 12px;
  color: var(--text-secondary);
  font-size: 13px;
  text-align: center;
}
.plan-spin {
  width: 22px;
  height: 22px;
}
.plan-block {
  padding-bottom: 12px;
  border-bottom: 1px solid var(--border);
}
.plan-block:last-child {
  border-bottom: none;
  padding-bottom: 0;
}
.plan-block-title {
  margin: 0 0 8px;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--text-muted);
}
.plan-block-body {
  margin: 0;
  font-size: 13px;
  line-height: 1.65;
  color: var(--text-secondary);
}
.plan-muted {
  margin: 0;
  font-size: 12px;
  line-height: 1.55;
  color: var(--text-muted);
}
.plan-subject-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.plan-subject-item {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  cursor: pointer;
  padding: 4px;
  border-radius: var(--radius-sm);
  transition: background 0.15s;
}
.plan-subject-item:hover {
  background: var(--bg-muted);
}
.plan-subject-avatar {
  width: 44px;
  height: 44px;
  border-radius: var(--radius-sm);
  overflow: hidden;
  flex-shrink: 0;
  background: var(--bg-muted);
  border: 1px solid var(--border);
}
.plan-subject-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.plan-avatar-ph {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  font-size: 14px;
  color: var(--text-muted);
}
.plan-avatar-ph--loading {
  font-size: 11px;
  color: var(--primary-light);
  animation: plan-avatar-pulse 1.5s ease-in-out infinite;
}
@keyframes plan-avatar-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}
.plan-subject-meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}
.plan-subject-name {
  font-size: 13px;
  font-weight: 700;
  color: var(--text-primary);
}
.plan-subject-desc {
  font-size: 11px;
  line-height: 1.45;
  color: var(--text-muted);
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.plan-script-body {
  margin: 0;
  padding: 10px 12px;
  font-size: 12px;
  line-height: 1.65;
  font-family: ui-monospace, 'Cascadia Mono', 'Segoe UI Mono', monospace;
  color: var(--text-secondary);
  white-space: pre-wrap;
  word-break: break-word;
  background: var(--bg-muted);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  max-height: min(42vh, 520px);
  overflow-y: auto;
}
.plan-actions {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.btn-plan-prompts {
  width: 100%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 10px 14px;
  border-radius: var(--radius-md);
  border: 1px solid var(--primary-light);
  cursor: pointer;
  font-size: 13px;
  font-weight: 700;
  color: var(--primary-light);
  background: var(--primary-glow);
  transition: background 0.15s, border-color 0.15s;
}
.btn-plan-prompts:hover:not(:disabled) {
  background: rgba(99, 102, 241, 0.22);
  border-color: var(--primary);
}
.btn-plan-prompts:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
/* ── 视频工作台入口卡片 ── */
.video-workbench-entry {
  position: relative;
  margin: 0 0 4px;
  border-radius: var(--radius-lg);
  cursor: pointer;
  overflow: hidden;
  isolation: isolate;
}
.vwe-glow {
  position: absolute;
  inset: 0;
  background: linear-gradient(135deg, rgba(99,102,241,0.32) 0%, rgba(168,85,247,0.18) 50%, rgba(236,72,153,0.12) 100%);
  filter: blur(24px);
  opacity: 0.6;
  z-index: 0;
  transition: opacity 0.4s;
}
.video-workbench-entry:hover .vwe-glow {
  opacity: 0.9;
}
.vwe-card {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
  background: rgba(99,102,241,0.06);
  border: 1px solid rgba(99,102,241,0.2);
  border-radius: inherit;
  backdrop-filter: blur(6px);
  transition: border-color 0.2s, background 0.2s;
}
.video-workbench-entry:hover .vwe-card {
  border-color: rgba(99,102,241,0.45);
  background: rgba(99,102,241,0.1);
}
.vwe-icon-ring {
  flex-shrink: 0;
  width: 44px;
  height: 44px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, var(--primary), #a855f7);
  color: #fff;
  box-shadow: 0 4px 16px rgba(99,102,241,0.35), 0 0 0 0 rgba(99,102,241,0.25);
  animation: vwe-pulse 2.4s ease-in-out infinite;
}
@keyframes vwe-pulse {
  0%, 100% { box-shadow: 0 4px 16px rgba(99,102,241,0.35), 0 0 0 0 rgba(99,102,241,0.25); }
  50% { box-shadow: 0 4px 20px rgba(99,102,241,0.45), 0 0 0 8px rgba(99,102,241,0); }
}
.vwe-body {
  flex: 1;
  min-width: 0;
}
.vwe-title {
  margin: 0;
  font-size: 13px;
  font-weight: 700;
  color: var(--text-primary);
  line-height: 1.3;
}
.vwe-desc {
  margin: 2px 0 0;
  font-size: 11px;
  color: var(--text-muted);
  line-height: 1.4;
}
.vwe-arrow {
  flex-shrink: 0;
  color: var(--primary-light);
  display: flex;
  align-items: center;
  transition: transform 0.2s;
}
.video-workbench-entry:hover .vwe-arrow {
  transform: translateX(3px);
}

.plan-action-hint {
  margin: 0;
  font-size: 11px;
  line-height: 1.45;
  color: var(--text-muted);
}
.plan-action-hint--ok {
  color: var(--color-success);
}
.plan-action-hint--err {
  color: var(--color-danger);
}
.btn-plan-video {
  width: 100%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 12px 16px;
  border-radius: var(--radius-full);
  border: none;
  cursor: pointer;
  font-size: 14px;
  font-weight: 700;
  color: #fff;
  background: var(--primary);
  box-shadow: var(--shadow-primary);
  transition: transform 0.15s, box-shadow 0.15s;
}
.btn-plan-video-ico {
  font-size: 13px;
  opacity: 0.88;
}
.btn-plan-video:hover:not(:disabled) {
  transform: translateY(-4px);
  box-shadow: 0 12px 28px rgba(0, 0, 0, 0.4);
}
.btn-plan-video:disabled {
  opacity: 0.45;
  cursor: not-allowed;
  transform: none;
  box-shadow: none;
}

.btn-shot-select {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: 1px solid var(--border);
  background: var(--bg-muted);
  color: var(--text-primary);
  padding: 6px 10px;
  border-radius: var(--radius-md);
  font-size: 11px;
  cursor: pointer;
  white-space: nowrap;
  transition: border-color 0.15s;
}
.btn-shot-select:hover { border-color: var(--primary); }
.btn-shot-select.has-selection { border-color: var(--primary); color: var(--primary-light); }
.btn-shot-select-arrow { font-size: 10px; opacity: 0.6; }

.plan-video-progress {
  position: relative;
  margin-top: 8px;
  height: 36px;
  border-radius: 10px;
  background: rgba(255,255,255,0.08);
  overflow: hidden;
}
.plan-video-progress-bar {
  position: absolute;
  inset: 0;
  height: 100%;
  background: linear-gradient(90deg, #6366f1, #8b5cf6);
  border-radius: 10px;
  transition: width 0.4s ease;
}
.plan-video-progress-text {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  font-size: 11px;
  color: #fff;
}

.episode-rail {
  width: 56px;
  flex-shrink: 0;
  align-self: stretch;
  min-height: 0;
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 12px 6px;
  gap: 12px;
  background: var(--bg-card);
}
.episode-rail-label {
  flex-shrink: 0;
  writing-mode: vertical-rl;
  font-size: 11px;
  letter-spacing: 0.2em;
  color: var(--text-muted);
}
/* 约 5 个集数圆点的可视高度：36×5 + 间距 10×4 */
.episode-pills-scroll {
  flex: 0 1 auto;
  width: 100%;
  min-height: 0;
  max-height: calc(36px * 5 + 10px * 4);
  overflow-x: hidden;
  overflow-y: auto;
  scrollbar-width: none;
  -ms-overflow-style: none;
}
.episode-pills-scroll::-webkit-scrollbar {
  width: 0;
  height: 0;
  display: none;
}
.episode-pills {
  display: flex;
  flex-direction: column;
  gap: 10px;
  align-items: center;
}
.episode-dot {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  border: 1px solid var(--border);
  background: var(--bg-muted);
  color: var(--text-primary);
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}
.episode-dot.active {
  border-color: var(--primary-light);
  background: var(--primary-glow);
}
.episode-add {
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  border: 1px dashed var(--border-strong);
  background: transparent;
  color: var(--text-secondary);
  font-size: 18px;
  line-height: 1;
  cursor: pointer;
}
.episode-add:hover {
  border-color: var(--primary-light);
  background: var(--bg-muted);
}

.immersive-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}

.chat-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 20px 24px 16px;
  max-width: 900px;
  width: 100%;
  margin: 0 auto;
}

.msg-user-wrap {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 20px;
}
.bubble-user {
  max-width: 85%;
  padding: 12px 16px;
  border-radius: 16px 16px 4px 16px;
  background: var(--primary);
  border: 1px solid var(--primary-dark);
  font-size: 14px;
  line-height: 1.55;
  color: #fff;
  white-space: pre-wrap;
  word-break: break-word;
}

/* 第 2 集及以后：继续策划引导（对齐参考布局） */
.continuation-block {
  margin-bottom: 18px;
  padding: 14px 16px 16px;
  border-radius: var(--radius-md);
  background: var(--bg-card);
  border: 1px solid var(--border);
}
.continuation-title {
  margin: 0 0 12px;
  font-size: 15px;
  font-weight: 700;
  color: var(--text-primary);
  letter-spacing: -0.02em;
}
.continuation-seko {
  padding-top: 2px;
}
.continuation-lead {
  margin: 10px 0 6px;
  font-size: 14px;
  font-weight: 600;
  color: var(--accent-light);
}
.continuation-desc {
  margin: 0;
  font-size: 13px;
  line-height: 1.65;
  color: var(--text-secondary);
}

.ai-status-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
  font-size: 13px;
  color: var(--text-secondary);
}
.ai-brand {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-weight: 700;
  color: #fff;
}
.ai-brand.small {
  font-size: 12px;
}
.ai-brand-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: linear-gradient(135deg, #818cf8, #6366f1);
}
.ai-status {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--text-muted);
}
.ai-status-row .ttft-hint {
  width: 100%;
  flex-basis: 100%;
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-muted);
  font-weight: 400;
}
.spin {
  width: 14px;
  height: 14px;
  border: 2px solid var(--primary-glow);
  border-top-color: var(--primary-light);
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.outline-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 18px 18px 14px;
  margin-bottom: 20px;
  box-shadow: var(--shadow-md);
}
.outline-card-actions {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid var(--border);
}
/* 流式阶段：纯文本，避免 Markdown 每帧全量解析阻塞渲染 */
.outline-plain {
  margin: 0;
  font-family: ui-sans-serif, system-ui, sans-serif;
  font-size: 13px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--text-secondary);
  max-height: min(52vh, 520px);
  overflow-y: auto;
}

.outline-md {
  margin: 0;
  font-size: 14px;
  line-height: 1.7;
  color: var(--text-secondary);
  max-height: min(52vh, 520px);
  overflow-y: auto;
}
.outline-md :deep(h1),
.outline-md :deep(h2),
.outline-md :deep(h3),
.outline-md :deep(h4) {
  margin: 1em 0 0.5em;
  font-weight: 700;
  color: var(--text-primary);
  line-height: 1.35;
}
.outline-md :deep(h1) {
  font-size: 1.35rem;
}
.outline-md :deep(h2) {
  font-size: 1.2rem;
}
.outline-md :deep(h3) {
  font-size: 1.08rem;
}
.outline-md :deep(h4) {
  font-size: 1rem;
  color: var(--text-secondary);
}
.outline-md :deep(p) {
  margin: 0.5em 0;
}
.outline-md :deep(strong),
.outline-md :deep(b) {
  font-weight: 700;
  color: var(--text-primary);
}
.outline-md :deep(em),
.outline-md :deep(i) {
  font-style: italic;
  color: var(--text-secondary);
}
.outline-md :deep(ul),
.outline-md :deep(ol) {
  margin: 0.5em 0 0.5em 1.2em;
  padding: 0;
}
.outline-md :deep(li) {
  margin: 0.25em 0;
}
.outline-md :deep(blockquote) {
  margin: 0.6em 0;
  padding: 0.4em 0.75em;
  border-left: 3px solid var(--primary-light);
  background: var(--bg-muted);
  color: var(--text-secondary);
}
.outline-md :deep(hr) {
  border: none;
  border-top: 1px solid var(--border);
  margin: 1rem 0;
}

/* AI 结构化标记 → 柔和分割线（与沉浸式深色卡片协调） */
.outline-md :deep(.ai-outline-sep) {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 1.35rem 0;
  min-height: 12px;
  user-select: none;
}
.outline-md :deep(.ai-outline-sep__arm) {
  flex: 1;
  height: 1px;
  border-radius: 1px;
  background: linear-gradient(
    90deg,
    transparent 0%,
    rgba(129, 140, 248, 0.38) 42%,
    rgba(129, 140, 248, 0.38) 58%,
    transparent 100%
  );
  opacity: 0.85;
}
.outline-md :deep(.ai-outline-sep__arm--muted) {
  background: linear-gradient(
    90deg,
    transparent,
    rgba(148, 163, 184, 0.22) 50%,
    transparent
  );
  height: 1px;
  opacity: 0.9;
}
.outline-md :deep(.ai-outline-sep__arm--accent) {
  background: linear-gradient(
    90deg,
    transparent,
    rgba(251, 191, 36, 0.28) 45%,
    rgba(245, 158, 11, 0.22) 50%,
    rgba(251, 191, 36, 0.2) 55%,
    transparent
  );
  height: 1px;
}
.outline-md :deep(.ai-outline-sep__arm--fine) {
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.1) 50%, transparent);
  height: 1px;
  max-width: 42%;
  flex: 1;
  opacity: 0.75;
}
.outline-md :deep(.ai-outline-sep__chip) {
  flex-shrink: 0;
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  color: rgba(165, 180, 252, 0.88);
  padding: 5px 12px;
  border-radius: 999px;
  background: linear-gradient(135deg, rgba(99, 102, 241, 0.16), rgba(30, 27, 50, 0.5));
  border: 1px solid rgba(129, 140, 248, 0.28);
  box-shadow: 0 0 0 1px rgba(0, 0, 0, 0.2) inset;
}
.outline-md :deep(.ai-outline-sep__chip--muted) {
  color: rgba(148, 163, 184, 0.9);
  border-color: rgba(148, 163, 184, 0.22);
  background: linear-gradient(135deg, rgba(30, 41, 59, 0.45), rgba(15, 23, 42, 0.35));
  letter-spacing: 0.12em;
  text-transform: none;
  font-size: 11px;
  font-weight: 500;
}
.outline-md :deep(.ai-outline-sep__pill) {
  flex-shrink: 0;
  font-size: 12px;
  font-weight: 700;
  color: rgba(254, 243, 199, 0.95);
  padding: 4px 14px;
  border-radius: 8px;
  background: rgba(120, 53, 15, 0.28);
  border: 1px solid rgba(245, 158, 11, 0.32);
  letter-spacing: 0.04em;
}
.outline-md :deep(.ai-outline-sep__glyph) {
  flex-shrink: 0;
  font-size: 9px;
  line-height: 1;
  color: rgba(148, 163, 184, 0.45);
  opacity: 0.9;
}
.outline-md :deep(.ai-outline-sep--pci-start) {
  margin-top: 0.5rem;
}
.outline-md :deep(.ai-outline-sep--ep-end) {
  margin-bottom: 0.75rem;
  gap: 10px;
}
.outline-md :deep(.ai-outline-sep--ep-end .ai-outline-sep__arm--fine) {
  max-width: 38%;
}
.outline-md :deep(code) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 0.88em;
  padding: 0.15em 0.4em;
  border-radius: var(--radius-sm);
  background: var(--bg-muted);
  border: 1px solid var(--border);
  color: var(--primary-light);
}
.outline-md :deep(pre) {
  margin: 0.75em 0;
  padding: 12px 14px;
  border-radius: var(--radius-sm);
  background: var(--bg-muted);
  border: 1px solid var(--border);
  overflow-x: auto;
}
.outline-md :deep(pre code) {
  padding: 0;
  border: none;
  background: none;
  color: var(--text-secondary);
}
.outline-md :deep(a) {
  color: var(--accent-light);
  text-decoration: none;
}
.outline-md :deep(a:hover) {
  text-decoration: underline;
}
.outline-md :deep(table) {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
  margin: 0.75em 0;
}
.outline-md :deep(th),
.outline-md :deep(td) {
  border: 1px solid var(--border);
  padding: 6px 10px;
  text-align: left;
}
.outline-md :deep(th) {
  background: var(--bg-muted);
}
.chat-extra {
  margin-bottom: 16px;
}
.chat-extra.user {
  display: flex;
  justify-content: flex-end;
}
.ai-line {
  padding: 12px 0;
}
.ai-text {
  margin: 8px 0 0;
  font-size: 14px;
  line-height: 1.65;
  color: var(--text-secondary);
}

.stream-err {
  color: var(--color-danger);
  font-size: 13px;
  padding: 12px;
  border-radius: var(--radius-sm);
  background: rgba(127, 29, 29, 0.25);
  border: 1px solid rgba(248, 113, 113, 0.25);
}

.composer-bottom {
  flex-shrink: 0;
  padding: 12px 16px 20px;
  border-top: 1px solid var(--border);
  background: var(--bg-card);
}
.composer-inner {
  max-width: 900px;
  margin: 0 auto;
  display: flex;
  align-items: flex-end;
  gap: 10px;
  padding: 10px 14px;
  border-radius: var(--radius-lg);
  background: var(--bg-muted);
  border: 1px solid var(--border);
}
.attach-btn {
  flex-shrink: 0;
  width: 40px;
  height: 40px;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}
.attach-btn:hover {
  background: var(--bg-card-hover);
}
.bottom-textarea {
  flex: 1;
  min-height: 44px;
  max-height: 160px;
  resize: none;
  border: none;
  outline: none;
  background: transparent;
  font-size: 14px;
  line-height: 1.5;
  color: var(--text-primary);
  font-family: inherit;
  padding: 8px 0;
}
.bottom-textarea::placeholder {
  color: var(--text-secondary);
}
.send-round {
  flex-shrink: 0;
  width: 44px;
  height: 44px;
  border-radius: 50%;
  border: none;
  cursor: pointer;
  background: var(--text-primary);
  color: var(--bg-page);
  display: flex;
  align-items: center;
  justify-content: center;
  transition: transform 0.12s, opacity 0.12s;
}
.send-round:hover:not(:disabled) {
  transform: scale(1.05);
}
.send-round:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
</style>

<style>
/* append-to-body 对话框不受 scoped 限制 */
.immersive-episode-dialog.el-dialog {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg);
}
.immersive-episode-dialog .el-dialog__header {
  padding: 16px 18px 6px;
  margin: 0;
}
.immersive-episode-dialog .el-dialog__title {
  color: var(--text-primary);
  font-weight: 700;
  font-size: 15px;
}
.immersive-episode-dialog .el-dialog__headerbtn .el-dialog__close {
  color: var(--text-secondary);
}
.immersive-episode-dialog .el-dialog__body {
  padding: 8px 18px 4px;
  color: var(--text-secondary);
}
.immersive-episode-dialog .el-dialog__footer {
  padding: 10px 18px 16px;
  border-top: 1px solid var(--border);
}
.ep-dialog-desc {
  margin: 0 0 12px;
  font-size: 12px;
  line-height: 1.55;
  color: var(--text-secondary);
}
.ep-dialog-ref {
  padding: 12px 12px 14px;
  border-radius: var(--radius-md);
  background: var(--bg-muted);
  border: 1px solid var(--border);
}
.ep-dialog-ref-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
  line-height: 1.45;
  margin-bottom: 8px;
}
.ep-dialog-ref-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  color: var(--text-secondary);
}
.ep-dialog-ver {
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.06em;
  padding: 2px 8px;
  border-radius: 6px;
  background: var(--primary-glow);
  color: var(--primary-light);
  border: 1px solid var(--primary);
}
.ep-dialog-empty {
  font-size: 12px;
  line-height: 1.55;
  color: var(--text-secondary);
}
.ep-dialog-footer-inner {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 12px;
  align-items: stretch;
}
.ep-dialog-footer-hint {
  font-size: 11px;
  color: var(--text-muted);
}
.ep-dialog-footer-btns {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}
.btn-ep-cancel {
  padding: 8px 16px;
  border-radius: var(--radius-md);
  border: 1px solid var(--border);
  background: transparent;
  color: var(--text-primary);
  font-size: 13px;
  cursor: pointer;
}
.btn-ep-cancel:hover {
  background: var(--bg-muted);
}
.btn-ep-primary {
  padding: 8px 18px;
  border-radius: var(--radius-md);
  border: none;
  background: var(--text-primary);
  color: var(--bg-page);
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
}
.btn-ep-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.step-confirm-actions {
  display: flex;
  justify-content: flex-end;
  padding: 12px 0;
}
.btn-confirm-step {
  min-width: 132px;
  min-height: 52px;
  padding: 0 24px;
  border-radius: 18px;
  border: none;
  background: linear-gradient(135deg, #7c86ff, #6672f4);
  color: #fff;
  font-size: 16px;
  font-weight: 700;
  cursor: pointer;
  transition: transform 0.15s, box-shadow 0.15s;
  box-shadow: 0 10px 24px rgba(99, 102, 241, 0.28);
}
.btn-confirm-step:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 14px 30px rgba(99, 102, 241, 0.38);
}
.btn-confirm-step:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

/* 角色图片画廊 */
.char-gallery-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 12px;
}
.char-gallery-item {
  position: relative;
  border-radius: var(--radius-md);
  overflow: hidden;
  aspect-ratio: 3 / 4;
  background: var(--bg-muted);
}
.char-gallery-item img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.char-gallery-tag {
  position: absolute;
  top: 6px;
  left: 6px;
  background: var(--primary);
  color: #fff;
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 4px;
}

/* 镜头多选弹出层 */
.shot-select-popover {
  background: var(--bg-card) !important;
  border: 1px solid var(--border) !important;
  border-radius: var(--radius-lg) !important;
  box-shadow: var(--shadow-lg) !important;
  padding: 0 !important;
}
.shot-select-popover .el-popover__arrow { display: none; }
.shot-select-panel { padding: 8px; }
.shot-select-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 6px 8px;
  border-bottom: 1px solid var(--border);
  margin-bottom: 6px;
}
.shot-select-all-btn {
  border: none;
  background: var(--primary-glow);
  color: var(--primary-light);
  font-size: 11px;
  font-weight: 600;
  padding: 4px 10px;
  border-radius: var(--radius-sm);
  cursor: pointer;
}
.shot-select-all-btn:hover { background: rgba(99,102,241,0.3); }
.shot-select-count {
  font-size: 11px;
  color: var(--text-secondary);
}
.shot-select-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: min(50vh, 360px);
  overflow-y: auto;
}
.shot-select-item {
  display: flex;
  align-items: center;
  padding: 6px;
  border-radius: var(--radius-sm);
  margin: 0;
  height: auto;
}
.shot-select-item:hover { background: var(--bg-muted); }
.shot-select-item .el-checkbox__label { display: flex; align-items: center; gap: 8px; }
.shot-select-item-no { font-size: 12px; font-weight: 600; color: var(--text-primary); }
.shot-select-item-status {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: var(--radius-full);
  background: var(--primary-glow);
  color: var(--primary-light);
}
.shot-select-item-status--done {
  background: rgba(52,211,153,0.15);
  color: #6ee7b7;
}
</style>
