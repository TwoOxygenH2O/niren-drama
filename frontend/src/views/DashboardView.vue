<template>
  <div class="page-container">
    <!-- Hero welcome -->
    <div class="hero-banner">
      <div class="hero-text">
        <div class="hero-greeting">你好，{{ userStore.userInfo?.nickname || '创作者' }}</div>
        <div class="hero-title">泥人剧场 · AI 短剧创作平台</div>
        <div class="hero-sub">从一句话创意到完整短剧成片，AI 全流程驱动创作</div>
      </div>
      <div class="hero-action">
        <button class="new-project-btn" @click="$router.push('/projects')">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
          新建项目
        </button>
      </div>
    </div>

    <!-- Stats row -->
    <div class="stats-row">
      <div class="stat-card" v-for="stat in stats" :key="stat.label">
        <div class="stat-icon" :style="{ background: stat.bg }">
          <span v-html="stat.icon"></span>
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
        <span class="section-title">创作流程</span>
        <span class="section-sub">从创意到成片，全程 AI 驱动</span>
      </div>
      <div class="steps-grid">
        <div
          v-for="step in steps"
          :key="step.no"
          class="step-card"
          :class="{ clickable: !!step.route }"
          @click="step.route && handleStepClick(step)"
        >
          <div class="step-number">{{ step.no }}</div>
          <div class="step-icon-wrap" v-html="step.icon"></div>
          <div class="step-name">{{ step.name }}</div>
          <div class="step-desc">{{ step.desc }}</div>
          <div class="step-arrow" v-if="step.no !== '08'">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"/></svg>
          </div>
        </div>
      </div>
    </div>

    <!-- Recent projects -->
    <div class="section">
      <div class="section-head">
        <span class="section-title">最近项目</span>
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
            <svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="rgba(255,255,255,0.6)" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M15 10l4.553-2.843A1 1 0 0121 8.117v7.766a1 1 0 01-1.447.894L15 14M3 8a2 2 0 012-2h8a2 2 0 012 2v8a2 2 0 01-2 2H5a2 2 0 01-2-2V8z"/></svg>
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
        <svg class="empty-svg" width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M15 10l4.553-2.843A1 1 0 0121 8.117v7.766a1 1 0 01-1.447.894L15 14M3 8a2 2 0 012-2h8a2 2 0 012 2v8a2 2 0 01-2 2H5a2 2 0 01-2-2V8z"/></svg>
        <div class="empty-text">暂无项目，开始你的第一部短剧创作</div>
        <button class="new-project-btn-sm" @click="$router.push('/projects')">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
          创建项目
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { projectApi } from '@/api/project'

const router = useRouter()
const userStore = useUserStore()
const recentProjects = ref<any[]>([])

const steps = [
  {
    no: '01',
    icon: '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="16"/><line x1="8" y1="12" x2="16" y2="12"/></svg>',
    name: '创建项目',
    desc: '设置题材、集数与时长',
    route: '/projects',
  },
  {
    no: '02',
    icon: '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 19l7-7 3 3-7 7-3-3z"/><path d="M18 13l-1.5-7.5L2 2l3.5 14.5L13 18l5-5z"/><path d="M2 2l7.586 7.586"/><circle cx="11" cy="11" r="2"/></svg>',
    name: '剧本生成',
    desc: '一句话创意 → AI 剧本',
    route: null,
  },
  {
    no: '03',
    icon: '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="2" width="20" height="20" rx="2.18" ry="2.18"/><line x1="7" y1="2" x2="7" y2="22"/><line x1="17" y1="2" x2="17" y2="22"/><line x1="2" y1="12" x2="22" y2="12"/><line x1="2" y1="7" x2="7" y2="7"/><line x1="2" y1="17" x2="7" y2="17"/><line x1="17" y1="7" x2="22" y2="7"/><line x1="17" y1="17" x2="22" y2="17"/></svg>',
    name: '分镜拆解',
    desc: 'AI 解析剧本为分镜',
    route: null,
  },
  {
    no: '04',
    icon: '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>',
    name: '角色设计',
    desc: 'AI 生成角色肖像',
    route: null,
  },
  {
    no: '05',
    icon: '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/></svg>',
    name: '场景绘制',
    desc: 'AI 生成场景背景',
    route: null,
  },
  {
    no: '06',
    icon: '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/><polyline points="3.27 6.96 12 12.01 20.73 6.96"/><line x1="12" y1="22.08" x2="12" y2="12"/></svg>',
    name: '分镜画面',
    desc: 'AI 生成每帧画面',
    route: null,
  },
  {
    no: '07',
    icon: '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"/><path d="M19 10v2a7 7 0 0 1-14 0v-2"/><line x1="12" y1="19" x2="12" y2="23"/><line x1="8" y1="23" x2="16" y2="23"/></svg>',
    name: '配音生成',
    desc: 'TTS 为角色配音',
    route: null,
  },
  {
    no: '08',
    icon: '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="5 3 19 12 5 21 5 3"/></svg>',
    name: '合成导出',
    desc: '自动合成竖屏短剧',
    route: null,
  },
]

