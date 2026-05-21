<template>
  <div class="page-container">
    <div class="page-header">
      <span class="page-title">分镜制作</span>
    </div>

    <el-card class="gen-card">
      <template #header><b>AI 生成分镜</b></template>
      <el-form :model="genForm" inline>
        <el-form-item label="选择剧本">
          <el-select v-model="genForm.scriptId" placeholder="请选择剧本" style="width: 260px">
            <el-option
              v-for="script in scripts"
              :key="script.id"
              :label="script.title || `第${script.episodeNo}集`"
              :value="script.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="previewDialog.generating" :disabled="!genForm.scriptId || previewDialog.saving" @click="handleGenerate">
            AI 拆解分镜
          </el-button>
        </el-form-item>
      </el-form>
      <div class="form-tip">分镜 JSON 会先进入预览弹窗，你可以手工调整后再确认入库。</div>
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
        <el-input-number
          v-model="recommendFilter.minScore"
          :min="0"
          :max="100"
          :step="5"
          size="small"
          controls-position="right"
          placeholder="最低分"
        />
        <el-input-number
          v-model="recommendFilter.maxApply"
          :min="1"
          :max="200"
          size="small"
          controls-position="right"
          placeholder="最多应用"
        />
        <el-switch
          v-model="recommendFilter.onlyCurrentEpisode"
          size="small"
          active-text="仅当前剧本"
        />
        <el-button size="small" @click="applyRecommendations" :loading="selectionLoading" :disabled="recommendedCount === 0">
          应用推荐
        </el-button>
        <el-button size="small" @click="clearDynamicSelection" :loading="selectionLoading" :disabled="selectedCount === 0">
          清空动态选择
        </el-button>
        <el-button size="small" @click="applyDerivedTexts" :loading="selectionLoading" :disabled="!storyboards.length">
          一键应用派生文案
        </el-button>
        <el-button size="small" @click="clearTextOverrides" :loading="selectionLoading" :disabled="!storyboards.length">
          一键清空覆盖恢复自动派生
        </el-button>
        <el-button size="small" type="primary" @click="$router.push(`/projects/${route.params.id}/synthesis`)">
          前往合成导出 <el-icon class="ml-1"><ArrowRight /></el-icon>
        </el-button>
      </div>
    </el-card>

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
          <div class="shot-text-panel">
            <div class="text-field">
              <div class="text-field-head">
                <span>上屏字幕</span>
                <el-switch
                  v-model="shot.userLockedSubtitle"
                  size="small"
                  active-text="锁定"
                  @change="saveShotTextFields(shot)"
                />
              </div>
              <el-input
                v-model="shot.subtitleText"
                type="textarea"
                :rows="2"
                placeholder="留空则按对白/旁白与合成配置生成"
              />
              <div v-if="shot.resolvedSubtitle" class="text-resolved">生效预览：{{ shot.resolvedSubtitle }}</div>
            </div>
            <div class="text-field">
              <div class="text-field-head">
                <span>配音稿</span>
                <el-switch
                  v-model="shot.userLockedTts"
                  size="small"
                  active-text="锁定"
                  @change="saveShotTextFields(shot)"
                />
              </div>
              <el-input
                v-model="shot.ttsText"
                type="textarea"
                :rows="2"
                placeholder="留空则由旁白+口播派生"
              />
              <div v-if="shot.resolvedTts" class="text-resolved">生效预览：{{ shot.resolvedTts }}</div>
            </div>
            <div v-if="shot.duration && shot.duration > 5" class="duration-warn">镜头时长 &gt;5s，短剧建议拆句或改时长</div>
            <el-button
              type="primary"
              size="small"
              :loading="textSaveLoadingId === shot.id"
              @click="saveShotTextFields(shot)"
            >
              保存字幕与配音
            </el-button>
          </div>
          <div class="shot-status">
            <span :class="`status-badge status-${shot.status}`">{{ shotStatusLabel(shot.status) }}</span>
          </div>

          <div class="dynamic-card">
            <div class="dynamic-top">
              <div class="dynamic-labels">
                <span v-if="shot.dynamicRecommended" class="dynamic-badge recommended">推荐动态</span>
                <span v-if="shot.dynamicSelected" class="dynamic-badge selected">已选动态</span>
                <span class="dynamic-badge">{{ motionTierLabel(shot.motionTier) }}</span>
                <span class="dynamic-score">{{ shot.dynamicScore || 0 }} 分</span>
              </div>
              <span class="dynamic-motion">{{ motionLevelLabel(shot.motionLevel) }}</span>
            </div>

            <div class="dynamic-reason">{{ shot.dynamicReason || '当前镜头更适合保留为静态图片' }}</div>
            <div class="dynamic-reason subtle">{{ shot.motionTierReason || '按镜头语义自动分配动效档位' }}</div>

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

    <el-empty v-else description="暂无分镜，请先生成剧本再拆解分镜" />

    <AiPreviewDialog
      v-model="previewDialog.visible"
      title="分镜预览工作台"
      subtitle="实时接收 AI 拆解的分镜 JSON，允许在确认前手工修正结构与字段。"
      description="保存时会替换当前剧本已有分镜。建议保留 shots 数组和每个镜头的核心字段，确保 JSON 可解析。"
      :phase-text="previewDialog.generating ? '流式生成中' : '待确认保存'"
      :loading="previewDialog.generating"
      :confirm-loading="previewDialog.saving"
      :confirm-disabled="previewDialog.generating || previewDialog.repairing || !previewDialog.content.trim()"
      confirm-text="确认保存分镜"
      :error-message="previewDialog.error"
      @confirm="savePreview"
      @cancel="resetPreviewDialog"
    >
      <template #meta>
        <span class="preview-chip">{{ selectedScriptLabel }}</span>
        <span class="preview-chip">JSON 结构</span>
      </template>
      <template #footer-prefix>
        <div class="preview-footer-tools">
          <span v-if="storyboardRepairHint" class="preview-footer-tip">
            {{ storyboardRepairHint }}
          </span>
          <el-button
            v-if="canRepairStoryboardPreview"
            text
            type="warning"
            :loading="previewDialog.repairing"
            @click="repairPreview"
          >
            AI 修复 JSON
          </el-button>
        </div>
      </template>
      <div class="preview-toolbar">
        <span>建议保留 {"shots": [...]} 的整体结构；保存时会严格校验 JSON，再替换当前剧本分镜。</span>
      </div>
      <el-input
        v-model="previewDialog.content"
        type="textarea"
        :rows="30"
        class="preview-editor"
        placeholder="AI 生成的分镜 JSON 会实时出现在这里，可直接修改。"
      />
    </AiPreviewDialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import AiPreviewDialog from '@/components/AiPreviewDialog.vue'
