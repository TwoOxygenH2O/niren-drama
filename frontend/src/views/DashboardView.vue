<template>
  <div class="page-container">
    <div class="welcome-section">
      <div class="welcome-text">
        <h1>你好，{{ userStore.userInfo?.nickname || '创作者' }} 👋</h1>
        <p>全栈 AI 短剧自动化生产平台 · 一句话创意→成片全流程</p>
      </div>
      <el-button type="primary" size="large" @click="$router.push('/projects')">
        <el-icon><Plus /></el-icon> 新建项目
      </el-button>
    </div>

    <!-- Workflow steps -->
    <div class="workflow-section">
      <h2 class="section-title">🎬 八步全流程制作</h2>
      <div class="steps-grid">
        <div v-for="step in steps" :key="step.no" class="step-card">
          <div class="step-no">{{ step.no }}</div>
          <div class="step-icon">{{ step.icon }}</div>
          <div class="step-name">{{ step.name }}</div>
          <div class="step-desc">{{ step.desc }}</div>
        </div>
      </div>
    </div>

    <!-- Recent projects -->
    <div class="recent-section">
      <div class="section-header">
        <h2 class="section-title">📁 最近项目</h2>
        <router-link to="/projects" class="view-all">查看全部</router-link>
      </div>
      <div v-if="recentProjects.length" class="card-grid">
        <div
          v-for="project in recentProjects"
          :key="project.id"
          class="project-card"
          @click="$router.push(`/projects/${project.id}`)"
        >
          <div class="project-cover">
            <el-icon size="40" color="#6366f1"><Film /></el-icon>
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
      <el-empty v-else description="暂无项目，快去创建第一个吧~" />
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
  { no: '04', icon: '👤', name: '角色素材', desc: 'AI生成角色肖像' },
  { no: '05', icon: '🖼️', name: '分镜画面', desc: 'AI生成每个镜头画面' },
  { no: '06', icon: '🎙️', name: '配音生成', desc: 'AI为角色生成配音' },
  { no: '07', icon: '🎞️', name: '视频合成', desc: '自动合成成片' },
  { no: '08', icon: '🚀', name: '导出发布', desc: '竖屏成片下载/发布' },
]

const statusLabel = (s: string) => ({ draft: '草稿', generating: '生成中', completed: '已完成', failed: '失败' }[s] || s)
const formatDate = (d: string) => d ? d.substring(0, 10) : ''

onMounted(async () => {
  try {
    const res = await projectApi.list({ page: 1, size: 6 })
    recentProjects.value = res.data.data?.records || []
  } catch {}
})
</script>

<style scoped>
.page-container { padding: 24px; }

.welcome-section {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: linear-gradient(135deg, #6366f1, #8b5cf6);
  border-radius: 16px;
  padding: 32px 40px;
  margin-bottom: 32px;
  color: #fff;
}

.welcome-text h1 { font-size: 26px; font-weight: 700; margin-bottom: 8px; }
.welcome-text p { font-size: 14px; opacity: 0.85; }

.section-title { font-size: 18px; font-weight: 600; color: #1a202c; margin-bottom: 20px; }

.steps-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 16px;
  margin-bottom: 40px;
}

.step-card {
  background: #fff;
  border-radius: 12px;
  padding: 20px 16px;
  text-align: center;
  box-shadow: 0 2px 8px rgba(0,0,0,0.06);
  transition: transform 0.2s, box-shadow 0.2s;
}
.step-card:hover { transform: translateY(-4px); box-shadow: 0 8px 24px rgba(99,102,241,0.15); }
.step-no { font-size: 11px; font-weight: 700; color: #6366f1; margin-bottom: 8px; }
.step-icon { font-size: 28px; margin-bottom: 8px; }
.step-name { font-size: 14px; font-weight: 600; color: #1a202c; margin-bottom: 4px; }
.step-desc { font-size: 12px; color: #718096; }

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.view-all { color: #6366f1; text-decoration: none; font-size: 14px; }

.card-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 16px; }

.project-card {
  background: #fff;
  border-radius: 12px;
  overflow: hidden;
  cursor: pointer;
  box-shadow: 0 2px 8px rgba(0,0,0,0.06);
  transition: transform 0.2s, box-shadow 0.2s;
}
.project-card:hover { transform: translateY(-4px); box-shadow: 0 8px 24px rgba(0,0,0,0.12); }

.project-cover {
  height: 120px;
  background: linear-gradient(135deg, #e0e7ff, #f3e8ff);
  display: flex;
  align-items: center;
  justify-content: center;
}

.project-info { padding: 16px; }
.project-name { font-size: 15px; font-weight: 600; color: #1a202c; margin-bottom: 8px; }
.project-meta { display: flex; justify-content: space-between; align-items: center; }
.project-date { font-size: 12px; color: #718096; }
</style>
