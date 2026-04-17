<template>
  <div class="page-container">
    <div class="page-header">
      <span class="page-title">🎬 分镜制作</span>
    </div>

    <!-- Generate -->
    <el-card class="gen-card">
      <template #header><b>AI 生成分镜</b></template>
      <el-form :model="genForm" inline>
        <el-form-item label="选择剧本">
          <el-select v-model="genForm.scriptId" placeholder="请选择剧本" style="width: 240px">
            <el-option
              v-for="s in scripts"
              :key="s.id"
              :label="s.title || `第${s.episodeNo}集`"
              :value="s.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="generating" :disabled="!genForm.scriptId" @click="handleGenerate">
            🤖 AI 拆解分镜
          </el-button>
        </el-form-item>
      </el-form>
      <div v-if="currentTask" class="task-progress">
        <el-progress :percentage="currentTask.progress" :status="taskStatus(currentTask.status)" />
        <div class="task-msg">{{ currentTask.message }}</div>
      </div>
    </el-card>

    <!-- Storyboard grid -->
    <div class="storyboard-grid" v-if="storyboards.length">
      <div v-for="shot in storyboards" :key="shot.id" class="shot-card">
        <div class="shot-image">
          <img v-if="shot.imageUrl" :src="shot.imageUrl" alt="shot" />
          <div v-else class="shot-placeholder">
            <el-icon size="32" color="#a0aec0"><Picture /></el-icon>
            <div class="shot-no">镜头 {{ shot.shotNo }}</div>
          </div>
        </div>
        <div class="shot-info">
          <div class="shot-header">
            <span class="shot-no-badge">{{ shot.shotNo }}</span>
            <span class="shot-camera">{{ shot.cameraAngle }}</span>
            <span class="shot-duration">{{ shot.duration }}s</span>
          </div>
          <div class="shot-desc">{{ shot.description }}</div>
          <div v-if="shot.dialogue" class="shot-dialogue">
            <el-icon size="12"><ChatDotRound /></el-icon> {{ shot.dialogue }}
          </div>
          <div v-if="shot.narration" class="shot-narration">
            <el-icon size="12"><Reading /></el-icon> {{ shot.narration }}
          </div>
          <div class="shot-status">
            <span :class="`status-badge status-${shot.status}`">{{ shotStatusLabel(shot.status) }}</span>
          </div>
        </div>
      </div>
    </div>

    <el-empty v-else-if="!generating" description="暂无分镜，请先生成剧本再拆解分镜" />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { scriptApi } from '@/api/script'
import { storyboardApi } from '@/api/storyboard'
import { taskApi } from '@/api/task'

const route = useRoute()
const projectId = route.params.id

const scripts = ref<any[]>([])
const storyboards = ref<any[]>([])
const generating = ref(false)
const currentTask = ref<any>(null)
let pollTimer: any = null

const genForm = ref({ scriptId: '' })

const shotStatusLabel = (s: string) => ({
  draft: '草稿', image_generated: '图片已生成',
  video_generated: '视频已生成', audio_generated: '音频已生成', completed: '已完成'
}[s] || s)
const taskStatus = (s: string) => s === 'SUCCESS' ? 'success' : s === 'FAILED' ? 'exception' : undefined

async function handleGenerate() {
  if (!genForm.value.scriptId) return
  generating.value = true
  try {
    const res = await storyboardApi.generate({ scriptId: genForm.value.scriptId, projectId: projectId as string })
    currentTask.value = res.data.data
    startPolling(currentTask.value.id)
  } catch {
    generating.value = false
  }
}

function startPolling(taskId: number) {
  pollTimer = setInterval(async () => {
    try {
      const res = await taskApi.get(taskId)
      currentTask.value = res.data.data
      if (['SUCCESS', 'FAILED'].includes(currentTask.value.status)) {
        clearInterval(pollTimer)
        generating.value = false
        if (currentTask.value.status === 'SUCCESS') {
          ElMessage.success('分镜生成成功！')
          loadStoryboards()
        } else {
          ElMessage.error('分镜生成失败: ' + currentTask.value.message)
        }
      }
    } catch {}
  }, 2000)
}

async function loadStoryboards() {
  const res = await storyboardApi.listByProject(projectId as string)
  storyboards.value = res.data.data || []
}

onMounted(async () => {
  const res = await scriptApi.listByProject(projectId as string)
  scripts.value = res.data.data || []
  await loadStoryboards()
})
onUnmounted(() => clearInterval(pollTimer))
</script>

<style scoped>
.page-container { padding: 24px; }
.page-header { margin-bottom: 20px; }
.page-title { font-size: 20px; font-weight: 600; }
.gen-card { margin-bottom: 20px; }
.task-progress { margin-top: 16px; }
.task-msg { font-size: 13px; color: #718096; margin-top: 8px; }

.storyboard-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 16px;
}

.shot-card {
  background: #fff;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 2px 8px rgba(0,0,0,0.08);
}

.shot-image {
  height: 160px;
  background: #f7f8fa;
  overflow: hidden;
  position: relative;
}
.shot-image img { width: 100%; height: 100%; object-fit: cover; }
.shot-placeholder {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

.shot-info { padding: 12px; }
.shot-header { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.shot-no-badge {
  background: #6366f1;
  color: #fff;
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 12px;
  font-weight: 700;
}
.shot-camera { font-size: 11px; color: #718096; background: #f0f0f0; padding: 2px 6px; border-radius: 4px; }
.shot-duration { font-size: 11px; color: #718096; margin-left: auto; }

.shot-desc { font-size: 13px; color: #4a5568; margin-bottom: 6px; line-height: 1.5; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.shot-dialogue, .shot-narration { font-size: 12px; color: #718096; margin-bottom: 4px; display: flex; align-items: flex-start; gap: 4px; }
.shot-status { margin-top: 8px; }
</style>
