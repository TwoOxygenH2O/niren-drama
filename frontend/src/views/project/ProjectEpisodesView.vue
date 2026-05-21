<template>
  <div class="ep-page">
    <header class="ep-top">
      <button type="button" class="ep-back" @click="router.push('/projects')">
        <span class="ep-back-ico" aria-hidden="true">‹</span>
        返回
      </button>
    </header>

    <div v-if="project" class="ep-hero">
      <h1 class="ep-title">{{ project.name || '未命名项目' }}</h1>
    </div>

    <div v-loading="loading" class="ep-grid-wrap">
      <div class="ep-grid">
        <button
          v-for="ep in episodeSlots"
          :key="ep.no"
          type="button"
          class="ep-card"
          @click="openEpisode(ep.no)"
        >
          <div class="ep-card-visual" aria-hidden="true">
            <span class="ep-doc-bg" />
            <span class="ep-doc-ico">第{{ ep.no }}集</span>
          </div>
          <div class="ep-card-meta">
            <span class="ep-doc-pin" aria-hidden="true">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z" stroke-linejoin="round" />
                <path d="M14 2v6h6M16 13H8M16 17H8M10 9H8" stroke-linecap="round" />
              </svg>
            </span>
            <div class="ep-card-text">
              <span class="ep-card-name">{{ ep.label }}</span>
              <span class="ep-card-time">{{ ep.time }}</span>
            </div>
          </div>
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { projectApi } from '@/api/project'
import { scriptApi } from '@/api/script'

const route = useRoute()
const router = useRouter()
const projectId = computed(() => String(route.params.id || ''))

const project = ref<any>(null)
const scripts = ref<any[]>([])
const loading = ref(false)

const episodeSlots = computed(() => {
  const n = typeof project.value?.episodes === 'number' && project.value.episodes > 0 ? project.value.episodes : 1
  const list: { no: number; label: string; time: string }[] = []
  for (let i = 1; i <= n; i++) {
    const sc = scripts.value.find((s) => Number(s.episodeNo) === i)
    const title = (sc?.title as string | undefined)?.trim()
    const label = title
      ? `第${i}集：${title.replace(/^第\d+集[：:]\s*/i, '')}`
      : `第${i}集`
    const raw = sc?.updateTime ?? sc?.createTime
    let time = '—'
    if (raw) {
      const d = new Date(String(raw))
      if (!Number.isNaN(d.getTime())) {
        const y = d.getFullYear()
        const m = String(d.getMonth() + 1).padStart(2, '0')
        const day = String(d.getDate()).padStart(2, '0')
        const hh = String(d.getHours()).padStart(2, '0')
        const mm = String(d.getMinutes()).padStart(2, '0')
        time = `${y}/${m}/${day} ${hh}:${mm}`
      }
    }
    list.push({ no: i, label, time })
  }
  return list
})

function openEpisode(episodeNo: number) {
  router.push({
    path: `/projects/${projectId.value}/immersive`,
    query: { episode: String(episodeNo) },
  })
}

onMounted(async () => {
  loading.value = true
  try {
    const [pr, sr] = await Promise.all([
      projectApi.get(projectId.value),
      scriptApi.listByProject(projectId.value),
    ])
    project.value = pr.data?.data ?? pr.data
    const raw = sr as any
    scripts.value = raw.data?.data ?? raw.data ?? []
  } catch (e: any) {
    ElMessage.error(e?.message || '加载失败')
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.ep-page {
  min-height: 100%;
  background: var(--bg-page);
  color: var(--text-primary);
  display: flex;
  flex-direction: column;
  padding: 0 40px 48px 112px;
  box-sizing: border-box;
}

.ep-top {
  padding: 20px 0 8px;
}

.ep-back {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 6px 10px 6px 4px;
  border: none;
  background: none;
  color: var(--text-primary);
  font-size: 15px;
  cursor: pointer;
  border-radius: var(--radius-sm);
}
.ep-back:hover {
  background: var(--bg-muted);
  color: #fff;
}
.ep-back-ico {
  font-size: 22px;
  line-height: 1;
  opacity: 0.9;
}

.ep-hero {
  margin-bottom: 28px;
}
.ep-title {
  font-size: 22px;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--text-primary);
}

.ep-grid-wrap {
  flex: 1;
  min-height: 200px;
}

.ep-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 20px;
}

.ep-card {
  text-align: left;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: var(--bg-card);
  padding: 0 0 16px;
  cursor: pointer;
  transition: border-color 0.2s, box-shadow 0.2s, transform 0.2s;
  color: inherit;
  font: inherit;
}
.ep-card:hover {
  border-color: var(--primary);
  box-shadow: var(--shadow-lg);
  transform: translateY(-2px);
}

.ep-card-visual {
  position: relative;
  height: 168px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 12px 12px 0;
  border-radius: var(--radius-md);
  background: var(--bg-muted);
  overflow: hidden;
}

.ep-doc-bg {
  position: absolute;
  inset: 0;
  opacity: 0.35;
  background: radial-gradient(circle at 30% 30%, var(--primary-glow), transparent 55%);
}

.ep-doc-ico {
  position: relative;
  font-size: 15px;
  font-weight: 700;
  color: var(--text-primary);
  padding: 10px 18px;
  border-radius: var(--radius-md);
  background: rgba(0, 0, 0, 0.35);
  border: 1px solid var(--border);
}

.ep-card-meta {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 14px 16px 0 18px;
}

.ep-doc-pin {
  color: var(--text-secondary);
  margin-top: 2px;
  flex-shrink: 0;
}

.ep-card-text {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.ep-card-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.ep-card-time {
  font-size: 12px;
  color: var(--text-secondary);
}
</style>
