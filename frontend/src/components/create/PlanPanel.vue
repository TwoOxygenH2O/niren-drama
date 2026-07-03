<template>
  <aside class="immersive-plan" :aria-label="`第${activeEpisode}集策划`">
    <div class="plan-scroll">
      <header class="plan-head">
        <span class="plan-ep-badge">第 {{ String(activeEpisode).padStart(2, '0') }} 集</span>
        <h2 class="plan-title">{{ activePlanScript?.title || '—' }}</h2>
        <p class="plan-ai-note">内容由智能生成</p>
        <button type="button" class="plan-close-btn" title="收起策划栏" aria-label="收起策划栏" @click="$emit('close')">×</button>
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
            <li v-for="c in planCharacters" :key="c.id" class="plan-subject-item" @click="$emit('open-character-gallery', c)">
              <div class="plan-subject-avatar">
                <img v-if="c.imageUrl" :src="c.imageUrl" :alt="c.name" loading="lazy" decoding="async" />
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

        <div v-if="hasProjectVideo" class="video-workbench-entry" @click="$emit('go-video-workbench')">
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
        @click="$emit('open-video-prompts')"
      >
        生成视频提示词
      </button>
      <button
        type="button"
        class="btn-plan-video"
        :disabled="primaryVideoDisabled"
        @click="$emit('primary-video')"
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
            <button type="button" class="shot-select-all-btn" @click="$emit('toggle-select-all')">
              {{ allSelected ? '取消全选' : '全选' }}
            </button>
            <span class="shot-select-count">{{ selectedShotIds.length }} / {{ episodeShots.length }}</span>
          </div>
          <el-checkbox-group v-model="selectedShotIdsValue" class="shot-select-list">
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
</template>

<script setup lang="ts">
import { computed } from 'vue'

type AnyRecord = Record<string, any>

const props = defineProps<{
  activeEpisode: number
  activePlanScript: AnyRecord | null
  allSelected: boolean
  episodeScriptBody: string
  episodeShots: AnyRecord[]
  episodeStoryboardErr: string
  episodeStoryboardGenerating: boolean
  episodeStoryboardReady: boolean
  hasProjectVideo: boolean
  mediaSubmitLoading: boolean
  mediaTaskMessage: string
  mediaTaskProgress: number
  planCharacters: AnyRecord[]
  portraitRefreshing: boolean
  primaryVideoDisabled: boolean
  primaryVideoLabel: string
  scriptWorkflowLoading: boolean
  scriptWorkflowPhaseText: string
  selectedShotIds: string[]
  shotSelectLabel: string
  showGenVideoFab: boolean
}>()

const emit = defineEmits<{
  (e: 'update:selectedShotIds', value: string[]): void
  (e: 'close'): void
  (e: 'go-video-workbench'): void
  (e: 'open-character-gallery', value: AnyRecord): void
  (e: 'open-video-prompts'): void
  (e: 'primary-video'): void
  (e: 'toggle-select-all'): void
}>()

const selectedShotIdsValue = computed({
  get: () => props.selectedShotIds,
  set: (value) => emit('update:selectedShotIds', value),
})
</script>

<style scoped>
.immersive-plan {
  flex: 0 0 clamp(280px, 34vw, 420px);
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: transparent;
  border-left: 1px solid rgba(150, 190, 255, 0.14);
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
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--surface-panel);
  box-shadow: var(--shadow-md);
  backdrop-filter: blur(var(--glass-blur)) saturate(145%);
}

.plan-actions--dock {
  flex-shrink: 0;
  padding: 12px 16px calc(14px + env(safe-area-inset-bottom, 0px));
  border-top: 1px solid var(--border);
  background: rgba(11, 20, 27, 0.68);
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
  color: var(--primary);
  background: var(--primary-glow);
  border: 1px solid var(--primary-light);
  padding: 4px 10px;
  border-radius: var(--radius-full);
}

.plan-title {
  margin: 10px 0 6px;
  font-size: 1.05rem;
  font-weight: 700;
  color: #f7fbff;
  line-height: 1.35;
}

.plan-ai-note,
.plan-muted {
  margin: 0;
  font-size: 11px;
  color: #9aa8bd;
}

.plan-muted {
  font-size: 12px;
  line-height: 1.55;
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

.spin {
  width: 14px;
  height: 14px;
  border: 2px solid var(--primary-glow);
  border-top-color: var(--primary-light);
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}

.plan-spin {
  width: 22px;
  height: 22px;
}

@keyframes spin {
  to { transform: rotate(360deg); }
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
  color: #f7fbff;
}

.plan-block-body {
  margin: 0;
  font-size: 13px;
  line-height: 1.65;
  color: #dbe8ff;
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
  border-color: rgba(150, 190, 255, 0.14);
  background: rgba(255, 255, 255, 0.045);
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
  color: #dbe8ff;
  white-space: pre-wrap;
  word-break: break-word;
  background: var(--bg-muted);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  max-height: min(42vh, 520px);
  overflow-y: auto;
}

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
  border: 1px solid rgba(150, 190, 255, 0.14);
  border-radius: inherit;
  background: rgba(255, 255, 255, 0.045);
  backdrop-filter: blur(24px) saturate(140%);
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
  box-shadow: 0 4px 16px rgba(99,102,241,0.35);
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

.plan-actions {
  display: flex;
  flex-direction: column;
  gap: 10px;
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

.btn-plan-prompts,
.btn-plan-video {
  width: 100%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  cursor: pointer;
  font-weight: 700;
}

.btn-plan-prompts {
  padding: 10px 14px;
  border-radius: var(--radius-md);
  border: 1px solid var(--primary-light);
  font-size: 13px;
  color: var(--primary-light);
  background: var(--primary-glow);
}

.btn-plan-video {
  padding: 12px 16px;
  border-radius: var(--radius-full);
  border: none;
  font-size: 14px;
  color: #fff;
  background: var(--primary);
  box-shadow: var(--shadow-primary);
}

.btn-plan-video-ico {
  font-size: 13px;
  opacity: 0.88;
}

.btn-plan-prompts:disabled,
.btn-plan-video:disabled {
  opacity: 0.4;
  cursor: not-allowed;
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
}

.btn-shot-select:hover {
  border-color: var(--primary);
}

.btn-shot-select.has-selection {
  border-color: var(--primary);
  color: var(--primary-light);
}

.btn-shot-select-arrow {
  font-size: 10px;
  opacity: 0.6;
}

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
  background: linear-gradient(90deg, var(--primary), var(--secondary));
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

@media (max-width: 900px) {
  .immersive-plan {
    flex: 0 0 40vh;
    border-left: none;
    border-top: 1px solid rgba(150, 190, 255, 0.14);
  }
}

@media (max-width: 620px) {
  .immersive-plan {
    flex-basis: 44vh;
  }

  .plan-scroll {
    padding: 12px;
  }
}
</style>
