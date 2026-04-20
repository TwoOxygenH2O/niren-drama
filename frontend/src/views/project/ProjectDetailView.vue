<template>
  <div class="page-container">
    <div class="project-nav">
      <el-button text @click="$router.push('/projects')">
        <el-icon><ArrowLeft /></el-icon> 返回列表
      </el-button>
      <span class="project-name">{{ project?.name }}</span>
      <span v-if="project" :class="`status-badge status-${project.status}`">
        {{ statusLabel(project.status) }}
      </span>
    </div>

    <div v-if="project" class="workflow-nav">
      <div
        v-for="step in workflowSteps"
        :key="step.routeEnd"
        class="workflow-step"
        :class="{ active: route.path.endsWith(step.routeEnd) }"
        @click="$router.push(`/projects/${project.id}${step.routeEnd}`)"
      >
        <span class="wf-icon">{{ step.icon }}</span>
        <span class="wf-label">{{ step.label }}</span>
      </div>
    </div>

    <div class="project-info-cards" v-if="project">
      <div class="info-card">
        <div class="info-label">题材</div>
        <div class="info-value">{{ genreLabel(project.genre) }}</div>
      </div>
      <div class="info-card">
        <div class="info-label">集数</div>
        <div class="info-value">{{ project.episodes }} 集</div>
      </div>
      <div class="info-card">
        <div class="info-label">单集时长</div>
        <div class="info-value">{{ project.episodeDuration }} 秒</div>
      </div>
      <div class="info-card">
        <div class="info-label">创建时间</div>
        <div class="info-value">{{ project.createTime?.substring(0, 10) }}</div>
      </div>
    </div>

    <div v-if="project?.description" class="project-desc">
      <strong>项目描述：</strong>{{ project.description }}
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { projectApi } from '@/api/project'

const route = useRoute()
const project = ref<any>(null)

const workflowSteps = [
  { icon: '✍️', label: '剧本', routeEnd: '/script' },
  { icon: '🎬', label: '分镜', routeEnd: '/storyboard' },
  { icon: '👤', label: '角色', routeEnd: '/characters' },
  { icon: '🌄', label: '场景', routeEnd: '/scenes' },
  { icon: '📦', label: '素材库', routeEnd: '/assets' },
  { icon: '🚀', label: '合成导出', routeEnd: '/synthesis' },
]

const statusLabel = (s: string) => ({ draft: '草稿', generating: '生成中', completed: '已完成', failed: '失败' }[s] || s)
const genreLabel = (g: string) => ({ romance: '都市言情', fantasy: '玄幻奇幻', thriller: '悬疑惊悚', urban: '都市职场', historical: '古装历史', comedy: '喜剧搞笑' }[g] || g || '-')

onMounted(async () => {
  const res = await projectApi.get(route.params.id as string)
  project.value = res.data.data
})
</script>

<style scoped>
.page-container { padding: 24px; }

.project-nav {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 24px;
}
.project-name { font-size: 20px; font-weight: 600; color: #1a202c; }

.workflow-nav {
  display: flex;
  gap: 12px;
  margin-bottom: 24px;
  background: #fff;
  padding: 16px;
  border-radius: 12px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.06);
}

.workflow-step {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 12px 20px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.2s;
  min-width: 80px;
}
.workflow-step:hover { background: #f0f0ff; }
.workflow-step.active { background: #6366f1; color: #fff; }
.workflow-step.active .wf-icon { opacity: 1; }
.wf-icon { font-size: 22px; }
.wf-label { font-size: 13px; font-weight: 500; }

.project-info-cards {
  display: flex;
  gap: 16px;
  margin-bottom: 20px;
  flex-wrap: wrap;
}
.info-card {
  background: #fff;
  border-radius: 10px;
  padding: 16px 24px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.06);
  min-width: 120px;
}
.info-label { font-size: 12px; color: #718096; margin-bottom: 4px; }
.info-value { font-size: 18px; font-weight: 600; color: #1a202c; }

.project-desc {
  background: #fff;
  border-radius: 10px;
  padding: 16px 20px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.06);
  color: #4a5568;
  font-size: 14px;
  line-height: 1.6;
}
</style>
