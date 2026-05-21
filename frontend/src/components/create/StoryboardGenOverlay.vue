<template>
  <Teleport to="body">
    <Transition name="sb-gen-fade">
      <div v-if="modelValue" class="sb-gen-overlay" role="dialog" aria-modal="true" aria-labelledby="sb-gen-title">
        <div class="sb-gen-panel">
          <ul class="sb-gen-list">
            <li
              v-for="(step, i) in steps"
              :key="step.key"
              class="sb-gen-row"
              :class="rowClass(i)"
            >
              <span class="sb-gen-label">{{ step.label }}</span>
              <span class="sb-gen-status">
                <template v-if="phaseIndex > i">
                  <span class="sb-gen-done">执行完毕</span>
                  <span class="sb-gen-check" aria-hidden="true">✓</span>
                </template>
                <template v-else-if="phaseIndex === i">
                  <span class="sb-gen-run">执行中 {{ Math.min(99, Math.round(innerPct)) }}%</span>
                  <span class="sb-gen-spinner" aria-hidden="true" />
                </template>
                <template v-else>
                  <span class="sb-gen-wait">待开始执行</span>
                  <span class="sb-gen-shutter" aria-hidden="true" />
                </template>
              </span>
            </li>
          </ul>
          <p id="sb-gen-title" class="sb-gen-foot">
            请稍候，泥人正在为您生成分镜…
          </p>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue'

const props = defineProps<{
  modelValue: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [boolean]
  complete: []
}>()

const steps = [
  { key: 'analyze', label: '分析剧本内容' },
  { key: 'subject', label: '明确分镜主体' },
  { key: 'style', label: '匹配参考风格' },
  { key: 'bg', label: '构思场景背景' },
  { key: 'comp', label: '设计画面构图' },
  { key: 'shot', label: '选择适合景别' },
  { key: 'light', label: '调整色彩与灯光' },
  { key: 'final', label: '最终细节确认' },
]

/** 0 .. steps.length 当前执行到的步骤索引 */
const phaseIndex = ref(0)
/** 当前步骤内进度 0–100 */
const innerPct = ref(0)

let raf = 0
let start = 0
const TOTAL_MS = 5500

function tick(now: number) {
  if (!start) start = now
  const elapsed = now - start
  const p = Math.min(1, elapsed / TOTAL_MS)
  const stepProgress = Math.min(steps.length - 0.001, p * steps.length)
  phaseIndex.value = Math.floor(stepProgress)
  innerPct.value = (stepProgress % 1) * 100

  if (p >= 1) {
    cancelAnimationFrame(raf)
    emit('complete')
    emit('update:modelValue', false)
    reset()
    return
  }
  raf = requestAnimationFrame(tick)
}

function reset() {
  start = 0
  phaseIndex.value = 0
  innerPct.value = 0
}

function rowClass(i: number) {
  if (phaseIndex.value > i) return 'is-done'
  if (phaseIndex.value === i) return 'is-active'
  return 'is-pending'
}

watch(
  () => props.modelValue,
  (v) => {
    if (v) {
      reset()
      cancelAnimationFrame(raf)
      raf = requestAnimationFrame(tick)
    } else {
      cancelAnimationFrame(raf)
    }
  },
)

onBeforeUnmount(() => cancelAnimationFrame(raf))
</script>

<style scoped>
.sb-gen-fade-enter-active,
.sb-gen-fade-leave-active {
  transition: opacity 0.25s ease;
}
.sb-gen-fade-enter-from,
.sb-gen-fade-leave-to {
  opacity: 0;
}

.sb-gen-overlay {
  position: fixed;
  inset: 0;
  z-index: 9000;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 20px;
  width: 100vw;
  height: 100vh;
  background: rgba(0, 0, 0, 0.92);
  padding: 24px;
}

.sb-gen-panel {
  width: min(440px, 100%);
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 20px 18px 16px;
  box-shadow: var(--shadow-lg);
}

.sb-gen-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.sb-gen-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 10px;
  border-radius: var(--radius-md);
  font-size: 13px;
  color: var(--text-primary);
  border-bottom: 1px solid var(--border);
}
.sb-gen-row:last-of-type {
  border-bottom: none;
}
.sb-gen-row.is-active {
  background: var(--bg-muted);
}

.sb-gen-label {
  flex: 1;
  min-width: 0;
}

.sb-gen-status {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
  font-size: 12px;
}

.sb-gen-done {
  color: #5eead4;
  font-weight: 600;
}
.sb-gen-check {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: rgba(94, 234, 212, 0.15);
  color: #5eead4;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
}

.sb-gen-run {
  color: var(--accent);
  font-weight: 600;
}

.sb-gen-spinner {
  width: 16px;
  height: 16px;
  border-radius: 50%;
  border: 2px solid var(--border);
  border-top-color: var(--text-secondary);
  animation: sb-spin 0.75s linear infinite;
}

.sb-gen-wait {
  color: var(--text-secondary);
}

.sb-gen-shutter {
  width: 16px;
  height: 16px;
  border-radius: 4px;
  background: repeating-linear-gradient(
    90deg,
    var(--border) 0 2px,
    transparent 2px 4px
  );
  opacity: 0.6;
}

.sb-gen-foot {
  margin: 14px 8px 4px;
  text-align: center;
  font-size: 13px;
  color: var(--text-secondary);
}

@keyframes sb-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
