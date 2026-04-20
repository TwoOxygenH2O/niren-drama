<template>
  <div class="page-container">
    <!-- Hero welcome -->
    <div class="hero-banner">
      <div class="hero-text">
        <div class="hero-greeting">你好，{{ userStore.userInfo?.nickname || '创作者' }} 👋</div>
        <div class="hero-title">泥人剧场 · AI 短剧制作平台</div>
        <div class="hero-sub">一句话创意 → 剧本 → 分镜 → 生图 → 配音 → 成片，全流程自动化</div>
      </div>
      <div class="hero-action">
        <button class="new-project-btn" @click="$router.push('/projects')">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none"><line x1="12" y1="5" x2="12" y2="19" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"/><line x1="5" y1="12" x2="19" y2="12" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"/></svg>
          新建项目
        </button>
      </div>
    </div>

    <!-- Stats row -->
    <div class="stats-row">
      <div class="stat-card" v-for="stat in stats" :key="stat.label">
        <div class="stat-icon" :style="{ background: stat.bg }">
          <span>{{ stat.emoji }}</span>
        </div>
        <div class="stat-body">
          <div class="stat-value">{{ stat.value }}</div>
          <div class="stat-label">{{ stat.label }}</div>
        </div>
      </div>
    </div>

    <!-- Workflow steps -->
    <div class="section">
      <div class="section-head">
        <span class="section-title">🎬 八步全流程制作</span>
        <span class="section-sub">从创意到成片，全程 AI 驱动</span>
      </div>
      <div class="steps-grid">
        <div v-for="step in steps" :key="step.no" class="step-card">
          <div class="step-number">{{ step.no }}</div>
          <div class="step-emoji">{{ step.icon }}</div>
          <div class="step-name">{{ step.name }}</div>
          <div class="step-desc">{{ step.desc }}</div>
        </div>
      </div>
    </div>

    <!-- Recent projects -->
    <div class="section">
      <div class="section-head">
        <span class="section-title">📁 最近项目</span>
        <router-link to="/projects" class="view-all-link">查看全部 →</router-link>
      </div>
      <div v-if="recentProjects.length" class="projects-grid">
        <div
          v-for="project in recentProjects"
          :key="project.id"
          class="project-card"
          @click="$router.push(`/projects/${project.id}`)"
        >
          <div class="project-cover">
            <svg width="40" height="40" viewBox="0 0 24 24" fill="none" opacity="0.5"><path d="M15 10l4.553-2.843A1 1 0 0121 8.117v7.766a1 1 0 01-1.447.894L15 14M3 8a2 2 0 012-2h8a2 2 0 012 2v8a2 2 0 01-2 2H5a2 2 0 01-2-2V8z" stroke="white" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </div>
          <div class="project-info">
            <div class="project-name">{{ project.name }}</div>
            <div class="project-meta">
              <span :class="`status-badge status-${project.status}`">{{ statusLabel(project.status) }}</span>
              <span class="project-date">{{ formatDate(project.createTime) }}</span>
            </div>
          </div>
        </div>
      </div>
      <div v-else class="empty-state">
        <div class="empty-icon">🎬</div>
        <div class="empty-text">暂无项目，快去创建第一个吧~</div>
        <button class="new-project-btn-sm" @click="$router.push('/projects')">创建项目</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useUserStore } from '@/stores/user'
import { projectApi } from '@/api/project'

const userStore = useUserStore()
const recentProjects = ref<any[]>([])

const steps = [
  { no: '01', icon: '🎯', name: '创建项目', desc: '设置题材、集数、时长' },
  { no: '02', icon: '✍️', name: '剧本生成', desc: '一句话创意 → AI剧本' },
  { no: '03', icon: '🎬', name: '分镜拆解', desc: 'AI自动解析剧本分镜' },
  { no: '04', icon: '👤', name: '角色素材', desc: 'AI生成角色肖像图像' },
  { no: '05', icon: '🌄', name: '场景背景', desc: 'AI生成场景背景图' },
  { no: '06', icon: '🖼️', name: '分镜画面', desc: 'AI生成每个镜头画面' },
  { no: '07', icon: '🎙️', name: '配音生成', desc: 'TTS 为角色生成配音' },
  { no: '08', icon: '🚀', name: '合成导出', desc: '自动合成竖屏成片' },
]

const stats = ref([
  { emoji: '🎬', label: '总项目', value: '—', bg: 'rgba(99,102,241,0.1)' },
  { emoji: '✅', label: '已完成', value: '—', bg: 'rgba(16,185,129,0.1)' },
  { emoji: '⚡', label: '生成中', value: '—', bg: 'rgba(245,158,11,0.1)' },
])

const statusLabel = (s: string) => ({ draft: '草稿', generating: '生成中', completed: '已完成', failed: '失败' }[s] || s)
const formatDate = (d: string) => d ? d.substring(0, 10) : ''

onMounted(async () => {
  try {
    const res = await projectApi.list({ page: 1, size: 6 })
    const records = res.data.data?.records || []
    recentProjects.value = records
    stats.value[0].value = String(res.data.data?.total || records.length)
    stats.value[1].value = String(records.filter((p: any) => p.status === 'completed').length)
    stats.value[2].value = String(records.filter((p: any) => p.status === 'generating').length)
  } catch {}
})
</script>