const stats = ref([
  {
    icon: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="7" width="20" height="14" rx="2" ry="2"/><path d="M16 7V5a2 2 0 00-2-2h-4a2 2 0 00-2 2v2"/></svg>',
    label: '总项目',
    value: '—',
    bg: 'rgba(99,102,241,0.1)',
  },
  {
    icon: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/></svg>',
    label: '已完成',
    value: '—',
    bg: 'rgba(16,185,129,0.1)',
  },
  {
    icon: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>',
    label: '生成中',
    value: '—',
    bg: 'rgba(245,158,11,0.1)',
  },
])

const statusLabel = (s: string) => ({ draft: '草稿', generating: '生成中', completed: '已完成', failed: '失败' }[s] || s)
const formatDate = (d: string) => d ? d.substring(0, 10) : ''

function handleStepClick(step: any) {
  if (step.route) {
    router.push(step.route)
  }
}

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
  background: var(--bg-card, #fff);
  border-radius: 14px;
  padding: 20px 22px;
  display: flex;
  align-items: center;
  gap: 16px;
  border: 1px solid var(--border, #e5e7eb);
  transition: box-shadow 0.2s;
}
.stat-card:hover { box-shadow: 0 4px 12px rgba(0,0,0,0.06); }
.stat-icon {
  width: 48px; height: 48px;
  border-radius: 12px;
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
  color: var(--primary, #6366f1);
}
.stat-value { font-size: 26px; font-weight: 800; color: var(--text-primary, #1e293b); line-height: 1; margin-bottom: 4px; }
.stat-label { font-size: 12px; color: var(--text-muted, #94a3b8); font-weight: 500; }

/* Sections */
.section { margin-bottom: 32px; }
.section-head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 18px; }
.section-title { font-size: 17px; font-weight: 700; color: var(--text-primary, #1e293b); }
.section-sub { font-size: 13px; color: var(--text-muted, #94a3b8); margin-left: 10px; }
.view-all-link { font-size: 13px; color: var(--primary, #6366f1); text-decoration: none; font-weight: 500; }
.view-all-link:hover { text-decoration: underline; }

/* Step cards */
.steps-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
}
@media (max-width: 1100px) {
  .steps-grid { grid-template-columns: repeat(2, 1fr); }
}
.step-card {
  background: var(--bg-card, #fff);
  border-radius: 14px;
  padding: 22px 18px;
  text-align: center;
  border: 1px solid var(--border, #e5e7eb);
  transition: transform 0.18s, box-shadow 0.18s, border-color 0.18s;
  position: relative;
}
.step-card.clickable { cursor: pointer; }
.step-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 8px 24px rgba(99,102,241,0.12);
  border-color: rgba(99,102,241,0.25);
}
.step-card.clickable:hover {
  border-color: var(--primary, #6366f1);
}
.step-number { font-size: 10px; font-weight: 700; color: var(--primary, #6366f1); letter-spacing: 0.5px; margin-bottom: 10px; }
.step-icon-wrap {
  width: 48px; height: 48px;
  margin: 0 auto 12px;
  display: flex; align-items: center; justify-content: center;
  background: rgba(99,102,241,0.08);
  border-radius: 12px;
  color: var(--primary, #6366f1);
}
.step-name { font-size: 14px; font-weight: 700; color: var(--text-primary, #1e293b); margin-bottom: 4px; }
.step-desc { font-size: 12px; color: var(--text-muted, #94a3b8); line-height: 1.4; }
.step-arrow {
  position: absolute;
  right: -14px;
  top: 50%;
  transform: translateY(-50%);
  color: var(--border-strong, #cbd5e1);
  z-index: 1;
}

/* Project grid */
.projects-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 16px;
}
.project-card {
  background: var(--bg-card, #fff);
  border-radius: 14px;
  overflow: hidden;
  cursor: pointer;
  border: 1px solid var(--border, #e5e7eb);
  transition: transform 0.18s, box-shadow 0.18s;
}
.project-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 8px 20px rgba(0,0,0,0.08);
}
.project-cover {
  height: 100px;
  background: linear-gradient(135deg, #1a1040, #2d1b69);
  display: flex; align-items: center; justify-content: center;
}
.project-info { padding: 14px 16px; }
.project-name { font-size: 14px; font-weight: 700; color: var(--text-primary, #1e293b); margin-bottom: 8px; }
.project-meta { display: flex; justify-content: space-between; align-items: center; }
.project-date { font-size: 11px; color: var(--text-muted, #94a3b8); }

/* Empty */
.empty-state {
  background: var(--bg-card, #fff);
  border-radius: 16px;
  padding: 56px 24px;
  text-align: center;
  border: 1px dashed var(--border-strong, #cbd5e1);
}
.empty-svg { color: var(--text-muted, #94a3b8); opacity: 0.4; margin-bottom: 16px; }
.empty-text { font-size: 14px; color: var(--text-muted, #94a3b8); margin-bottom: 20px; }
.new-project-btn-sm {
  display: inline-flex; align-items: center; gap: 6px;
  background: var(--primary, #6366f1); color: #fff;
  border: none; border-radius: 8px;
  padding: 10px 20px; font-size: 13px; font-weight: 600;
  cursor: pointer;
  transition: opacity 0.15s;
}
.new-project-btn-sm:hover { opacity: 0.88; }
</style>
