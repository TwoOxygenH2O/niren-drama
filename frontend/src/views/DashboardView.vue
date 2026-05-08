<template>
  <div class="dashboard-immersive" v-loading="creatingProject" element-loading-text="正在创建项目…">
    <div class="dashboard-bg" aria-hidden="true" />
    <div class="dashboard-inner" :class="{ 'dashboard-inner--expanded': expanded }">
      <h1 class="dashboard-headline">有什么新的故事灵感？</h1>
      <p class="dashboard-sub">你好，{{ userStore.userInfo?.nickname || '创作者' }} · 泥人剧场</p>

      <div
        class="inspire-bar-wrap"
        :class="{ 'inspire-bar-wrap--expanded': expanded }"
        @click="onWrapClick"
      >
        <Transition name="inspire-swap" mode="out-in">
          <!-- Collapsed: single pill row（点击除「发送」外区域展开） -->
          <div v-if="!expanded" key="compact" class="inspire-bar inspire-bar--compact">
            <input
              v-model="inspiration"
              type="text"
              class="inspire-input"
              placeholder="输入你的灵感，AI 会为你自动规划内容生成视频"
              maxlength="500"
              readonly
              tabindex="-1"
              @keydown.enter.prevent="goFromInspiration"
            />
            <button type="button" class="inspire-submit" title="开始创作" @click.stop="goFromInspiration">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                <line x1="12" y1="19" x2="12" y2="5" />
                <polyline points="5 12 12 5 19 12" />
              </svg>
            </button>
          </div>

          <!-- Expanded: full composer -->
          <div v-else key="expanded" class="inspire-bar inspire-bar--expanded" @click.stop>
          <button type="button" class="composer-collapse" title="收起" aria-label="收起" @click="collapseComposer">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
              <polyline points="6 15 12 9 18 15" />
            </svg>
          </button>

          <div class="composer-body">
            <div class="composer-input-row">
              <span class="composer-prefix"><span class="composer-prefix-accent">短剧原创</span><span class="composer-slash">/</span></span>
              <textarea
                ref="textareaRef"
                v-model="inspiration"
                class="composer-textarea"
                placeholder="输入你的灵感，AI 会为你自动策划内容生成视频"
                maxlength="500"
                rows="3"
                @keydown.ctrl.enter.prevent="goFromInspiration"
              />
            </div>

            <div class="composer-toolbar">
              <div class="toolbar-icons" aria-hidden="true">
                <button type="button" class="tool-ico" title="附件">
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
                    <path d="M21.44 11.05l-9.19 9.19a6 6 0 01-8.49-8.49l9.19-9.19a4 4 0 015.66 5.66l-9.2 9.19a2 2 0 01-2.83-2.83l8.49-8.48" />
                  </svg>
                </button>
                <button type="button" class="tool-ico" title="素材">
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                    <path d="M12 2l9 5v10l-9 5-9-5V7l9-5z" />
                    <path d="M12 22V12M12 12L3 7M12 12l9-5" />
                  </svg>
                </button>
                <button type="button" class="tool-ico" title="提及">
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                    <circle cx="12" cy="12" r="4" />
                    <path d="M16 8v5a4 4 0 01-8 0v-1" />
                    <path d="M16 12h1.5a2.5 2.5 0 010 5H17" />
                  </svg>
                </button>
                <button type="button" class="tool-ico" title="对话">
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                    <path d="M21 11.5a8.38 8.38 0 01-.9 3.8 8.5 8.5 0 01-7.6 4.7 8.38 8.38 0 01-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 01-.9-3.8 8.5 8.5 0 018.5-8.5h.5a8.48 8.48 0 018 8.5z" />
                  </svg>
                </button>
              </div>
              <div class="toolbar-center">
                <button type="button" class="mode-pill" title="当前默认文生视频模型（来自 AI 配置）">
                  <span class="mode-pill-text">{{ videoModelLabel }}</span>
                  <span class="mode-pill-new">NEW</span>
                </button>
              </div>
              <div class="toolbar-right">
                <span class="multi-label">多剧集</span>
                <el-switch v-model="multiEpisode" size="small" />
                <button type="button" class="send-pill" title="生成 / 前往项目" @click="goFromInspiration">
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                    <line x1="12" y1="19" x2="12" y2="5" />
                    <polyline points="5 12 12 5 19 12" />
                  </svg>
                </button>
              </div>
            </div>
          </div>
        </div>
        </Transition>
      </div>

      <div class="dashboard-extras" :class="{ 'dashboard-extras--visible': expanded }">
        <div class="quick-actions">
          <button type="button" class="quick-chip quick-chip--drama" @click="goFromInspiration">
            <span class="quick-chip-ico quick-chip-ico--pink" aria-hidden="true">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none"><path d="M12 3l2 4 4 .5-3 3 1 4.5L12 13l-4 2 .5-4.5-3-3L10 7l2-4z" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"/></svg>
            </span>
            短剧原创
          </button>
        </div>

        <div class="feature-cards">
          <button type="button" class="feature-card" @click="goFromInspiration">
            <div class="feature-card-visual feature-card-visual--portrait">
              <span class="thumb t1" />
              <span class="thumb t2" />
              <span class="thumb t3" />
            </div>
            <span class="feature-card-title">对话剧情</span>
          </button>
          <button type="button" class="feature-card" @click="goFromInspiration">
            <div class="feature-card-visual feature-card-visual--landscape">
              <span class="thumb t1" />
              <span class="thumb t2" />
              <span class="thumb t3" />
            </div>
            <span class="feature-card-title">旁白解说</span>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { aiConfigApi } from '@/api/aiConfig'
