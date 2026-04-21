<template>
  <div class="page-container">
    <div class="page-header">
      <span class="page-title">分镜制作</span>
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

    <el-card v-if="storyboards.length" class="recommend-card">
      <div class="recommend-header">
        <div>
          <div class="recommend-title">动态镜头推荐</div>
          <div class="recommend-sub">系统会先给出建议，你可以决定哪些镜头用动态片段替代静态图片。</div>
        </div>
        <div class="recommend-stats">
          <span>推荐 {{ recommendedCount }}</span>
          <span>已选择 {{ selectedCount }}</span>
        </div>
      </div>

      <div class="recommend-actions">
        <el-button size="small" @click="applyRecommendations" :loading="selectionLoading" :disabled="recommendedCount === 0">
          应用推荐
        </el-button>
        <el-button size="small" @click="clearDynamicSelection" :loading="selectionLoading" :disabled="selectedCount === 0">
          清空动态选择
        </el-button>
      </div>
    </el-card>

    <!-- Storyboard grid -->
    <div class="storyboard-grid" v-if="storyboards.length">
      <div
        v-for="shot in storyboards"
        :key="shot.id"
        class="shot-card"
        :class="{ recommended: !!shot.dynamicRecommended, selected: !!shot.dynamicSelected }"
      >
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

          <div class="dynamic-card">
            <div class="dynamic-top">
              <div class="dynamic-labels">
                <span v-if="shot.dynamicRecommended" class="dynamic-badge recommended">推荐动态</span>
                <span v-if="shot.dynamicSelected" class="dynamic-badge selected">已选动态</span>
                <span class="dynamic-score">{{ shot.dynamicScore || 0 }} 分</span>
              </div>
              <span class="dynamic-motion">{{ motionLevelLabel(shot.motionLevel) }}</span>
            </div>

            <div class="dynamic-reason">{{ shot.dynamicReason || '当前镜头更适合保留为静态图片' }}</div>

            <div class="dynamic-toggle-row">
              <div>
                <div class="dynamic-toggle-title">用动态镜头代替图片</div>
                <div class="dynamic-toggle-sub">将基于关键帧生成独立动态片段</div>
              </div>
              <el-switch
                :model-value="!!shot.dynamicSelected"
                :loading="updatingIds.has(shot.id)"
                @change="handleDynamicToggle(shot, $event)"
              />
            </div>
          </div>
        </div>
      </div>
    </div>

    <el-empty v-else-if="!generating" description="暂无分镜，请先生成剧本再拆解分镜" />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted, onUnmounted } from 'vue'
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
const selectionLoading = ref(false)
const updatingIds = ref(new Set<number>())
let pollTimer: any = null

const genForm = ref({ scriptId: '' })

const recommendedCount = computed(() => storyboards.value.filter(shot => !!shot.dynamicRecommended).length)
const selectedCount = computed(() => storyboards.value.filter(shot => !!shot.dynamicSelected).length)

const shotStatusLabel = (s: string) => ({
  draft: '草稿', image_generated: '图片已生成',
  video_generated: '视频已生成', audio_generated: '音频已生成', completed: '已完成'
}[s] || s)
const taskStatus = (s: string) => s === 'SUCCESS' ? 'success' : s === 'FAILED' ? 'exception' : undefined
const motionLevelLabel = (level: string) => ({ low: '轻动态', medium: '中动态', high: '强动态' }[level] || '轻动态')

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

async function handleDynamicToggle(shot: any, dynamicSelected: boolean | string | number) {
  const normalizedSelected = Boolean(dynamicSelected)
  const previous = !!shot.dynamicSelected
  shot.dynamicSelected = normalizedSelected
  shot.renderMode = normalizedSelected ? 'video' : 'image'
  const ids = new Set(updatingIds.value)
  ids.add(shot.id)
  updatingIds.value = ids

  try {
    await storyboardApi.update(shot.id, {
      dynamicSelected: normalizedSelected,
      renderMode: normalizedSelected ? 'video' : 'image',
    })
  } catch {
    shot.dynamicSelected = previous
    shot.renderMode = previous ? 'video' : 'image'
    ElMessage.error('更新动态镜头选择失败')
  } finally {
    const nextIds = new Set(updatingIds.value)
    nextIds.delete(shot.id)
    updatingIds.value = nextIds
  }
}

