<template>
  <div class="wb-root">
    <header class="wb-top">
      <button type="button" class="wb-back" @click="goBack">返回策划</button>
      <span v-if="projectName" class="wb-title">{{ projectName }} · 第 {{ epLabel }} 集</span>
      <span v-else class="wb-title">镜头工作台</span>
      <span v-if="isVideoTab" class="wb-title-tag">成片预览</span>
    </header>

    <div class="editor-thread">
      <aside class="wb-chat" aria-label="分镜对话">
        <div class="wb-chat-head">AI 分镜助手</div>
        <div class="wb-chat-messages">
          <p v-for="(m, i) in chatMessages" :key="i" :class="['wb-msg', m.role]">{{ m.text }}</p>
        </div>
        <div class="wb-chat-input-wrap">
          <textarea
            v-model="chatInput"
            class="wb-chat-input"
            rows="3"
            placeholder="描述希望如何调整当前镜头（后续可对接修复接口）"
            @keydown.enter.exact.prevent="sendChatStub"
          />
          <button type="button" class="wb-chat-send" @click="sendChatStub">发送</button>
        </div>
      </aside>

      <div class="wb-rail" aria-label="编辑类型">
        <button
          type="button"
          class="wb-rail-btn"
          :class="{ active: leftTab === 'frame' }"
          title="画面"
          @click="leftTab = 'frame'"
        >
          <span class="wb-rail-ico" aria-hidden="true">▣</span>
          <span class="wb-rail-lbl">画面</span>
        </button>
        <button
          type="button"
          class="wb-rail-btn"
          :class="{ active: leftTab === 'dub' }"
          title="配音"
          @click="leftTab = 'dub'"
        >
          <span class="wb-rail-ico" aria-hidden="true">♪</span>
          <span class="wb-rail-lbl">配音</span>
        </button>
        <button
          type="button"
          class="wb-rail-btn"
          :class="{ active: leftTab === 'music' }"
          title="音乐"
          @click="leftTab = 'music'"
        >
          <span class="wb-rail-ico" aria-hidden="true">♫</span>
          <span class="wb-rail-lbl">音乐</span>
        </button>
      </div>

      <div class="wb-side-panel">
        <template v-if="leftTab === 'frame'">
          <div class="wb-panel-head">分镜 {{ activeShot?.shotNo ?? '—' }}</div>
          <div class="wb-panel-section">
            <label class="wb-label">图片提示词</label>
            <p class="wb-panel-body">{{ activeShot?.imagePrompt || activeShot?.description || '暂无' }}</p>
          </div>
          <div class="wb-panel-section">
            <label class="wb-label">画面描述</label>
            <p class="wb-panel-body">{{ activeShot?.description || '—' }}</p>
          </div>
          <div v-if="activeShot?.dynamicSelected" class="wb-badge">动态镜头</div>
        </template>
        <template v-else-if="leftTab === 'dub'">
          <div class="wb-panel-head">分镜 {{ activeShot?.shotNo ?? '—' }}</div>
          <div class="wb-panel-section">
            <label class="wb-label">旁白 / 台词</label>
            <p class="wb-panel-body">{{ activeShot?.resolvedTts || activeShot?.dialogue || activeShot?.narration || '暂无' }}</p>
          </div>
          <p class="wb-muted">试听、音色与语速等可在「分镜制作」页精细调节。</p>
        </template>
        <template v-else>
          <div class="wb-panel-head">音乐</div>
          <p class="wb-muted">AI 配乐与曲库应用后续接入；此处为布局占位。</p>
          <ul class="wb-music-placeholder">
            <li>剑出禁地：觉醒之光 <span class="wb-time">03:23</span></li>
            <li>剑灵觉醒：逆袭序章 <span class="wb-time">03:12</span></li>
          </ul>
        </template>
      </div>

      <div class="wb-main">
        <div v-if="isVideoTab" class="wb-video-tab-actions">
          <span class="wb-video-tab-hint">以下为合成导出产生的项目成片</span>
          <button type="button" class="wb-link-btn" @click="switchToShotEditing">镜头剪辑</button>
          <button type="button" class="wb-link-btn" @click="goSynthesis">合成导出</button>
        </div>
        <div class="wb-canvas">
          <template v-if="isVideoTab && composeVideoUrl">
            <video class="wb-media wb-compose-player" :src="composeVideoUrl" controls playsinline />
          </template>
          <template v-else-if="isVideoTab && !composeVideoUrl">
            <div class="wb-canvas-empty">
              <p>暂无成片地址，请先在「合成导出」完成合成。</p>
              <button type="button" class="wb-link-btn wb-link-btn--primary" @click="goSynthesis">前往合成导出</button>
            </div>
          </template>
          <template v-else-if="displayShotVideoUrl">
            <video class="wb-media" :src="displayShotVideoUrl" controls playsinline />
          </template>
          <template v-else-if="displayImageUrl">
            <img class="wb-media" :src="displayImageUrl" alt="镜头画面" />
          </template>
          <div v-else class="wb-canvas-empty">素材生成中或未就绪</div>
        </div>
        <div class="wb-external-panel">
          <div class="wb-external-title">外站分镜视频</div>
          <p class="wb-external-tip">
            粘贴其他平台生成的镜头视频直链（建议 https · mp4），保存后与当前选中镜头关联，成片合成时可使用。
          </p>
          <div class="wb-external-row">
            <input
              v-model="externalVideoUrl"
              type="url"
              class="wb-external-input"
              placeholder="https://example.com/clip.mp4"
              autocomplete="off"
            />
            <button
              type="button"
              class="wb-external-btn"
              :disabled="!activeShot?.id || externalSaving"
              @click="saveExternalVideo"
            >
              {{ externalSaving ? '保存中…' : '保存关联' }}
            </button>
          </div>
        </div>

        <div class="wb-filmstrip-wrap">
          <div ref="stripRef" class="wb-filmstrip">
            <button
              v-for="(shot, idx) in shots"
              :key="shot.id"
              type="button"
              class="wb-thumb"
              :class="{ active: idx === activeIndex }"
              @click="activeIndex = idx"
            >
              <img v-if="thumbUrl(shot)" :src="thumbUrl(shot)!" alt="" />
              <span v-else class="wb-thumb-ph">{{ shot.shotNo }}</span>
              <span class="wb-thumb-no">{{ shot.shotNo }}</span>
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { projectApi } from '@/api/project'
import { scriptApi } from '@/api/script'
import { storyboardApi } from '@/api/storyboard'
import { videoApi } from '@/api/video'