import { projectApi } from '@/api/project'
import { DASHBOARD_COLLAPSE_COMPOSER } from '@/constants/dashboard'
import { DEFAULT_PROJECT_TYPE } from '@/constants/project'

const router = useRouter()
const userStore = useUserStore()
const inspiration = ref('')
const expanded = ref(false)
const multiEpisode = ref(false)
const textareaRef = ref<HTMLTextAreaElement | null>(null)
const videoModelName = ref('')
const creatingProject = ref(false)

const INSPIRATION_KEY = 'niren.dashboard.inspiration'
const MULTI_EPISODE_KEY = 'niren.dashboard.multiEpisode'

/** 与 AI 配置中心「文生视频」默认项的模型名称一致 */
const videoModelLabel = computed(() => {
  const m = videoModelName.value.trim()
  if (m) return `${m} 全能模式`
  return '请在 AI 配置中添加文生视频模型'
})

async function loadDefaultVideoModel() {
  try {
    const res = await aiConfigApi.list()
    const list = (res.data?.data || []) as Array<{
      configType?: string
      isDefault?: number
      model?: string
    }>
    const videos = list.filter((c) => c.configType === 'video')
    const def = videos.find((c) => c.isDefault === 1) || videos[0]
    videoModelName.value = (def?.model || '').trim()
  } catch {
    videoModelName.value = ''
  }
}

function collapseComposer() {
  expanded.value = false
}

function expandComposer() {
  if (expanded.value) return
  expanded.value = true
  loadDefaultVideoModel()
  nextTick(() => textareaRef.value?.focus())
}

function onExternalCollapse() {
  collapseComposer()
}

function onWindowWheel(e: WheelEvent) {
  if (!expanded.value) return
  const t = e.target
  if (t instanceof HTMLTextAreaElement) {
    if (t.scrollHeight > t.clientHeight + 1 && t.scrollTop > 0) {
      return
    }
  }
  const main = document.querySelector('main.page-main') as HTMLElement | null
  if (main && main.scrollTop > 2) return
  if (e.deltaY >= 0) return
  collapseComposer()
}

onMounted(() => {
  loadDefaultVideoModel()
  window.addEventListener(DASHBOARD_COLLAPSE_COMPOSER, onExternalCollapse)
  window.addEventListener('wheel', onWindowWheel, { passive: true })
})

onBeforeUnmount(() => {
  window.removeEventListener(DASHBOARD_COLLAPSE_COMPOSER, onExternalCollapse)
  window.removeEventListener('wheel', onWindowWheel)
})

