<template>
  <div class="project-flow">
    <header class="flow-header">
      <el-button text @click="$router.push('/projects')">
        <el-icon><ArrowLeft /></el-icon> 返回项目中心
      </el-button>
      <span v-if="project" :class="`status-badge status-${project.status}`">{{ statusLabel(project.status) }}</span>
    </header>

    <template v-if="project">
      <section class="production-hero">
        <div class="production-copy">
          <p>项目生产流</p>
          <h1>{{ project.name }}</h1>
          <span>{{ project.description || '项目描述待补充' }}</span>
          <div class="production-actions">
            <el-button type="primary" @click="openEpisodeWorkbench">进入剧集生产</el-button>
            <el-button @click="openWorkflowStep(workflowSteps[0])">编辑剧本</el-button>
          </div>
        </div>
        <div class="production-metrics">
          <div v-for="item in projectInfoCards" :key="item.label">
            <span>{{ item.label }}</span>
            <strong>{{ item.value }}</strong>
          </div>
        </div>
      </section>

      <section class="production-lane">
        <article
          v-for="(step, index) in workflowSteps"
          :key="step.routeEnd"
          class="workflow-step"
          :class="{ active: isStepActive(step) }"
          @click="openWorkflowStep(step)"
        >
          <b>{{ String(index + 1).padStart(2, '0') }}</b>
          <span class="wf-icon" v-html="step.icon"></span>
          <strong>{{ step.label }}</strong>
          <i />
        </article>
      </section>

      <section class="project-notes">
        <article>
          <span>题材与规格</span>
          <dl>
            <div v-for="item in projectInfoCards" :key="item.label">
              <dt>{{ item.label }}</dt>
              <dd>{{ item.value }}</dd>
            </div>
          </dl>
        </article>
        <article>
          <span>项目通用信息</span>
          <pre>{{ project.commonInfo || '暂无通用信息' }}</pre>
        </article>
      </section>
    </template>

    <section v-else class="project-loading">项目加载中</section>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import { projectApi } from '@/api/project'
import { formatGenreLabel, formatProjectTypeLabel } from '@/constants/project'

const route = useRoute()
const router = useRouter()
const project = ref<any>(null)