const route = useRoute()
const router = useRouter()

const projectId = computed(() => String(route.params.id || ''))
const episodeNo = computed(() => {
  const q = Number(route.query.episode)
  return Number.isFinite(q) && q >= 1 ? q : 1
})

/** 策划页「查看视频」进入：内嵌项目成片 */
const isVideoTab = computed(() => String(route.query.tab || '').toLowerCase() === 'video')

const projectName = ref('')
const composeVideoUrl = ref('')
const scripts = ref<any[]>([])
const shots = ref<any[]>([])
const activeIndex = ref(0)
const leftTab = ref<'frame' | 'dub' | 'music'>('frame')
const stripRef = ref<HTMLElement | null>(null)
const externalVideoUrl = ref('')
const externalSaving = ref(false)

const chatMessages = ref<Array<{ role: 'user' | 'ai'; text: string }>>([
  { role: 'ai', text: '你好，这里是本集镜头工作台。下方横条可切换镜头；左侧可切换画面 / 配音 / 音乐面板。' },
])
const chatInput = ref('')

const epLabel = computed(() => String(episodeNo.value).padStart(2, '0'))

const activeShot = computed(() => shots.value[activeIndex.value] ?? null)

const displayImageUrl = computed(() => {
  const s = activeShot.value
  if (!s) return ''
  return s.imageUrl && String(s.imageUrl).trim() ? String(s.imageUrl) : ''
})

/** 当前镜头已有关联视频（含外站导入或本系统生成） */
const displayShotVideoUrl = computed(() => {
  const s = activeShot.value
  if (!s) return ''
  const u = s.videoUrl
  return u && String(u).trim() ? String(u) : ''
})

function thumbUrl(shot: any): string | null {
  if (shot?.videoUrl && String(shot.videoUrl).trim()) return String(shot.videoUrl)
  if (shot?.imageUrl && String(shot.imageUrl).trim()) return String(shot.imageUrl)
  return null
}

async function loadData() {
  const [pr, sr, ovRes] = await Promise.all([
    projectApi.get(projectId.value),
    scriptApi.listByProject(projectId.value),
    videoApi.getOverview(projectId.value).catch(() => null),
  ])
  projectName.value = (pr as any).data?.data?.name ?? (pr as any).data?.name ?? ''
  if (ovRes) {
    const o = (ovRes as any).data?.data ?? {}
    const u = o.videoUrl
    composeVideoUrl.value = u && String(u).trim() ? String(u) : ''
  } else {
    composeVideoUrl.value = ''
  }

  const list = (sr as any).data?.data ?? (sr as any).data ?? []
  scripts.value = Array.isArray(list) ? list : []
  const sc = scripts.value.find((s: any) => Number(s.episodeNo) === episodeNo.value)
  if (!sc?.id) {
    shots.value = []
    if (!isVideoTab.value || !composeVideoUrl.value) {
      ElMessage.warning('未找到本集剧本，请返回策划页确认')
    }
    return
  }
  const sbRes = await storyboardApi.listByScript(sc.id)
  const rows = (sbRes as any).data?.data ?? []
  shots.value = Array.isArray(rows)
    ? [...rows].sort(
        (a: any, b: any) => (Number(a.shotNo) || 0) - (Number(b.shotNo) || 0),
      )
    : []
  activeIndex.value = 0
  syncExternalInputFromShot()
}

