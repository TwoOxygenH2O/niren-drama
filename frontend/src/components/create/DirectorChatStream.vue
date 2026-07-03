<template>
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
            @click="$emit('confirm-outline')"
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

      <ScriptConfirmPanel
        :show-character-confirm="charactersReady && !charactersConfirmed"
        :show-script-confirm="scriptReady && !scriptConfirmed"
        @confirm-characters="$emit('confirm-characters')"
        @confirm-script="$emit('confirm-script')"
      />

      <div v-if="streamError" class="stream-err">{{ streamError }}</div>
    </div>

    <div class="composer-bottom">
      <div class="composer-inner">
        <button type="button" class="attach-btn" title="附件" aria-label="附件" @click="$emit('attach')">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
            <path d="M21.44 11.05l-9.19 9.19a6 6 0 01-8.49-8.49l9.19-9.19a4 4 0 015.66 5.66l-9.2 9.19a2 2 0 01-2.83-2.83l8.49-8.48" />
          </svg>
        </button>
        <textarea
          v-model="inputValue"
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
          @click="$emit('send-follow-up')"
        >
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
            <line x1="12" y1="19" x2="12" y2="5" />
            <polyline points="5 12 12 5 19 12" />
          </svg>
        </button>
      </div>
    </div>
  </main>
</template>

<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import ScriptConfirmPanel from './ScriptConfirmPanel.vue'

type WorkflowPhase = 'outline' | 'script_gen' | 'plan_ready'
type ChatMessage = { role: 'user' | 'ai'; text: string }

const props = defineProps<{
  activeEpisode: number
  bottomInput: string
  bottomSending: boolean
  charactersConfirmed: boolean
  charactersReady: boolean
  chatTail: ChatMessage[]
  composerPlaceholder: string
  generating: boolean
  outlineContent: string
  outlineHtml: string
  outlinePlainDisplay: string
  outlineSaving: boolean
  scriptConfirmed: boolean
  scriptReady: boolean
  scriptWorkflowLoading: boolean
  showOutlineConfirmActions: boolean
  streamError: string
  userPrompt: string
  workflowPhase: WorkflowPhase
}>()

const emit = defineEmits<{
  (e: 'update:bottomInput', value: string): void
  (e: 'attach'): void
  (e: 'confirm-characters'): void
  (e: 'confirm-outline'): void
  (e: 'confirm-script'): void
  (e: 'send-follow-up'): void
}>()

const scrollRef = ref<HTMLElement | null>(null)

const inputValue = computed({
  get: () => props.bottomInput,
  set: (value) => emit('update:bottomInput', value),
})

function onBottomKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
    e.preventDefault()
    emit('send-follow-up')
  }
}

function scrollToBottom() {
  nextTick(() => {
    const el = scrollRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

defineExpose({ scrollToBottom })
</script>

<style scoped>
.immersive-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  background: transparent;
}

.chat-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 20px 24px 16px;
  max-width: 900px;
  width: 100%;
  margin: 0 auto;
  background:
    linear-gradient(rgba(255, 255, 255, 0.018) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.018) 1px, transparent 1px);
  background-size: 72px 72px;
}

.msg-user-wrap,
.chat-extra.user {
  display: flex;
  justify-content: flex-end;
}

.msg-user-wrap {
  margin-bottom: 20px;
}

.bubble-user {
  max-width: 85%;
  padding: 12px 16px;
  border-radius: 8px;
  background: linear-gradient(100deg, rgba(103, 232, 249, 0.16), rgba(139, 124, 255, 0.14));
  border: 1px solid rgba(103, 232, 249, 0.22);
  font-size: 14px;
  line-height: 1.55;
  color: #f7fbff;
  white-space: pre-wrap;
  word-break: break-word;
}

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
  color: #f7fbff;
  letter-spacing: -0.02em;
}

.continuation-seko,
.outline-card,
.ai-line,
.composer-inner {
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--surface-panel);
  box-shadow: var(--shadow-md);
  backdrop-filter: blur(var(--glass-blur)) saturate(145%);
}

.continuation-seko {
  padding: 14px;
}

.continuation-lead {
  margin: 10px 0 6px;
  font-size: 14px;
  font-weight: 600;
  color: #9aa8bd;
}

.continuation-desc {
  margin: 0;
  font-size: 13px;
  line-height: 1.65;
  color: #9aa8bd;
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
  color: var(--primary);
}

.ai-brand.small {
  font-size: 12px;
}

.ai-brand-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--primary);
  box-shadow: 0 0 14px rgba(103, 232, 249, 0.7);
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
  to { transform: rotate(360deg); }
}