const workflowSteps = [
  { icon: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 19l7-7 3 3-7 7-3-3z"/><path d="M18 13l-1.5-7.5L2 2l3.5 14.5L13 18l5-5z"/><path d="M2 2l7.586 7.586"/><circle cx="11" cy="11" r="2"/></svg>', label: '剧本', routeEnd: '/script' },
  { icon: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="2" width="20" height="20" rx="2.18" ry="2.18"/><line x1="7" y1="2" x2="7" y2="22"/><line x1="17" y1="2" x2="17" y2="22"/><line x1="2" y1="12" x2="22" y2="12"/></svg>', label: '分镜', routeEnd: '/storyboard' },
  { icon: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>', label: '角色', routeEnd: '/characters' },
  { icon: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/></svg>', label: '场景', routeEnd: '/scenes' },
  { icon: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>', label: '素材库', routeEnd: '/assets' },
  { icon: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="5 3 19 12 5 21 5 3"/></svg>', label: '成片预览', routeEnd: '/immersive/workbench', query: { tab: 'video' } },
]

const statusLabel = (s: string) => ({ draft: '草稿', generating: '生成中', completed: '已完成', failed: '失败' }[s] || s)
const projectInfoCards = computed(() => {
  if (!project.value) return []
  return [
    { label: '类型', value: formatProjectTypeLabel(project.value.projectType) },
    { label: '题材', value: formatGenreLabel(project.value.genre) || '未设置' },
    { label: '集数', value: `${project.value.episodes} 集` },
    { label: '单集时长', value: `${project.value.episodeDuration} 秒` },
    { label: '创建时间', value: project.value.createTime?.substring(0, 10) || '-' },
  ]
})

function openWorkflowStep(step: any) {
  if (!project.value?.id) return
  router.push({
    path: `/projects/${project.value.id}${step.routeEnd}`,
    query: step.query || {},
  })
}

function isStepActive(step: any) {
  if (step.query?.tab === 'video') {
    return route.path.endsWith(step.routeEnd) && route.query.tab === 'video'
  }
  return route.path.endsWith(step.routeEnd)
}

function openEpisodeWorkbench() {
  if (!project.value?.id) return
  router.push(`/projects/${project.value.id}/episodes`)
}

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
.project-flow {
  min-height: 100%;
  padding: 30px;
  background: var(--page-environment);
}

.flow-header,
.production-hero,
.production-lane,
.project-notes,
.project-loading {
  max-width: 1220px;
  margin: 0 auto;
}

.flow-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 18px;
}

.production-hero,
.production-lane,
.project-notes article,
.project-loading {
  border: 1px solid var(--border);
  border-radius: 20px;
  background: var(--surface-panel);
  backdrop-filter: blur(var(--glass-blur)) saturate(145%);
  box-shadow: var(--shadow-md), inset 0 1px 0 rgba(255, 255, 255, 0.055);
}

.production-hero {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 360px;
  gap: 28px;
  min-height: 320px;
  padding: 30px;
  margin-bottom: 18px;
}

.production-copy {
  display: flex;
  flex-direction: column;
  justify-content: end;
}

.production-copy p {
  margin: 0 0 10px;
  color: var(--primary);
  font-size: 14px;
  font-weight: 850;
}

.production-copy h1 {
  margin: 0;
  font-size: clamp(34px, 5vw, 62px);
  line-height: 1.02;
  letter-spacing: 0;
}

.production-copy > span {
  max-width: 720px;
  margin-top: 16px;
  color: var(--text-secondary);
  font-size: 17px;
  line-height: 1.7;
}

.production-actions {
  display: flex;
  gap: 12px;
  margin-top: 24px;
  flex-wrap: wrap;
}

.production-metrics {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  align-self: end;
}

.production-metrics div {
  min-height: 96px;
  padding: 16px;
  border: 1px solid var(--border);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.045);
}

.production-metrics span,
.project-notes span,
.project-notes dt {
  color: var(--text-muted);
  font-size: 14px;
}

.production-metrics strong {
  display: block;
  margin-top: 10px;
  color: var(--text-primary);
  font-size: 22px;
}

.production-lane {
  display: grid;
  grid-template-columns: repeat(6, minmax(120px, 1fr));
  gap: 10px;
  padding: 14px;
  margin-bottom: 18px;
  overflow-x: auto;
}

.workflow-step {
  position: relative;
  min-height: 150px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  gap: 10px;
  padding: 14px;
  border: 1px solid transparent;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.038);
  cursor: pointer;
  transition: transform 0.18s, border-color 0.18s, background 0.18s;
}

.workflow-step:hover {
  transform: translateY(-1px);
  border-color: var(--border-strong);
}

.workflow-step.active {
  border-color: var(--border-strong);
  background: var(--surface-panel-strong);
}

.workflow-step b {
  color: var(--primary);
  font-size: 14px;
}

.workflow-step strong {
  color: var(--text-primary);
  font-size: 16px;
}

.wf-icon {
  width: 38px;
  height: 38px;
  display: grid;
  place-items: center;
  border-radius: 13px;
  background:
    radial-gradient(circle at 22% 20%, rgba(103, 232, 249, 0.12), transparent 54%),
    rgba(255, 255, 255, 0.06);
  color: var(--primary);
}

.workflow-step i {
  display: block;
  width: 100%;
  height: 5px;
  border-radius: 999px;
  background: linear-gradient(90deg, var(--primary), var(--secondary), rgba(255, 255, 255, 0.12));
}

.project-notes {
  display: grid;
  grid-template-columns: 380px minmax(0, 1fr);
  gap: 18px;
}

.project-notes article {
  padding: 22px;
}

.project-notes dl {
  display: grid;
  gap: 14px;
  margin: 18px 0 0;
}

.project-notes dl div {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--border);
}

.project-notes dd {
  margin: 0;
  color: var(--text-primary);
  font-weight: 800;
  text-align: right;
}

.project-notes pre {
  margin: 18px 0 0;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--text-secondary);
  font-family: inherit;
  line-height: 1.6;
}

.project-loading {
  min-height: 360px;
  display: grid;
  place-items: center;
  color: var(--text-secondary);
}

@media (max-width: 1020px) {
  .production-hero,
  .project-notes {
    grid-template-columns: 1fr;
  }

  .production-lane {
    grid-template-columns: repeat(6, 150px);
  }
}

@media (max-width: 680px) {
  .project-flow {
    padding: 20px;
  }

  .production-metrics {
    grid-template-columns: 1fr;
  }
}
</style>