function onWrapClick() {
  if (!expanded.value) expandComposer()
}

async function goFromInspiration() {
  const text = inspiration.value.trim()
  if (!text) {
    ElMessage.warning('请先输入创作灵感')
    return
  }
  try {
    sessionStorage.setItem(MULTI_EPISODE_KEY, multiEpisode.value ? '1' : '0')
    sessionStorage.setItem(INSPIRATION_KEY, text)
  } catch {
    /* ignore */
  }

  const episodes = 20

  creatingProject.value = true
  try {
    const res = await projectApi.create({
      name: text.length > 40 ? `${text.slice(0, 37)}…` : text,
      description: text,
      projectType: DEFAULT_PROJECT_TYPE,
      genre: '',
      episodes,
      episodeDuration: 60,
    })
    const project = res.data?.data
    const pid = project?.id
    if (!pid) {
      ElMessage.error('创建项目失败')
      return
    }
    await router.push(`/projects/${pid}/episodes`)
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '创建项目失败'
    ElMessage.error(msg)
  } finally {
    creatingProject.value = false
  }
}
</script>

<style scoped>
.dashboard-immersive {
  position: relative;
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: min(720px, 100%);
  padding: 32px 20px 48px;
  overflow: hidden;
}

.dashboard-bg {
  position: absolute;
  inset: 0;
  background:
    radial-gradient(ellipse 120% 80% at 50% 120%, rgba(30, 25, 45, 0.95) 0%, transparent 55%),
    radial-gradient(ellipse 90% 60% at 70% 20%, rgba(60, 80, 120, 0.35) 0%, transparent 50%),
    radial-gradient(ellipse 70% 50% at 20% 60%, rgba(90, 60, 40, 0.2) 0%, transparent 45%),
    linear-gradient(165deg, #0d0f14 0%, #1a1528 35%, #121820 70%, #0a0c10 100%);
  pointer-events: none;
}
.dashboard-bg::after {
  content: '';
  position: absolute;
  inset: 0;
  background: url("data:image/svg+xml,%3Csvg viewBox='0 0 256 256' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.85' numOctaves='4' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)' opacity='0.06'/%3E%3C/svg%3E");
  opacity: 0.5;
  mix-blend-mode: overlay;
}

.dashboard-inner {
  position: relative;
  z-index: 1;
  width: 100%;
  max-width: 820px;
  text-align: center;
  transition:
    max-width 0.55s cubic-bezier(0.4, 0, 0.2, 1),
    transform 0.55s cubic-bezier(0.4, 0, 0.2, 1);
}
.dashboard-inner--expanded {
  max-width: 980px;
  transform: translateY(-10px);
}

.dashboard-headline {
  margin: 0 0 12px;
  font-size: clamp(26px, 4.5vw, 38px);
  font-weight: 700;
  color: #fff;
  letter-spacing: 0.02em;
  text-shadow: 0 2px 24px rgba(0, 0, 0, 0.45);
  transition: opacity 0.35s ease;
}

.dashboard-sub {
  margin: 0 0 28px;
  font-size: 14px;
  color: rgba(226, 232, 240, 0.65);
}

.inspire-bar-wrap {
  width: 100%;
  cursor: pointer;
  transition: margin 0.45s ease;
}
.inspire-bar-wrap--expanded {
  cursor: default;
}

/* Collapsed pill */
.inspire-bar--compact {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 8px 8px 22px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.14);
  box-shadow:
    0 8px 32px rgba(0, 0, 0, 0.35),
    inset 0 1px 0 rgba(255, 255, 255, 0.1);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
}

.inspire-input {
  flex: 1;
  min-width: 0;
  background: transparent;
  border: none;
  outline: none;
  font-size: 15px;
  color: #f1f5f9;
  padding: 12px 0;
  pointer-events: none;
}
.inspire-input::placeholder {
  color: rgba(148, 163, 184, 0.85);
}

.inspire-submit {
  flex-shrink: 0;
  width: 44px;
  height: 44px;
  border-radius: 50%;
  border: none;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(255, 255, 255, 0.95);
  color: #1e293b;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.25);
  transition: transform 0.15s, box-shadow 0.15s;
  pointer-events: auto;
}
.inspire-submit:hover {
  transform: scale(1.05);
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.35);
}