import { scriptApi } from '@/api/script'
import { storyboardApi } from '@/api/storyboard'

type StoryboardPreviewErrorData = {
  type?: string
  repairable?: boolean
}

const route = useRoute()
const projectId = route.params.id

const scripts = ref<any[]>([])
const storyboards = ref<any[]>([])
const selectionLoading = ref(false)
const updatingIds = ref(new Set<number>())
const genForm = ref({ scriptId: '' })

const previewDialog = ref({
  visible: false,
  generating: false,
  saving: false,
  repairing: false,
  error: '',
  content: '',
  errorData: null as StoryboardPreviewErrorData | null,
})

const recommendedCount = computed(() => storyboards.value.filter((shot) => !!shot.dynamicRecommended).length)
const selectedCount = computed(() => storyboards.value.filter((shot) => !!shot.dynamicSelected).length)
const selectedScript = computed(() => scripts.value.find((script) => String(script.id) === String(genForm.value.scriptId)))
const selectedScriptLabel = computed(() => selectedScript.value?.title || (selectedScript.value ? `第${selectedScript.value.episodeNo}集` : '未选择剧本'))
const recommendFilter = ref({
  minScore: 60,
  maxApply: 10,
  onlyCurrentEpisode: false,
})
const canRepairStoryboardPreview = computed(() => {
  const errorData = previewDialog.value.errorData
  const hasLegacyParseError = previewDialog.value.error.includes('分镜预览解析失败')
  const hasRepairableError =
    (errorData?.type === 'STORYBOARD_PREVIEW_PARSE_FAILED' && errorData?.repairable)
    || hasLegacyParseError
  return Boolean(
    hasRepairableError
      && !previewDialog.value.generating
      && !previewDialog.value.saving
      && !previewDialog.value.repairing
      && previewDialog.value.content.trim(),
  )
})
const storyboardRepairHint = computed(() => {
  if (canRepairStoryboardPreview.value) {
    return '检测到分镜 JSON 结构异常，可尝试 AI 自动修复'
  }
  return ''
})