.outline-card {
  padding: 18px 18px 14px;
  margin-bottom: 20px;
}

.outline-card-actions {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid var(--border);
}

.outline-plain,
.outline-md {
  margin: 0;
  color: #dbe8ff;
  max-height: min(52vh, 520px);
  overflow-y: auto;
}

.outline-plain {
  font-family: var(--font-sans);
  font-size: 13px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
}

.outline-md {
  font-size: 14px;
  line-height: 1.7;
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

.outline-md :deep(h1) { font-size: 1.35rem; }
.outline-md :deep(h2) { font-size: 1.2rem; }
.outline-md :deep(h3) { font-size: 1.08rem; }
.outline-md :deep(h4) { font-size: 1rem; color: var(--text-secondary); }
.outline-md :deep(p) { margin: 0.5em 0; }
.outline-md :deep(strong),
.outline-md :deep(b) { font-weight: 700; color: var(--text-primary); }
.outline-md :deep(ul),
.outline-md :deep(ol) { margin: 0.5em 0 0.5em 1.2em; padding: 0; }
.outline-md :deep(li) { margin: 0.25em 0; }
.outline-md :deep(blockquote) {
  margin: 0.6em 0;
  padding: 0.4em 0.75em;
  border-left: 3px solid var(--primary-light);
  background: var(--bg-muted);
  color: var(--text-secondary);
}
.outline-md :deep(hr) { border: none; border-top: 1px solid var(--border); margin: 1rem 0; }
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
.outline-md :deep(pre code) { padding: 0; border: none; background: none; color: var(--text-secondary); }
.outline-md :deep(a) { color: var(--accent-light); text-decoration: none; }
.outline-md :deep(a:hover) { text-decoration: underline; }
.outline-md :deep(table) { width: 100%; border-collapse: collapse; font-size: 13px; margin: 0.75em 0; }
.outline-md :deep(th),
.outline-md :deep(td) { border: 1px solid var(--border); padding: 6px 10px; text-align: left; }
.outline-md :deep(th) { background: var(--bg-muted); }

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
  background: linear-gradient(90deg, transparent 0%, rgba(129, 140, 248, 0.38) 42%, rgba(129, 140, 248, 0.38) 58%, transparent 100%);
  opacity: 0.85;
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
}

.chat-extra {
  margin-bottom: 16px;
}

.ai-line {
  padding: 12px;
}

.ai-text {
  margin: 8px 0 0;
  font-size: 14px;
  line-height: 1.65;
  color: #dbe8ff;
}

.stream-err {
  color: var(--color-danger);
  font-size: 13px;
  padding: 12px;
  border-radius: var(--radius-sm);
  background: rgba(127, 29, 29, 0.25);
  border: 1px solid rgba(248, 113, 113, 0.25);
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
  border-radius: 8px;
  border: 0;
  background: linear-gradient(100deg, #f7fbff, var(--primary), var(--secondary));
  color: #03101d;
  box-shadow: var(--shadow-primary);
  font-size: 16px;
  font-weight: 700;
  cursor: pointer;
}

.composer-bottom {
  flex-shrink: 0;
  padding: 12px 16px 20px;
  border-top: 1px solid rgba(150, 190, 255, 0.14);
  background: rgba(11, 20, 27, 0.68);
  backdrop-filter: blur(28px) saturate(145%);
}

.composer-inner {
  max-width: 900px;
  margin: 0 auto;
  display: flex;
  align-items: flex-end;
  gap: 10px;
  padding: 10px 14px;
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
  color: #f7fbff;
  font-family: inherit;
  padding: 8px 0;
}

.bottom-textarea::placeholder {
  color: #8897ae;
}

.send-round {
  flex-shrink: 0;
  width: 44px;
  height: 44px;
  border-radius: 50%;
  border: 0;
  cursor: pointer;
  background: linear-gradient(100deg, #f7fbff, var(--primary), var(--secondary));
  color: #03101d;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: transform 0.12s, opacity 0.12s;
}

.send-round:hover:not(:disabled) {
  transform: scale(1.05);
}

.send-round:disabled {
  cursor: not-allowed;
}

@media (max-width: 900px) {
  .chat-scroll {
    padding: 16px 14px 12px;
  }

  .composer-bottom {
    padding: 10px 10px calc(12px + env(safe-area-inset-bottom, 0px));
  }
}

@media (max-width: 620px) {
  .chat-scroll {
    padding: 14px 10px 10px;
  }

  .bubble-user {
    max-width: 92%;
  }

  .composer-inner {
    padding: 8px 10px;
  }
}
</style>