/* Expanded panel */
.inspire-bar--expanded {
  position: relative;
  text-align: left;
  border-radius: 20px;
  padding: 14px 16px 12px;
  background: rgba(22, 24, 32, 0.72);
  border: 1px solid rgba(255, 255, 255, 0.12);
  box-shadow:
    0 16px 48px rgba(0, 0, 0, 0.45),
    inset 0 1px 0 rgba(255, 255, 255, 0.06);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  animation: inspire-expand-in 0.5s cubic-bezier(0.22, 1, 0.36, 1) both;
}

@keyframes inspire-expand-in {
  from {
    opacity: 0.65;
    transform: translateY(12px) scale(0.985);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

.inspire-swap-enter-active,
.inspire-swap-leave-active {
  transition:
    opacity 0.42s ease,
    transform 0.48s cubic-bezier(0.4, 0, 0.2, 1);
}
.inspire-swap-enter-from {
  opacity: 0;
  transform: translateY(14px) scale(0.985);
}
.inspire-swap-leave-to {
  opacity: 0;
  transform: translateY(18px) scale(0.978);
}

.composer-collapse {
  position: absolute;
  top: 10px;
  right: 12px;
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.06);
  color: rgba(226, 232, 240, 0.85);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.15s, color 0.15s;
  z-index: 2;
}
.composer-collapse:hover {
  background: rgba(255, 255, 255, 0.12);
  color: #fff;
}

.composer-body {
  padding-right: 36px;
}

.composer-input-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 14px;
}

.composer-prefix {
  flex-shrink: 0;
  padding-top: 4px;
  font-size: 15px;
  font-weight: 600;
  white-space: nowrap;
}
.composer-prefix-accent {
  color: #22d3ee;
}
.composer-slash {
  color: rgba(148, 163, 184, 0.55);
  margin-left: 2px;
}

.composer-textarea {
  flex: 1;
  min-width: 0;
  min-height: 72px;
  max-height: 160px;
  resize: vertical;
  background: transparent;
  border: none;
  outline: none;
  font-size: 15px;
  line-height: 1.55;
  color: #f1f5f9;
  font-family: inherit;
}
.composer-textarea::placeholder {
  color: rgba(148, 163, 184, 0.75);
}

.composer-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px 12px;
  padding-top: 4px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}

.toolbar-icons {
  display: flex;
  align-items: center;
  gap: 4px;
}
.tool-ico {
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 10px;
  background: transparent;
  color: rgba(226, 232, 240, 0.75);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.15s, color 0.15s;
}
.tool-ico:hover {
  background: rgba(255, 255, 255, 0.08);
  color: #fff;
}

.toolbar-center {
  flex: 1;
  display: flex;
  justify-content: center;
  min-width: 0;
}

.mode-pill {
  border: 1px solid rgba(255, 255, 255, 0.12);
  background: rgba(0, 0, 0, 0.35);
  color: rgba(248, 250, 252, 0.92);
  font-size: 12px;
  font-weight: 600;
  padding: 8px 14px;
  border-radius: 999px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  max-width: min(280px, 46vw);
  min-width: 0;
  transition: background 0.15s, border-color 0.15s;
}
.mode-pill-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}
.mode-pill:hover {
  background: rgba(255, 255, 255, 0.06);
  border-color: rgba(255, 255, 255, 0.2);
}
.mode-pill-new {
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.04em;
  color: #0f172a;
  background: linear-gradient(135deg, #fde047, #facc15);
  padding: 2px 6px;
  border-radius: 6px;
}

.toolbar-right {
  display: flex;
  align-items: center;
  gap: 10px;
}
.multi-label {
  font-size: 12px;
  color: rgba(203, 213, 225, 0.85);
  font-weight: 500;
}
.send-pill {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  border: none;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(255, 255, 255, 0.95);
  color: #1e293b;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.3);
  transition: transform 0.15s;
}
.send-pill:hover {
  transform: scale(1.06);
}

/* Extras slide in */
.dashboard-extras {
  margin-top: 0;
  max-height: 0;
  opacity: 0;
  overflow: hidden;
  transform: translateY(16px);
  transition:
    max-height 0.65s cubic-bezier(0.4, 0, 0.2, 1),
    opacity 0.45s ease 0.08s,
    transform 0.55s cubic-bezier(0.4, 0, 0.2, 1),
    margin-top 0.45s ease;
  pointer-events: none;
}
.dashboard-extras--visible {
  margin-top: 28px;
  max-height: 560px;
  opacity: 1;
  transform: translateY(0);
  pointer-events: auto;
}

.quick-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 12px;
  margin-bottom: 28px;
}