const shotStatusLabel = (status: string) => ({
  draft: '草稿',
  image_generated: '图片已生成',
  video_submitted: '视频任务已提交',
  video_polling: '视频生成中',
  video_generated: '视频已生成',
  audio_generated: '音频已生成',
  completed: '已完成',
}[status] || status)

const motionLevelLabel = (level: string) => ({ low: '轻动态', medium: '中动态', high: '强动态' }[level] || '轻动态')
const motionTierLabel = (tier: string) => ({ A: 'A档 真i2v', B: 'B档 轻动态', C: 'C档 静帧' }[(tier || 'C').toUpperCase()] || 'C档 静帧')

function resetPreviewDialog() {
  previewDialog.value.visible = false
  previewDialog.value.generating = false
  previewDialog.value.saving = false
  previewDialog.value.repairing = false
  previewDialog.value.error = ''
  previewDialog.value.errorData = null
}

async function handleGenerate() {
  if (!genForm.value.scriptId) {
    return
  }

  previewDialog.value.visible = true
  previewDialog.value.generating = true
  previewDialog.value.error = ''
  previewDialog.value.content = ''
  previewDialog.value.errorData = null

  try {
    await storyboardApi.generatePreviewStream(
      { scriptId: genForm.value.scriptId, projectId: projectId as string },
      {
        onChunk: (content) => {
          previewDialog.value.content += content
        },
        onDone: () => {
          previewDialog.value.generating = false
          previewDialog.value.errorData = null
          ElMessage.success('分镜预览生成完成，请确认后保存')
        },
        onError: (message) => {
          previewDialog.value.error = message
          previewDialog.value.errorData = null
          previewDialog.value.generating = false
        },
      },
    )
  } catch (error: any) {
    previewDialog.value.error = error?.message || '分镜预览生成失败'
    previewDialog.value.errorData = null
    previewDialog.value.generating = false
  }
}

async function savePreview() {
  previewDialog.value.saving = true
  try {
    await storyboardApi.savePreview({
      scriptId: genForm.value.scriptId,
      projectId: projectId as string,
      content: previewDialog.value.content,
    })
    ElMessage.success('分镜已保存')
    resetPreviewDialog()
    await loadStoryboards()
  } catch (error: any) {
    previewDialog.value.error = error?.message || '分镜预览保存失败'
    previewDialog.value.errorData = extractStoryboardPreviewErrorData(error)
  } finally {
    previewDialog.value.saving = false
  }
}

async function repairPreview() {
  previewDialog.value.repairing = true
  try {
    const res = await storyboardApi.repairPreview({
      scriptId: genForm.value.scriptId,
      projectId: projectId as string,
      content: previewDialog.value.content,
    })
    const payload = res.data.data || {}
    previewDialog.value.content = payload.content || previewDialog.value.content
    previewDialog.value.error = ''
    previewDialog.value.errorData = null
    ElMessage.success(`分镜 JSON 已修复，共 ${payload.shotCount || 0} 个镜头，请确认后保存`)
  } catch (error: any) {
    previewDialog.value.error = error?.message || '分镜预览修复失败'
    previewDialog.value.errorData = extractStoryboardPreviewErrorData(error) ?? previewDialog.value.errorData
  } finally {
    previewDialog.value.repairing = false
  }
}

function extractStoryboardPreviewErrorData(error: any): StoryboardPreviewErrorData | null {
  const data = error?.data || error?.response?.data?.data
  if (!data || typeof data !== 'object') {
    return null
  }
  return data as StoryboardPreviewErrorData
}

