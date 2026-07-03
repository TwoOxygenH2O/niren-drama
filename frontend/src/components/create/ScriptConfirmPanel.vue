<template>
  <div v-if="showCharacterConfirm" class="chat-action-row step-confirm-actions">
    <button type="button" class="btn-confirm-step" @click="$emit('confirm-characters')">
      确认角色，生成剧本
    </button>
  </div>

  <div v-if="showScriptConfirm" class="chat-action-row step-confirm-actions">
    <button type="button" class="btn-confirm-step" @click="$emit('confirm-script')">
      确认剧本
    </button>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  showCharacterConfirm: boolean
  showScriptConfirm: boolean
}>()

defineEmits<{
  (e: 'confirm-characters'): void
  (e: 'confirm-script'): void
}>()
</script>

<style scoped>
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
  border: none;
  background: linear-gradient(100deg, #f7fbff, var(--primary), var(--secondary));
  color: #03101d;
  font-size: 16px;
  font-weight: 700;
  cursor: pointer;
  transition: transform 0.15s, box-shadow 0.15s;
  box-shadow: var(--shadow-primary);
}

.btn-confirm-step:hover:not(:disabled) {
  transform: translateY(-1px);
}

.btn-confirm-step:disabled {
  cursor: not-allowed;
}
</style>