<style scoped>
.page-container { padding: 28px 32px; }

/* Hero */
.hero-banner {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  background: linear-gradient(135deg, #1a1040 0%, #2d1b69 50%, #1e3a5f 100%);
  border-radius: 20px;
  padding: 36px 44px;
  margin-bottom: 28px;
  position: relative;
  overflow: hidden;
}
.hero-banner::before {
  content: '';
  position: absolute;
  top: -30%;
  right: -10%;
  width: 400px;
  height: 400px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(99,102,241,0.25) 0%, transparent 70%);
  pointer-events: none;
}
.hero-greeting { font-size: 14px; color: rgba(129,140,248,0.9); margin-bottom: 8px; font-weight: 500; }
.hero-title { font-size: 28px; font-weight: 800; color: #fff; letter-spacing: -0.5px; margin-bottom: 10px; }
.hero-sub { font-size: 14px; color: rgba(148,163,184,0.8); line-height: 1.6; }
.new-project-btn {
  display: flex; align-items: center; gap: 8px;
  background: #fff; color: #4f46e5;
  border: none; border-radius: 10px;
  padding: 12px 22px; font-size: 14px; font-weight: 700;
  cursor: pointer; white-space: nowrap;
  box-shadow: 0 4px 16px rgba(0,0,0,0.2);
  transition: transform 0.15s, box-shadow 0.15s;
}
.new-project-btn:hover { transform: translateY(-2px); box-shadow: 0 8px 24px rgba(0,0,0,0.25); }

/* Stats */
.stats-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
  margin-bottom: 32px;
}
.stat-card {
  background: #fff;
  border-radius: 14px;
  padding: 20px 22px;
  display: flex;
  align-items: center;
  gap: 16px;
  border: 1px solid var(--border);
  box-shadow: var(--shadow-sm);
  transition: box-shadow 0.2s;
}
.stat-card:hover { box-shadow: var(--shadow-md); }
.stat-icon {
  width: 48px; height: 48px;
  border-radius: 12px;
  display: flex; align-items: center; justify-content: center;
  font-size: 22px;
  flex-shrink: 0;
}
.stat-value { font-size: 26px; font-weight: 800; color: var(--text-primary); line-height: 1; margin-bottom: 4px; }
.stat-label { font-size: 12px; color: var(--text-muted); font-weight: 500; }

/* Sections */
.section { margin-bottom: 32px; }
.section-head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 18px; }
.section-title { font-size: 17px; font-weight: 700; color: var(--text-primary); }
.section-sub { font-size: 13px; color: var(--text-muted); margin-left: 10px; }
.view-all-link { font-size: 13px; color: var(--primary); text-decoration: none; font-weight: 500; }
.view-all-link:hover { text-decoration: underline; }

/* Step cards */
.steps-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(148px, 1fr));
  gap: 14px;
}
.step-card {
  background: #fff;
  border-radius: 14px;
  padding: 20px 16px;
  text-align: center;
  border: 1px solid var(--border);
  box-shadow: var(--shadow-sm);
  transition: transform 0.18s, box-shadow 0.18s;
}
.step-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 8px 24px rgba(99,102,241,0.14);
  border-color: rgba(99,102,241,0.2);
}
.step-number { font-size: 10px; font-weight: 700; color: var(--primary); letter-spacing: 0.5px; margin-bottom: 8px; }
.step-emoji { font-size: 28px; margin-bottom: 8px; }
.step-name { font-size: 13.5px; font-weight: 700; color: var(--text-primary); margin-bottom: 4px; }
.step-desc { font-size: 11.5px; color: var(--text-muted); line-height: 1.4; }

/* Project grid */
.projects-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(228px, 1fr));
  gap: 16px;
}
.project-card {
  background: #fff;
  border-radius: 14px;
  overflow: hidden;
  cursor: pointer;
  border: 1px solid var(--border);
  box-shadow: var(--shadow-sm);
  transition: transform 0.18s, box-shadow 0.18s;
}
.project-card:hover {
  transform: translateY(-4px);
  box-shadow: var(--shadow-lg);
}
.project-cover {
  height: 112px;
  background: linear-gradient(135deg, #1a1040, #2d1b69);
  display: flex; align-items: center; justify-content: center;
}
.project-info { padding: 14px 16px; }
.project-name { font-size: 14px; font-weight: 700; color: var(--text-primary); margin-bottom: 8px; }
.project-meta { display: flex; justify-content: space-between; align-items: center; }
.project-date { font-size: 11px; color: var(--text-muted); }

/* Empty */
.empty-state {
  background: #fff;
  border-radius: 16px;
  padding: 48px 24px;
  text-align: center;
  border: 1px dashed var(--border-strong);
}
.empty-icon { font-size: 40px; margin-bottom: 12px; }
.empty-text { font-size: 14px; color: var(--text-muted); margin-bottom: 20px; }
.new-project-btn-sm {
  background: var(--primary); color: #fff;
  border: none; border-radius: 8px;
  padding: 10px 20px; font-size: 13px; font-weight: 600;
  cursor: pointer;
  transition: opacity 0.15s;
}
.new-project-btn-sm:hover { opacity: 0.88; }
</style>
