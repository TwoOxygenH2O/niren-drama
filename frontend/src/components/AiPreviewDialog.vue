<template>
  <el-dialog
    :model-value="modelValue"
    :width="width"
    top="4vh"
    destroy-on-close
    class="ai-preview-dialog"
    :close-on-click-modal="false"
    @update:model-value="handleVisibleChange"
  >
    <template #header>
      <div class="preview-hero">
        <div class="preview-hero__glow"></div>
        <div class="preview-hero__content">
          <div class="preview-hero__eyebrow">AI Workspace</div>
          <div class="preview-hero__main">
            <div>
              <div class="preview-hero__title">{{ title }}</div>
              <div v-if="subtitle" class="preview-hero__subtitle">{{ subtitle }}</div>
            </div>
            <div class="preview-hero__meta">
              <slot name="meta"></slot>
              <span class="preview-pill" :class="loading ? 'is-loading' : 'is-ready'">
                {{ phaseText || (loading ? '实时生成中' : '待确认入库') }}
              </span>
            </div>
          </div>
        </div>
      </div>
    </template>

    <div class="preview-shell">
      <div v-if="description" class="preview-shell__desc">{{ description }}</div>
      <slot></slot>
      <div v-if="errorMessage" class="preview-shell__error">{{ errorMessage }}</div>
    </div>

    <template #footer>
      <div class="preview-footer">
        <slot name="footer-prefix"></slot>
        <div class="preview-footer__actions">
          <el-button @click="handleCancel">{{ cancelText }}</el-button>
          <el-button
            type="primary"
            :loading="confirmLoading"
            :disabled="confirmDisabled"
            @click="$emit('confirm')"
          >
            {{ confirmText }}
          </el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
const props = withDefaults(defineProps<{
  modelValue: boolean
  title: string
  subtitle?: string
  description?: string
  phaseText?: string
  loading?: boolean
  confirmText?: string
  cancelText?: string
  confirmLoading?: boolean
  confirmDisabled?: boolean
  errorMessage?: string
  width?: string
}>(), {
  subtitle: '',
  description: '',
  phaseText: '',
  loading: false,
  confirmText: '确认保存',
  cancelText: '取消',
  confirmLoading: false,
  confirmDisabled: false,
  errorMessage: '',
  width: '84%',
})

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'confirm'): void
  (e: 'cancel'): void
}>()

function handleVisibleChange(value: boolean) {
  emit('update:modelValue', value)
}

function handleCancel() {
  emit('cancel')
  emit('update:modelValue', false)
}
</script>

<style scoped>
.ai-preview-dialog :deep(.el-dialog) {
  background:
    radial-gradient(circle at top right, rgba(99, 102, 241, 0.12), transparent 28%),
    linear-gradient(180deg, #fbfcff 0%, #ffffff 22%, #ffffff 100%);
}

.preview-hero {
  position: relative;
  overflow: hidden;
  border-radius: 18px;
  background: linear-gradient(135deg, #0f172a 0%, #1e293b 42%, #312e81 100%);
  color: #fff;
}

.preview-hero__glow {
  position: absolute;
  inset: auto -20% -45% auto;
  width: 240px;
  height: 240px;
  background: radial-gradient(circle, rgba(129, 140, 248, 0.55), transparent 68%);
  pointer-events: none;
}

.preview-hero__content {
  position: relative;
  z-index: 1;
  padding: 18px 20px 20px;
}

.preview-hero__eyebrow {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: rgba(255, 255, 255, 0.68);
  margin-bottom: 12px;
}

.preview-hero__main {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}

.preview-hero__title {
  font-size: 24px;
  font-weight: 700;
  letter-spacing: -0.03em;
}

.preview-hero__subtitle {
  margin-top: 8px;
  max-width: 720px;
  font-size: 13px;
  line-height: 1.6;
  color: rgba(255, 255, 255, 0.76);
}

.preview-hero__meta {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 10px;
}

.preview-pill {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 96px;
  padding: 8px 14px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  backdrop-filter: blur(10px);
}

.preview-pill.is-loading {
  background: rgba(255, 255, 255, 0.14);
  color: #fff;
  border: 1px solid rgba(255, 255, 255, 0.22);
}

.preview-pill.is-ready {
  background: rgba(16, 185, 129, 0.18);
  color: #d1fae5;
  border: 1px solid rgba(110, 231, 183, 0.28);
}

.preview-shell {
  margin-top: 18px;
}

.preview-shell__desc {
  margin-bottom: 16px;
  padding: 12px 14px;
  border-radius: 14px;
  background: linear-gradient(135deg, rgba(99, 102, 241, 0.08), rgba(59, 130, 246, 0.05));
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.7;
}

.preview-shell__error {
  margin-top: 14px;
  padding: 12px 14px;
  border-radius: 14px;
  background: rgba(239, 68, 68, 0.08);
  border: 1px solid rgba(239, 68, 68, 0.14);
  color: #b91c1c;
  font-size: 13px;
}

.preview-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.preview-footer__actions {
  display: flex;
  gap: 12px;
  margin-left: auto;
}

@media (max-width: 900px) {
  .preview-hero__main {
    flex-direction: column;
  }

  .preview-hero__meta {
    justify-content: flex-start;
  }

  .preview-footer {
    flex-direction: column;
    align-items: stretch;
  }

  .preview-footer__actions {
    width: 100%;
    justify-content: flex-end;
  }
}
</style>