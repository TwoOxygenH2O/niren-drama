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
        <span class="wf-icon" v-html="step.icon"></span>
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
import { ElMessage } from 'element-plus'
import { projectApi } from '@/api/project'

const route = useRoute()
const project = ref<any>(null)

const workflowSteps = [
  { icon: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 19l7-7 3 3-7 7-3-3z"/><path d="M18 13l-1.5-7.5L2 2l3.5 14.5L13 18l5-5z"/><path d="M2 2l7.586 7.586"/><circle cx="11" cy="11" r="2"/></svg>', label: '剧本', routeEnd: '/script' },
  { icon: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="2" width="20" height="20" rx="2.18" ry="2.18"/><line x1="7" y1="2" x2="7" y2="22"/><line x1="17" y1="2" x2="17" y2="22"/><line x1="2" y1="12" x2="22" y2="12"/></svg>', label: '分镜', routeEnd: '/storyboard' },
  { icon: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>', label: '角色', routeEnd: '/characters' },
  { icon: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/></svg>', label: '场景', routeEnd: '/scenes' },
  { icon: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>', label: '素材库', routeEnd: '/assets' },
  { icon: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="5 3 19 12 5 21 5 3"/></svg>', label: '合成导出', routeEnd: '/synthesis' },
]

const statusLabel = (s: string) => ({ draft: '草稿', generating: '生成中', completed: '已完成', failed: '失败' }[s] || s)
const genreLabel = (g: string) => ({ romance: '都市言情', fantasy: '玄幻奇幻', thriller: '悬疑惊悚', urban: '都市职场', historical: '古装历史', comedy: '喜剧搞笑' }[g] || g || '-')

onMounted(async () => {
  try {
    const res = await projectApi.get(route.params.id as string)
    project.value = res.data.data
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || error?.message || '项目加载失败')
  }
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
