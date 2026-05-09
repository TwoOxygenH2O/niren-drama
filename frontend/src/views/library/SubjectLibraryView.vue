<template>
  <div class="subject-library-root">
    <header class="sl-head">
      <h1 class="sl-title">主体库</h1>
      <p class="sl-desc">选择项目，进入该项目的角色与主体管理</p>
    </header>

    <div v-loading="loading" class="sl-cards-wrap">
      <div v-if="!loading && projects.length === 0" class="sl-empty">
        暂无项目，请先在「我的空间」创建项目
      </div>
      <div v-else class="sl-grid">
        <article
          v-for="p in projects"
          :key="p.id"
          class="sl-card"
          role="button"
          tabindex="0"
          @click="goCharacters(p.id)"
          @keydown.enter.prevent="goCharacters(p.id)"
        >
          <div class="sl-card-visual-wrap">
            <div class="sl-card-cover" :style="coverLayerStyle(p)">
              <span v-if="!coverUrl(p)" class="sl-card-cover-ph" aria-hidden="true">{{ titleInitial(p.name) }}</span>
            </div>
          </div>
          <div class="sl-card-body">
            <h2 class="sl-card-title">{{ p.name }}</h2>
            <p class="sl-card-meta">{{ p.description || '暂无描述' }}</p>
          </div>
        </article>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { projectApi } from '@/api/project'

const router = useRouter()
const loading = ref(true)
const projects = ref<any[]>([])

function coverUrl(row: any): string {
  const u = row?.coverImage ?? row?.cover_image
  return typeof u === 'string' && u.trim() ? u.trim() : ''
}

function gradientForProject(id: number) {
  const hues = [262, 215, 190, 330, 175, 205, 45, 280]
  const h = hues[Math.abs(Number(id)) % hues.length]
  return `linear-gradient(to bottom, hsla(${h}, 42%, 42%, 0.35), hsla(${h}, 38%, 14%, 0.92))`
}

function coverLayerStyle(row: any) {
  const url = coverUrl(row)
  const base = {
    backgroundSize: 'cover',
    backgroundPosition: 'center',
  } as Record<string, string>
  if (url) {
    base.backgroundImage = `linear-gradient(to bottom, rgba(255,255,255,0.06), rgba(0,0,0,0.42)), url("${url.replace(/"/g, '\\"')}")`
    return base
  }
  base.backgroundImage = `${gradientForProject(Number(row.id))}, radial-gradient(ellipse 80% 60% at 50% 20%, rgba(255,255,255,0.12), transparent 55%)`
  return base
}

function titleInitial(name: unknown) {
  const s = String(name || '?').trim()
  return s ? s.charAt(0) : '?'
}

onMounted(async () => {
  loading.value = true
  try {
    const res = await projectApi.list({ page: 1, size: 100 })
    const data = res.data?.data
    projects.value = data?.records || []
  } finally {
    loading.value = false
  }
})

function goCharacters(id: number) {
  router.push(`/projects/${id}/characters`)
}
</script>

<style scoped>
.subject-library-root {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  gap: 20px;
  padding: 28px 40px 32px 112px;
  width: 100%;
  box-sizing: border-box;
  background: #0a0a0c;
  color: #e8eaef;
}

.sl-head {
  flex-shrink: 0;
}

.sl-title {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: #f8fafc;
}

.sl-desc {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 1.55;
  color: rgba(148, 163, 184, 0.95);
  font-weight: 400;
}

.sl-cards-wrap {
  flex: 1;
  min-height: 240px;
}

.sl-empty {
  padding: 56px 16px;
  text-align: center;
  font-size: 14px;
  color: rgba(148, 163, 184, 0.95);
  border: 1px dashed rgba(255, 255, 255, 0.1);
  border-radius: 16px;
  background: rgba(22, 24, 30, 0.45);
}

.sl-grid {
  display: grid;
  gap: 16px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

@media (min-width: 640px) {
  .sl-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (min-width: 1024px) {
  .sl-grid {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
}

@media (min-width: 1440px) {
  .sl-grid {
    grid-template-columns: repeat(5, minmax(0, 1fr));
  }
}

.sl-card {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 4px;
  min-height: 280px;
  border-radius: 16px;
  border: 1px solid rgba(255, 255, 255, 0.06);
  background: rgba(22, 24, 30, 0.98);
  box-shadow: 0 8px 28px rgba(0, 0, 0, 0.35);
  cursor: pointer;
  user-select: none;
  transition: border-color 0.2s, box-shadow 0.2s, transform 0.2s;
  text-align: left;
}

.sl-card:hover {
  border-color: rgba(129, 140, 248, 0.35);
  box-shadow: 0 18px 48px rgba(0, 0, 0, 0.45);
  transform: translateY(-2px);
}

.sl-card:focus-visible {
  outline: 2px solid rgba(129, 140, 248, 0.65);
  outline-offset: 2px;
}

.sl-card-visual-wrap {
  flex: 1;
  min-height: 0;
  border-radius: 12px;
  overflow: hidden;
  background: linear-gradient(to bottom, rgba(255, 255, 255, 0.06), rgba(255, 255, 255, 0));
}

.sl-card-cover {
  width: 100%;
  height: 100%;
  min-height: 180px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.sl-card-cover-ph {
  font-size: 48px;
  font-weight: 800;
  color: rgba(248, 250, 252, 0.22);
  text-transform: uppercase;
  pointer-events: none;
}

.sl-card-body {
  flex-shrink: 0;
  padding: 10px 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.sl-card-title {
  font-size: 14px;
  font-weight: 700;
  color: #f8fafc;
  margin: 0;
  letter-spacing: 0.02em;
  line-height: 1.35;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.sl-card-meta {
  margin: 0;
  font-size: 12px;
  line-height: 1.5;
  color: rgba(148, 163, 184, 0.92);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.subject-library-root :deep(.el-loading-mask) {
  background-color: rgba(10, 10, 12, 0.72);
}
.subject-library-root :deep(.el-loading-spinner .path) {
  stroke: rgba(129, 140, 248, 0.85);
}
.subject-library-root :deep(.el-loading-text) {
  color: rgba(226, 232, 240, 0.88);
}
</style>