function syncExternalInputFromShot() {
  const s = activeShot.value
  externalVideoUrl.value = s?.videoUrl && String(s.videoUrl).trim() ? String(s.videoUrl).trim() : ''
}

async function saveExternalVideo() {
  const s = activeShot.value
  if (!s?.id) return
  externalSaving.value = true
  try {
    const url = externalVideoUrl.value.trim()
    await storyboardApi.update(s.id, { videoUrl: url || '' })
    ElMessage.success(url ? '已关联外站镜头视频' : '已清空本镜头视频地址')
    await loadData()
    const idx = shots.value.findIndex((x: any) => x.id === s.id)
    if (idx >= 0) activeIndex.value = idx
    syncExternalInputFromShot()
  } catch (e: unknown) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    externalSaving.value = false
  }
}

function goBack() {
  router.push({ path: `/projects/${projectId.value}/immersive`, query: { episode: String(episodeNo.value) } })
}

function switchToShotEditing() {
  router.replace({
    path: route.path,
    query: { episode: route.query.episode },
  })
}

function goSynthesis() {
  router.push(`/projects/${projectId.value}/synthesis`)
}

function sendChatStub() {
  const t = chatInput.value.trim()
  if (!t) return
  chatMessages.value.push({ role: 'user', text: t })
  chatInput.value = ''
  chatMessages.value.push({
    role: 'ai',
    text: '已记录你的想法；镜头级修改请优先在「分镜制作」使用编辑能力，此处将逐步接入对话式修改。',
  })
}

watch(activeIndex, () => {
  syncExternalInputFromShot()
  const el = stripRef.value?.querySelector('.wb-thumb.active')
  el?.scrollIntoView({ behavior: 'smooth', inline: 'center', block: 'nearest' })
})

onMounted(() => {
  loadData().catch(() => ElMessage.error('加载失败'))
})
</script>

<style scoped>
.wb-root {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background: var(--bg-page);
  color: var(--text-primary);
}

.wb-top {
  flex-shrink: 0;
  height: 52px;
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: nowrap;
  min-width: 0;
  padding: 0 14px;
  border-bottom: 1px solid var(--border);
  background: var(--bg-card);
}

.wb-back {
  border: none;
  background: var(--bg-muted);
  color: var(--text-primary);
  padding: 8px 14px;
  border-radius: var(--radius-md);
  cursor: pointer;
  font-size: 13px;
}
.wb-back:hover {
  background: var(--bg-card-hover);
}

.wb-title {
  font-size: 14px;
  font-weight: 700;
  color: var(--text-primary);
}

.wb-title-tag {
  margin-left: auto;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.06em;
  color: var(--accent);
  padding: 4px 10px;
  border-radius: var(--radius-full);
  border: 1px solid var(--accent);
  background: rgba(6, 182, 212, 0.08);
}

.wb-video-tab-actions {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  padding-bottom: 8px;
}

.wb-video-tab-hint {
  font-size: 12px;
  color: var(--text-secondary);
  flex: 1;
  min-width: 0;
}

.wb-link-btn {
  border: none;
  background: var(--bg-muted);
  color: var(--primary);
  padding: 6px 12px;
  border-radius: var(--radius-md);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}
.wb-link-btn:hover {
  background: var(--bg-card-hover);
}

.wb-link-btn--primary {
  margin-top: 12px;
  background: var(--primary);
  color: #fff;
}

.wb-compose-player {
  background: #000;
}

.wb-canvas-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  text-align: center;
  padding: 16px;
  color: var(--text-secondary);
  font-size: 13px;
}

.editor-thread {
  flex: 1;
  display: flex;
  flex-direction: row;
  flex-wrap: nowrap;
  min-height: 0;
  padding: 10px 12px 12px;
  gap: 10px;
}

.wb-chat {
  flex: 0 0 260px;
  display: flex;
  flex-direction: column;
  min-width: 0;
  border-radius: var(--radius-lg);
  border: 1px solid var(--border);
  background: var(--bg-card);
  overflow: hidden;
}

.wb-chat-head {
  padding: 12px 14px;
  font-size: 13px;
  font-weight: 700;
  border-bottom: 1px solid var(--border);
}

.wb-chat-messages {
  flex: 1;
  min-height: 120px;
  overflow-y: auto;
  padding: 12px;
  font-size: 12px;
  line-height: 1.55;
}

.wb-msg.ai {
  color: var(--text-secondary);
  margin-bottom: 10px;
}
.wb-msg.user {
  color: var(--primary);
  margin-bottom: 10px;
}