async function applyRecommendations() {
  const targets = storyboards.value.filter(shot => !!shot.dynamicRecommended && !shot.dynamicSelected)
  if (!targets.length) {
    return
  }

  selectionLoading.value = true
  try {
    await Promise.all(targets.map(shot =>
      storyboardApi.update(shot.id, { dynamicSelected: true, renderMode: 'video' }),
    ))
    await loadStoryboards()
    ElMessage.success(`已应用 ${targets.length} 个动态镜头推荐`)
  } catch {
    await loadStoryboards()
    ElMessage.error('批量应用动态镜头推荐失败')
  } finally {
    selectionLoading.value = false
  }
}

async function clearDynamicSelection() {
  const targets = storyboards.value.filter(shot => !!shot.dynamicSelected)
  if (!targets.length) {
    return
  }

  selectionLoading.value = true
  try {
    await Promise.all(targets.map(shot =>
      storyboardApi.update(shot.id, { dynamicSelected: false, renderMode: 'image' }),
    ))
    await loadStoryboards()
    ElMessage.success('已清空动态镜头选择')
  } catch {
    await loadStoryboards()
    ElMessage.error('清空动态镜头选择失败')
  } finally {
    selectionLoading.value = false
  }
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

.recommend-card {
  margin-bottom: 20px;
}

.recommend-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}

.recommend-title {
  font-size: 15px;
  font-weight: 700;
  color: #1f2937;
}

.recommend-sub {
  margin-top: 6px;
  font-size: 13px;
  color: #64748b;
}

.recommend-stats {
  display: flex;
  gap: 12px;
  font-size: 13px;
  color: #475569;
  white-space: nowrap;
}

.recommend-actions {
  display: flex;
  gap: 10px;
  margin-top: 16px;
}

.storyboard-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 16px;
}

.shot-card {
  background: #fff;
  border-radius: 12px;
  overflow: hidden;
  border: 1px solid #e5e7eb;
  box-shadow: 0 2px 8px rgba(0,0,0,0.08);
}

.shot-card.recommended {
  border-color: #c7d2fe;
}

.shot-card.selected {
  border-color: #60a5fa;
  box-shadow: 0 0 0 2px rgba(96, 165, 250, 0.18);
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

.dynamic-card {
  margin-top: 12px;
  padding: 12px;
  border-radius: 10px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
}

.dynamic-top {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
}

.dynamic-labels {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.dynamic-badge,
.dynamic-score,
.dynamic-motion {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
}

.dynamic-badge.recommended {
  background: #eef2ff;
  color: #4338ca;
}

.dynamic-badge.selected {
  background: #dbeafe;
  color: #1d4ed8;
}

.dynamic-score {
  background: #f1f5f9;
  color: #334155;
}

.dynamic-motion {
  background: #ecfeff;
  color: #0f766e;
}

.dynamic-reason {
  margin-top: 10px;
  font-size: 12px;
  line-height: 1.6;
  color: #475569;
}

.dynamic-toggle-row {
  margin-top: 12px;
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
}

.dynamic-toggle-title {
  font-size: 13px;
  font-weight: 600;
  color: #1f2937;
}

.dynamic-toggle-sub {
  margin-top: 4px;
  font-size: 12px;
  color: #64748b;
}

@media (max-width: 768px) {
  .recommend-header,
  .dynamic-toggle-row {
    flex-direction: column;
    align-items: flex-start;
  }

  .recommend-actions {
    flex-wrap: wrap;
  }
}
</style>