.quick-chip {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 12px 22px;
  border-radius: 999px;
  font-size: 14px;
  font-weight: 600;
  border: 1px solid rgba(255, 255, 255, 0.14);
  background: rgba(255, 255, 255, 0.06);
  color: #f1f5f9;
  cursor: pointer;
  backdrop-filter: blur(10px);
  transition: background 0.2s, border-color 0.2s, transform 0.15s;
}
.quick-chip:hover {
  background: rgba(255, 255, 255, 0.1);
  border-color: rgba(255, 255, 255, 0.22);
  transform: translateY(-1px);
}
.quick-chip-ico {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 10px;
}
.quick-chip-ico--pink {
  background: linear-gradient(145deg, #f472b6, #db2777);
  color: #fff;
}

.feature-cards {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  width: 100%;
}

.feature-card {
  text-align: left;
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 16px;
  padding: 14px 14px 16px;
  background: rgba(15, 17, 24, 0.55);
  cursor: pointer;
  transition: border-color 0.2s, box-shadow 0.2s, transform 0.2s;
}
.feature-card:hover {
  border-color: rgba(255, 255, 255, 0.18);
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.35);
  transform: translateY(-2px);
}

.feature-card-visual {
  position: relative;
  height: 120px;
  margin-bottom: 12px;
  border-radius: 12px;
  overflow: visible;
}
.feature-card-visual--portrait .thumb {
  position: absolute;
  width: 52px;
  height: 78px;
  border-radius: 8px;
  border: 1px solid rgba(255, 255, 255, 0.15);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.4);
}
.feature-card-visual--portrait .t1 {
  left: 12px;
  bottom: 0;
  background: linear-gradient(160deg, #4c1d95, #7c3aed);
  transform: rotate(-8deg);
  z-index: 1;
}
.feature-card-visual--portrait .t2 {
  left: 50%;
  bottom: 4px;
  transform: translateX(-50%);
  background: linear-gradient(160deg, #1e3a5f, #38bdf8);
  z-index: 2;
}
.feature-card-visual--portrait .t3 {
  right: 12px;
  bottom: 0;
  background: linear-gradient(160deg, #831843, #fb7185);
  transform: rotate(8deg);
  z-index: 1;
}

.feature-card-visual--landscape .thumb {
  position: absolute;
  height: 44px;
  border-radius: 8px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.4);
}
.feature-card-visual--landscape .t1 {
  width: 72px;
  left: 8px;
  bottom: 12px;
  background: linear-gradient(135deg, #0f172a, #334155);
  transform: rotate(-6deg);
}
.feature-card-visual--landscape .t2 {
  width: 80px;
  left: 50%;
  bottom: 8px;
  transform: translateX(-50%);
  background: linear-gradient(135deg, #422006, #ea580c);
  z-index: 2;
}
.feature-card-visual--landscape .t3 {
  width: 72px;
  right: 8px;
  bottom: 12px;
  background: linear-gradient(135deg, #134e4a, #14b8a6);
  transform: rotate(6deg);
}

.feature-card-title {
  font-size: 15px;
  font-weight: 600;
  color: rgba(248, 250, 252, 0.95);
}

@media (max-width: 640px) {
  .feature-cards {
    grid-template-columns: 1fr;
  }
  .toolbar-center {
    order: 3;
    width: 100%;
    flex-basis: 100%;
    justify-content: flex-start;
  }
}
</style>