.wb-chat-input-wrap {
  padding: 10px;
  border-top: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.wb-chat-input {
  width: 100%;
  resize: none;
  border-radius: var(--radius-md);
  border: 1px solid var(--border);
  background: var(--bg-muted);
  color: var(--text-primary);
  padding: 8px 10px;
  font-size: 12px;
}

.wb-chat-send {
  align-self: flex-end;
  border: none;
  border-radius: var(--radius-md);
  padding: 8px 16px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  background: var(--primary);
  color: #fff;
}

.wb-rail {
  flex: 0 0 52px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding-top: 8px;
}

.wb-rail-btn {
  width: 100%;
  border: none;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 10px 4px;
  border-radius: var(--radius-md);
}
.wb-rail-btn:hover {
  color: var(--text-primary);
  background: var(--bg-muted);
}
.wb-rail-btn.active {
  color: var(--primary);
  background: var(--primary-glow);
}

.wb-rail-ico {
  font-size: 16px;
  line-height: 1;
}

.wb-rail-lbl {
  font-size: 11px;
}

.wb-side-panel {
  flex: 0 0 min(376px, 34vw);
  border-radius: var(--radius-xl);
  border: 1px solid var(--border);
  background: var(--bg-card);
  padding: 16px 14px;
  overflow-y: auto;
  min-width: 0;
}

.wb-panel-head {
  font-size: 15px;
  font-weight: 700;
  margin-bottom: 14px;
}

.wb-panel-section {
  margin-bottom: 14px;
}

.wb-label {
  display: block;
  font-size: 11px;
  color: var(--text-secondary);
  margin-bottom: 6px;
}

.wb-panel-body {
  margin: 0;
  font-size: 13px;
  line-height: 1.55;
  color: var(--text-primary);
  white-space: pre-wrap;
  word-break: break-word;
}

.wb-muted {
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.5;
}

.wb-badge {
  display: inline-block;
  margin-top: 8px;
  font-size: 11px;
  padding: 4px 10px;
  border-radius: var(--radius-full);
  background: var(--primary-glow);
  color: var(--primary-light);
}

.wb-music-placeholder {
  list-style: none;
  padding: 0;
  margin: 12px 0 0;
  font-size: 13px;
  color: var(--text-secondary);
}
.wb-music-placeholder li {
  padding: 8px 0;
  border-bottom: 1px solid var(--border);
  display: flex;
  justify-content: space-between;
  gap: 8px;
}
.wb-time {
  color: var(--text-muted);
  font-size: 12px;
}

.wb-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  gap: 10px;
}

.wb-canvas {
  flex: 1;
  min-height: 200px;
  border-radius: var(--radius-lg);
  border: 1px solid var(--border);
  background: #000;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.wb-media {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
}

.wb-external-panel {
  flex-shrink: 0;
  padding: 12px 14px;
  border-radius: var(--radius-lg);
  border: 1px solid var(--primary);
  background: var(--primary-glow);
}
.wb-external-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--primary-light);
  margin-bottom: 6px;
}
.wb-external-tip {
  margin: 0 0 10px;
  font-size: 11px;
  line-height: 1.45;
  color: var(--text-secondary);
}
.wb-external-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}
.wb-external-input {
  flex: 1;
  min-width: 0;
  padding: 10px 12px;
  border-radius: var(--radius-md);
  border: 1px solid var(--border);
  background: var(--bg-muted);
  color: var(--text-primary);
  font-size: 12px;
}
.wb-external-btn {
  flex-shrink: 0;
  border: none;
  border-radius: var(--radius-md);
  padding: 10px 16px;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  color: #fff;
  background: var(--primary);
}
.wb-external-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.wb-filmstrip-wrap {
  flex-shrink: 0;
  overflow-x: auto;
  padding-bottom: 4px;
}

.wb-filmstrip {
  display: flex;
  flex-direction: row;
  flex-wrap: nowrap;
  gap: 10px;
  padding: 4px 2px;
}

.wb-thumb {
  position: relative;
  flex: 0 0 96px;
  width: 96px;
  height: 72px;
  border-radius: var(--radius-md);
  border: 2px solid transparent;
  padding: 0;
  overflow: hidden;
  cursor: pointer;
  background: var(--bg-card);
}
.wb-thumb.active {
  border-color: var(--primary);
  box-shadow: 0 0 0 1px var(--primary-glow);
}

.wb-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.wb-thumb-ph {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  font-size: 18px;
  font-weight: 700;
  color: var(--text-muted);
}

.wb-thumb-no {
  position: absolute;
  left: 6px;
  bottom: 4px;
  font-size: 10px;
  font-weight: 700;
  color: #fff;
  text-shadow: 0 1px 3px rgba(0, 0, 0, 0.85);
}

@media (max-width: 1100px) {
  .wb-chat {
    display: none;
  }
}
</style>
