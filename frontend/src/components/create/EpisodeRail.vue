<template>
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
          @click="$emit('update:activeEpisode', ep)"
        >
          {{ String(ep).padStart(2, '0') }}
        </button>
      </div>
    </div>
    <button type="button" class="episode-add" title="新增剧集" aria-label="新增剧集" @click="$emit('add')">
      +
    </button>
  </aside>
</template>

<script setup lang="ts">
defineProps<{
  episodeDisplay: number[]
  activeEpisode: number
}>()

defineEmits<{
  (e: 'update:activeEpisode', value: number): void
  (e: 'add'): void
}>()
</script>

<style scoped>
.episode-rail {
  width: 56px;
  flex-shrink: 0;
  align-self: stretch;
  min-height: 0;
  border-right: 1px solid rgba(150, 190, 255, 0.14);
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 12px 6px;
  gap: 12px;
  background: rgba(11, 20, 27, 0.58);
}

.episode-rail-label {
  flex-shrink: 0;
  writing-mode: vertical-rl;
  font-size: 11px;
  letter-spacing: 0.2em;
  color: #9aa8bd;
}

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

.episode-dot,
.episode-add {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  cursor: pointer;
}

.episode-dot {
  border: 1px solid rgba(150, 190, 255, 0.18);
  background: rgba(255, 255, 255, 0.04);
  color: #dbe8ff;
  font-size: 12px;
  font-weight: 700;
  transition: border-color 0.15s, background 0.15s, box-shadow 0.15s;
}

.episode-dot.active {
  border-color: rgba(139, 92, 246, 0.6);
  background: var(--primary-glow);
  color: #fff;
  box-shadow: 0 0 22px rgba(139, 92, 246, 0.46);
}

.episode-add {
  flex-shrink: 0;
  border: 1px dashed rgba(150, 190, 255, 0.16);
  background: rgba(255, 255, 255, 0.05);
  color: #dbe8ff;
  font-size: 18px;
  line-height: 1;
}

.episode-add:hover {
  border-color: var(--primary-light);
  background: var(--bg-muted);
}
</style>