async function loadStoryboards() {
  const res = await storyboardApi.listByProject(projectId as string)
  storyboards.value = res.data.data || []
  for (const s of storyboards.value) {
    s.userLockedSubtitle = !!s.userLockedSubtitle
    s.userLockedTts = !!s.userLockedTts
  }
}

const textSaveLoadingId = ref<string | number | null>(null)
async function saveShotTextFields(shot: any) {
  if (!shot?.id || textSaveLoadingId.value === shot.id) return
  textSaveLoadingId.value = shot.id
  try {
    await storyboardApi.update(shot.id, {
      subtitleText: shot.subtitleText ?? '',
      ttsText: shot.ttsText ?? '',
      userLockedSubtitle: !!shot.userLockedSubtitle,
      userLockedTts: !!shot.userLockedTts,
    })
    const res = await storyboardApi.get(shot.id)
    const u = res.data.data
    if (u) {
      shot.resolvedSubtitle = u.resolvedSubtitle
      shot.resolvedTts = u.resolvedTts
    }
  } catch {
    ElMessage.error('保存字幕/配音失败')
  } finally {
    if (textSaveLoadingId.value === shot.id) textSaveLoadingId.value = null
  }
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
  const currentEpisodeNo = selectedScript.value?.episodeNo
  const filtered = storyboards.value.filter((shot) => {
    if (!shot.dynamicRecommended || shot.dynamicSelected) return false
    if ((shot.dynamicScore || 0) < recommendFilter.value.minScore) return false
    if (recommendFilter.value.onlyCurrentEpisode && currentEpisodeNo != null && shot.episodeNo !== currentEpisodeNo) return false
    return true
  })
  const targets = filtered
    .sort((a, b) => (b.dynamicScore || 0) - (a.dynamicScore || 0))
    .slice(0, Math.max(1, recommendFilter.value.maxApply))
  if (!targets.length) {
    return
  }

  selectionLoading.value = true
  try {
    await Promise.all(targets.map((shot) =>
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
  const targets = storyboards.value.filter((shot) => !!shot.dynamicSelected)
  if (!targets.length) {
    return
  }

  selectionLoading.value = true
  try {
    await Promise.all(targets.map((shot) =>
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

async function applyDerivedTexts() {
  if (!storyboards.value.length) return
  selectionLoading.value = true
  try {
    await Promise.all(storyboards.value.map((shot) => storyboardApi.update(shot.id, {
      subtitleText: shot.resolvedSubtitle || '',
      ttsText: shot.resolvedTts || '',
      userLockedSubtitle: true,
      userLockedTts: true,
    })))
    await loadStoryboards()
    ElMessage.success('已批量应用派生文案并锁定')
  } catch {
    await loadStoryboards()
    ElMessage.error('批量应用派生文案失败')
  } finally {
    selectionLoading.value = false
  }
}

async function clearTextOverrides() {
  if (!storyboards.value.length) return
  selectionLoading.value = true
  try {
    await Promise.all(storyboards.value.map((shot) => storyboardApi.update(shot.id, {
      subtitleText: '',
      ttsText: '',
      userLockedSubtitle: false,
      userLockedTts: false,
    })))
    await loadStoryboards()
    ElMessage.success('已清空覆盖，恢复自动派生')
  } catch {
    await loadStoryboards()
    ElMessage.error('批量清空覆盖失败')
  } finally {
    selectionLoading.value = false
  }
}

onMounted(async () => {
  const res = await scriptApi.listByProject(projectId as string)
  scripts.value = res.data.data || []
  await loadStoryboards()
})
</script>

<style scoped>
.page-container { padding: 24px; }
.page-header { margin-bottom: 20px; }
.page-title { font-size: 20px; font-weight: 600; }
.gen-card { margin-bottom: 20px; }
.form-tip { color: var(--text-muted); font-size: 12px; }

.preview-chip {
  display: inline-flex;
  align-items: center;
  padding: 8px 12px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.12);
  border: 1px solid rgba(255, 255, 255, 0.18);
  color: #fff;
  font-size: 12px;
  font-weight: 600;
}

.preview-toolbar {
  margin-bottom: 14px;
  padding: 12px 14px;
  border-radius: var(--radius-md);
  background: var(--bg-muted);
  border: 1px solid var(--border);
  color: var(--text-secondary);
  font-size: 12px;
}

.preview-editor :deep(.el-textarea__inner) {
  min-height: 360px;
  border-radius: var(--radius-lg);
  border: 1px solid var(--border);
  background: var(--bg-card);
  color: var(--text-primary);
  font-family: 'JetBrains Mono', 'SFMono-Regular', Consolas, 'Liberation Mono', monospace;
  font-size: 12px;
  line-height: 1.7;
  box-shadow: inset 0 1px 3px rgba(15, 23, 42, 0.04);
}

.preview-footer-tools {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.recommend-card {
  margin-bottom: 20px;
}

.recommend-header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
}

.recommend-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
}

.recommend-sub {
  margin-top: 6px;
  color: var(--text-muted);
  font-size: 13px;
}

.recommend-stats {
  display: flex;
  gap: 10px;
  font-size: 12px;
  color: var(--text-secondary);
}

.recommend-actions {
  margin-top: 14px;
  display: flex;
  gap: 10px;
}

.storyboard-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 16px;
}

.shot-card {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 16px;
  overflow: hidden;
  box-shadow: var(--shadow-sm);
}

.shot-card.recommended {
  border-color: rgba(59, 130, 246, 0.28);
}

.shot-card.selected {
  border-color: rgba(99, 102, 241, 0.32);
  box-shadow: 0 10px 24px rgba(99, 102, 241, 0.14);
}

.shot-image {
  height: 240px;
  background: var(--bg-muted);
  display: flex;
  align-items: center;
  justify-content: center;
}

.shot-image img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.shot-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
}

.shot-no {
  font-size: 13px;
  color: var(--text-muted);
}

.shot-info {
  padding: 16px;
}

.shot-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.shot-no-badge,
.shot-camera,
.shot-duration {
  display: inline-flex;
  align-items: center;
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
}

.shot-no-badge { background: var(--primary-glow); color: var(--primary); }
.shot-camera { background: var(--bg-muted); color: var(--text-secondary); }
.shot-duration { background: rgba(6, 182, 212, 0.1); color: var(--accent-dark); }

.shot-desc,
.shot-dialogue,
.shot-narration {
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-secondary);
}

.shot-dialogue,
.shot-narration {
  margin-top: 10px;
  display: flex;
  gap: 6px;
  align-items: flex-start;
}

.shot-text-panel {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed var(--border);
}
.text-field { margin-bottom: 10px; }
.text-field-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 12px;
  color: var(--text-muted);
  margin-bottom: 6px;
}
.text-resolved { font-size: 11px; color: var(--text-muted); margin-top: 4px; }
.duration-warn { font-size: 12px; color: var(--color-warning); margin-top: 8px; }

.shot-status {
  margin-top: 12px;
}

.dynamic-card {
  margin-top: 14px;
  padding: 12px;
  border-radius: var(--radius-lg);
  background: var(--bg-muted);
  border: 1px solid var(--border);
}

.dynamic-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.dynamic-labels {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.dynamic-badge,
.dynamic-score,
.dynamic-motion {
  display: inline-flex;
  align-items: center;
  padding: 4px 10px;
  border-radius: var(--radius-full);
  font-size: 11px;
  font-weight: 600;
}

.dynamic-badge.recommended { background: #dbeafe; color: #2563eb; }
.dynamic-badge.selected { background: #ede9fe; color: var(--secondary); }
.dynamic-score { background: #ecfccb; color: #4d7c0f; }
.dynamic-motion { background: var(--bg-muted); color: var(--text-secondary); }

.dynamic-reason {
  margin-top: 10px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-secondary);
}

.dynamic-reason.subtle {
  margin-top: 4px;
  color: var(--text-muted);
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
  color: var(--text-primary);
}

.dynamic-toggle-sub {
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-muted);
}
</style>
