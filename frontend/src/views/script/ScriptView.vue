<template>
  <div class="page-container">
    <div class="page-header">
      <span class="page-title">剧本生成</span>
    </div>

    <el-card class="gen-card">
      <template #header><b>AI 生成剧本</b></template>
      <el-form :model="genForm" label-width="100px">
        <el-form-item label="创意描述">
          <el-input
            v-model="genForm.idea"
            type="textarea"
            :rows="4"
            placeholder="先用于生成全剧分集大纲与人物小传；后续生成剧本时，也会作为本次补充要求带给模型。"
          />
        </el-form-item>
        <el-form-item label="生成区间">
          <div class="episode-range-row">
            <el-input-number v-model="genForm.startEpisode" :min="1" :max="episodeUpperBound" style="width: 180px" />
            <span class="episode-range-sep">到</span>
            <el-input-number v-model="genForm.endEpisode" :min="1" :max="episodeUpperBound" style="width: 180px" />
          </div>
          <div class="form-tip">
            项目类型：{{ projectTypeLabel }}，项目题材：{{ projectGenreLabel }}。项目共 {{ projectInfo?.episodes || '—' }} 集，单集约 {{ projectInfo?.episodeDuration || '—' }} 秒。
          </div>
          <div class="form-tip">
            所有文本 AI 结果都会先进入预览弹窗，可修改后再确认入库。
          </div>
        </el-form-item>
        <el-form-item>
          <div class="action-row">
            <el-button
              type="primary"
              plain
              :loading="outlinePreview.generating"
              :disabled="!canGenerateOutline"
              @click="handleGenerateOutline"
            >
              生成大纲
            </el-button>
            <el-button
              type="primary"
              :loading="scriptPreview.generating"
              :disabled="!canGenerateScript"
              @click="handleGenerate"
            >
              根据大纲生成剧本
            </el-button>
            <el-button
              v-if="hasGeneratedScript"
              type="success"
              plain
              @click="$router.push(`/projects/${projectId}/storyboard`)"
            >
              前往下一流程：分镜制作
            </el-button>
          </div>
        </el-form-item>
      </el-form>

      <el-alert
        v-if="!hasGeneratedOutline"
        class="outline-alert"
        type="warning"
        :closable="false"
        title="当前项目还没有生成大纲和人物小传，请先点击“生成大纲”。"
      />
      <el-alert
        v-else
        class="outline-alert"
        type="success"
        :closable="false"
        title="项目已存在分集大纲和项目通用信息，可直接生成剧本。"
      />
    </el-card>

    <el-card v-if="projectInfo?.commonInfo" class="common-card">
      <template #header><b>项目通用信息</b></template>
      <div class="common-card-tip">后续剧本和分镜都会复用这里的人物小传、关系线和长期设定。</div>
      <pre class="common-info-content">{{ projectInfo.commonInfo }}</pre>
    </el-card>

    <div class="script-list" v-if="scripts.length">
      <el-card
        v-for="script in scripts"
        :key="script.id"
        class="script-card"
        :class="{ active: selectedScript?.id === script.id }"
        @click="selectScript(script)"
      >
        <div class="script-header">
          <span>{{ script.title || `第${script.episodeNo}集` }}</span>
          <span :class="`status-badge status-${script.status}`">{{ scriptStatusLabel(script.status) }}</span>
        </div>
        <div class="script-outline" v-if="script.summary">{{ script.summary }}</div>
        <div class="script-outline is-empty" v-else>未生成分集大纲</div>
      </el-card>
    </div>

    <el-card v-if="selectedScript" class="editor-card">
      <template #header>
        <div class="editor-header">
          <b>{{ selectedScript.title || '剧本内容' }}</b>
          <div class="editor-actions">
            <el-button size="small" @click="$router.push(`/projects/${projectId}/storyboard`)">
              生成分镜
            </el-button>
            <el-button size="small" type="primary" :loading="saving" @click="saveScript">
              保存
            </el-button>
          </div>
        </div>
      </template>
      <el-form label-position="top">
        <el-form-item label="标题">
          <el-input v-model="selectedScript.title" placeholder="请输入集标题" />
        </el-form-item>
        <el-form-item label="分集大纲">
          <el-input
            v-model="selectedScript.summary"
            type="textarea"
            :rows="8"
            placeholder="这一集的大纲会显示在这里"
          />
        </el-form-item>
        <el-form-item label="剧本内容">
          <el-input
            v-model="selectedScript.content"
            type="textarea"
            :rows="26"
            placeholder="剧本内容..."
            style="font-family: monospace; font-size: 13px"
          />
        </el-form-item>
      </el-form>
    </el-card>

    <AiPreviewDialog
      v-model="outlinePreview.visible"
      title="大纲预览工作台"
      subtitle="实时接收 AI 生成的项目通用信息与全剧分集大纲，允许修改后再确认入库。"
      description="保存前请保留项目通用信息和每一集的大纲标记块；如果模型漏了内容，可直接在这里补齐后再确认。"
      :phase-text="outlinePreview.generating ? '流式生成中' : '待确认保存'"
      :loading="outlinePreview.generating"
      :confirm-loading="outlinePreview.saving"
      :confirm-disabled="outlinePreview.generating || outlinePreview.repairing || !outlinePreview.content.trim()"
      confirm-text="确认保存大纲"
      :error-message="outlinePreview.error"
      @confirm="saveOutlinePreview"
      @cancel="resetOutlinePreview"
    >
      <template #meta>
        <span class="preview-chip">{{ projectTypeLabel }}</span>
        <span class="preview-chip">{{ projectGenreLabel }}</span>
        <span class="preview-chip">{{ projectInfo?.episodes || 0 }} 集</span>
      </template>
      <template #footer-prefix>
        <div class="outline-footer-tools">
          <span v-if="outlinePreviewRepairHint" class="preview-footer-tip">
            {{ outlinePreviewRepairHint }}
          </span>
          <el-button
            v-if="canRepairOutlinePreview"
            text
            type="warning"
            :loading="outlinePreview.repairing"
            @click="repairOutlinePreview"
          >
            AI 修复缺失集
          </el-button>
        </div>
      </template>
      <div class="preview-toolbar">
        <span>当前内容会直接作为项目通用信息和各集大纲的入库来源。</span>
      </div>
      <el-input
        v-model="outlinePreview.content"
        type="textarea"
        :rows="28"
        class="preview-editor"
        placeholder="AI 生成的大纲预览会实时出现在这里，可直接修改。"
      />
    </AiPreviewDialog>

    <AiPreviewDialog
      v-model="scriptPreview.visible"
      :title="scriptPreview.mode === 'single' ? '剧本预览工作台' : '批量剧本预览工作台'"
      :subtitle="scriptPreview.mode === 'single'
        ? '先看 AI 输出，再决定是否入库这一集剧本。'
        : '区间多集剧本会带标记输出，可在确认前集中修改。'"
      :description="scriptPreview.mode === 'single'
        ? '单集模式下可同时调整标题、大纲和剧本正文。'
        : '批量模式下请保留每一集的开始和结束标记，保存时会自动拆分并更新对应集数。'"
      :phase-text="scriptPreview.generating ? '流式生成中' : '待确认保存'"
      :loading="scriptPreview.generating"
      :confirm-loading="scriptPreview.saving"
      :confirm-disabled="scriptPreview.generating || !scriptPreview.content.trim()"
      :confirm-text="scriptPreview.mode === 'single' ? '确认保存剧本' : '确认保存批量剧本'"
      :error-message="scriptPreview.error"
      @confirm="saveScriptPreview"
      @cancel="resetScriptPreview"
    >
      <template #meta>
        <span class="preview-chip">{{ projectTypeLabel }}</span>
        <span class="preview-chip">{{ projectGenreLabel }}</span>
        <span class="preview-chip">{{ scriptPreviewRangeLabel }}</span>
      </template>
      <template #footer-prefix>
        <span class="preview-footer-tip">
          {{ scriptPreview.mode === 'single' ? '保存后会更新当前集剧本。' : '保存后会按标记拆分并批量更新区间剧本。' }}
        </span>
      </template>

      <el-form v-if="scriptPreview.mode === 'single'" label-position="top" class="preview-form">
        <el-form-item label="标题">
          <el-input v-model="scriptPreview.title" placeholder="输入剧本标题" />
        </el-form-item>
        <el-form-item label="分集大纲">
          <el-input
            v-model="scriptPreview.summary"
            type="textarea"
            :rows="8"
            placeholder="本集分集大纲"
          />
        </el-form-item>
        <el-form-item label="内容">
          <el-input
            v-model="scriptPreview.content"
            type="textarea"
            :rows="18"
            class="preview-editor"
            placeholder="AI 生成内容会在这里实时出现"
          />
        </el-form-item>
      </el-form>

      <template v-else>
        <div class="preview-toolbar">
          <span>请保留 ###EPISODE_START:X### 和 ###EPISODE_END### 标记，保存时会自动拆分对应集数。</span>
        </div>
        <el-input
          v-model="scriptPreview.content"
          type="textarea"
          :rows="28"
          class="preview-editor"
          placeholder="批量剧本预览会实时出现在这里，可按集修改。"
        />
      </template>
    </AiPreviewDialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import AiPreviewDialog from '@/components/AiPreviewDialog.vue'
import { projectApi } from '@/api/project'
import { scriptApi } from '@/api/script'
import { formatGenreLabel, formatProjectTypeLabel } from '@/constants/project'

type OutlinePreviewErrorData = {
  type?: string
  repairable?: boolean
  totalEpisodes?: number
  missingEpisodes?: number[]
  missingEpisodeRanges?: string
}

const route = useRoute()
const projectId = route.params.id

const scripts = ref<any[]>([])
const projectInfo = ref<any>(null)
const selectedScript = ref<any>(null)
const saving = ref(false)

const genForm = ref<{ idea: string; startEpisode?: number; endEpisode?: number }>({
  idea: '',
  startEpisode: 1,
  endEpisode: undefined,
})

const outlinePreview = ref({
  visible: false,
  content: '',
  generating: false,
  saving: false,
  repairing: false,
  error: '',
  errorData: null as OutlinePreviewErrorData | null,
})

const scriptPreview = ref({
  visible: false,
  mode: 'single' as 'single' | 'batch',
  generating: false,
  saving: false,
  error: '',
  existingId: '',
  episodeNo: 1,
  startEpisode: 1,
  endEpisode: 1,
  title: '',
  summary: '',
  content: '',
})

const episodeUpperBound = computed(() => projectInfo.value?.episodes || 120)
const hasEpisodeSelection = computed(() => genForm.value.startEpisode != null || genForm.value.endEpisode != null)
const outlineSeed = computed(() => (genForm.value.idea || projectInfo.value?.description || '').trim())
const hasGeneratedOutline = computed(() => Boolean(projectInfo.value?.commonInfo) || scripts.value.some((script) => !!script?.summary?.trim()))
const hasGeneratedScript = computed(() => scripts.value.some((script) => {
  const content = script?.content
  return typeof content === 'string' ? content.trim().length > 0 : Boolean(content)
}))
const canGenerateOutline = computed(() => Boolean(outlineSeed.value) && !hasGeneratedOutline.value && !outlinePreview.value.generating && !outlinePreview.value.saving)
const canGenerateScript = computed(() => hasEpisodeSelection.value && hasGeneratedOutline.value && !scriptPreview.value.generating && !scriptPreview.value.saving)
const projectTypeLabel = computed(() => formatProjectTypeLabel(projectInfo.value?.projectType))
const projectGenreLabel = computed(() => formatGenreLabel(projectInfo.value?.genre) || '未设置')
const canRepairOutlinePreview = computed(() => {
  const errorData = outlinePreview.value.errorData
  const hasLegacyParseError = outlinePreview.value.error.includes('大纲预览解析失败')
  const hasRepairableError =
    (errorData?.type === 'OUTLINE_PREVIEW_PARSE_INCOMPLETE' && errorData?.repairable)
    || hasLegacyParseError
  return Boolean(
    hasRepairableError
      && !outlinePreview.value.generating
      && !outlinePreview.value.saving
      && !outlinePreview.value.repairing
      && outlinePreview.value.content.trim(),
  )
})
const outlinePreviewRepairHint = computed(() => {
  if (outlinePreview.value.errorData?.missingEpisodeRanges) {
    return `缺失集：第 ${outlinePreview.value.errorData.missingEpisodeRanges} 集`
  }
  if (outlinePreview.value.error.includes('大纲预览解析失败')) {
    return '检测到大纲标记不完整，可尝试 AI 自动修复'
  }
  return ''
})
const scriptPreviewRangeLabel = computed(() => {
  if (scriptPreview.value.mode === 'single') {
    return `第 ${scriptPreview.value.episodeNo} 集`
  }
  return `第 ${scriptPreview.value.startEpisode}-${scriptPreview.value.endEpisode} 集`
})

const scriptStatusLabel = (status: string) => ({ draft: '草稿', ai_generated: 'AI生成', reviewed: '已审核' }[status] || status)

function findScriptByEpisode(episodeNo: number) {
  return scripts.value.find((script) => Number(script.episodeNo) === episodeNo)
}

function ensureOutlineReady(startEpisode: number, endEpisode: number) {
  if (!projectInfo.value?.commonInfo) {
    ElMessage.warning('请先生成大纲和人物小传')
    return false
  }

  const missingEpisodes: number[] = []
  for (let episodeNo = startEpisode; episodeNo <= endEpisode; episodeNo += 1) {
    const script = findScriptByEpisode(episodeNo)
    if (!script?.summary?.trim()) {
      missingEpisodes.push(episodeNo)
    }
  }

  if (missingEpisodes.length) {
    ElMessage.warning(`第 ${missingEpisodes.join('、')} 集还没有分集大纲，请先生成大纲`)
    return false
  }

  return true
}

function normalizeEpisodeRange() {
  const startValue = genForm.value.startEpisode
  const endValue = genForm.value.endEpisode
  const singleValue = startValue ?? endValue

  if (singleValue == null) {
    ElMessage.warning('请至少填写一个集数')
    return null
  }

  const startEpisode = startValue ?? singleValue
  const endEpisode = endValue ?? startEpisode

  if (startEpisode > endEpisode) {
    ElMessage.warning('起始集不能大于结束集')
    return null
  }

  if (projectInfo.value?.episodes) {
    if (startEpisode > projectInfo.value.episodes || endEpisode > projectInfo.value.episodes) {
      ElMessage.warning(`集数不能超过项目总集数 ${projectInfo.value.episodes}`)
      return null
    }
  }

  return {
    startEpisode,
    endEpisode,
    singleEpisode: startEpisode,
    isSingle: startEpisode === endEpisode,
  }
}

function resetOutlinePreview() {
  outlinePreview.value.visible = false
  outlinePreview.value.generating = false
  outlinePreview.value.saving = false
  outlinePreview.value.repairing = false
  outlinePreview.value.error = ''
  outlinePreview.value.errorData = null
}

function resetScriptPreview() {
  scriptPreview.value.visible = false
  scriptPreview.value.generating = false
  scriptPreview.value.saving = false
  scriptPreview.value.error = ''
}

async function handleGenerateOutline() {
  if (!outlineSeed.value) {
    ElMessage.warning('请先填写创意描述，或在项目详情中补充项目描述')
    return
  }

  outlinePreview.value.visible = true
  outlinePreview.value.content = ''
  outlinePreview.value.error = ''
  outlinePreview.value.errorData = null
  outlinePreview.value.generating = true

  try {
    await scriptApi.generateOutlinePreviewStream(
      {
        projectId: projectId as string,
        idea: outlineSeed.value,
      },
      {
        onChunk: (content) => {
          outlinePreview.value.content += content
        },
        onDone: () => {
          outlinePreview.value.generating = false
          outlinePreview.value.errorData = null
          ElMessage.success('大纲预览生成完成，请确认后保存')
        },
        onError: (message) => {
          outlinePreview.value.error = message
          outlinePreview.value.errorData = null
          outlinePreview.value.generating = false
        },
      },
    )
  } catch (error: any) {
    outlinePreview.value.error = error?.message || '大纲预览生成失败'
    outlinePreview.value.errorData = null
    outlinePreview.value.generating = false
  }
}

async function saveOutlinePreview() {
  outlinePreview.value.saving = true
  try {
    await scriptApi.saveOutlinePreview({
      projectId: projectId as string,
      content: outlinePreview.value.content,
      idea: outlineSeed.value,
    })
    ElMessage.success('大纲已保存')
    resetOutlinePreview()
    await Promise.all([loadProject(), loadScripts()])
  } catch (error: any) {
    outlinePreview.value.error = error?.message || '大纲预览保存失败'
    outlinePreview.value.errorData = extractOutlinePreviewErrorData(error)
  } finally {
    outlinePreview.value.saving = false
  }
}

async function repairOutlinePreview() {
  outlinePreview.value.repairing = true
  try {
    const res = await scriptApi.repairOutlinePreview({
      projectId: projectId as string,
      content: outlinePreview.value.content,
      idea: outlineSeed.value,
    })
    const payload = res.data.data || {}
    outlinePreview.value.content = payload.content || outlinePreview.value.content
    outlinePreview.value.error = ''
    outlinePreview.value.errorData = null
    const repairedRanges = payload.repairedEpisodeRanges ? `第 ${payload.repairedEpisodeRanges} 集` : '缺失集'
    ElMessage.success(`AI 已补齐 ${repairedRanges}，请确认后保存`)
  } catch (error: any) {
    outlinePreview.value.error = error?.message || '大纲预览修复失败'
    outlinePreview.value.errorData = extractOutlinePreviewErrorData(error) ?? outlinePreview.value.errorData
  } finally {
    outlinePreview.value.repairing = false
  }
}

function extractOutlinePreviewErrorData(error: any): OutlinePreviewErrorData | null {
  const data = error?.data || error?.response?.data?.data
  if (!data || typeof data !== 'object') {
    return null
  }
  return data as OutlinePreviewErrorData
}

async function handleGenerate() {
  const range = normalizeEpisodeRange()
  if (!range) {
    return
  }
  if (!ensureOutlineReady(range.startEpisode, range.endEpisode)) {
    return
  }

  if (range.isSingle) {
    await openSingleScriptPreview(range.singleEpisode)
    return
  }

  await openBatchScriptPreview(range.startEpisode, range.endEpisode)
}

async function openSingleScriptPreview(episodeNo: number) {
  const existingScript = findScriptByEpisode(episodeNo)
  scriptPreview.value.visible = true
  scriptPreview.value.mode = 'single'
  scriptPreview.value.generating = true
  scriptPreview.value.error = ''
  scriptPreview.value.existingId = existingScript?.id ? String(existingScript.id) : ''
  scriptPreview.value.episodeNo = episodeNo
  scriptPreview.value.startEpisode = episodeNo
  scriptPreview.value.endEpisode = episodeNo
  scriptPreview.value.title = existingScript?.title || `第${episodeNo}集`
  scriptPreview.value.summary = existingScript?.summary || ''
  scriptPreview.value.content = ''

  try {
    await scriptApi.generatePreviewStream(
      {
        projectId: projectId as string,
        idea: genForm.value.idea,
        episodeNo,
      },
      {
        onChunk: (content) => {
          scriptPreview.value.content += content
        },
        onDone: () => {
          scriptPreview.value.generating = false
          ElMessage.success('剧本预览生成完成，请确认后保存')
        },
        onError: (message) => {
          scriptPreview.value.error = message
          scriptPreview.value.generating = false
        },
      },
    )
  } catch (error: any) {
    scriptPreview.value.error = error?.message || '剧本预览生成失败'
    scriptPreview.value.generating = false
  }
}

async function openBatchScriptPreview(startEpisode: number, endEpisode: number) {
  scriptPreview.value.visible = true
  scriptPreview.value.mode = 'batch'
  scriptPreview.value.generating = true
  scriptPreview.value.error = ''
  scriptPreview.value.existingId = ''
  scriptPreview.value.startEpisode = startEpisode
  scriptPreview.value.endEpisode = endEpisode
  scriptPreview.value.content = ''

  try {
    await scriptApi.generatePreviewStream(
      {
        projectId: projectId as string,
        idea: genForm.value.idea,
        startEpisode,
        endEpisode,
      },
      {
        onChunk: (content) => {
          scriptPreview.value.content += content
        },
        onDone: () => {
          scriptPreview.value.generating = false
          ElMessage.success('批量剧本预览生成完成，请确认后保存')
        },
        onError: (message) => {
          scriptPreview.value.error = message
          scriptPreview.value.generating = false
        },
      },
    )
  } catch (error: any) {
    scriptPreview.value.error = error?.message || '批量剧本预览生成失败'
    scriptPreview.value.generating = false
  }
}

async function saveScriptPreview() {
  scriptPreview.value.saving = true
  try {
    if (scriptPreview.value.mode === 'single') {
      const res = await scriptApi.create({
        id: scriptPreview.value.existingId || undefined,
        projectId: projectId as string,
        episodeNo: scriptPreview.value.episodeNo,
        title: scriptPreview.value.title,
        summary: scriptPreview.value.summary,
        content: scriptPreview.value.content,
        aiPrompt: genForm.value.idea,
      })
      ElMessage.success('剧本已保存')
      resetScriptPreview()
      await loadScripts()
      selectedScript.value = { ...res.data.data }
      return
    }

    await scriptApi.saveBatchPreview({
      projectId: projectId as string,
      startEpisode: scriptPreview.value.startEpisode,
      endEpisode: scriptPreview.value.endEpisode,
      content: scriptPreview.value.content,
      idea: genForm.value.idea,
    })
    ElMessage.success('批量剧本已保存')
    resetScriptPreview()
    await loadScripts()
  } finally {
    scriptPreview.value.saving = false
  }
}

async function loadScripts() {
  const res = await scriptApi.listByProject(projectId as string)
  scripts.value = res.data.data || []
}

async function loadProject() {
  const res = await projectApi.get(projectId as string)
  projectInfo.value = res.data.data
}

async function selectScript(script: any) {
  const res = await scriptApi.get(script.id)
  selectedScript.value = { ...res.data.data }
}

async function saveScript() {
  saving.value = true
  try {
    await scriptApi.update(selectedScript.value.id, {
      content: selectedScript.value.content,
      title: selectedScript.value.title,
      summary: selectedScript.value.summary,
    })
    ElMessage.success('保存成功')
    await loadScripts()
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadProject(), loadScripts()])
})
</script>

<style scoped>
.page-container { padding: 24px; }
.page-header { margin-bottom: 20px; }
.page-title { font-size: 20px; font-weight: 600; }

.gen-card { margin-bottom: 20px; }

.action-row {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}

.episode-range-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.episode-range-sep {
  color: #64748b;
  font-size: 13px;
}

.form-tip {
  margin-top: 8px;
  color: #64748b;
  font-size: 12px;
}

.outline-alert {
  margin-top: 12px;
}

.common-card {
  margin-bottom: 20px;
}

.common-card-tip {
  margin-bottom: 10px;
  color: #64748b;
  font-size: 12px;
}

.common-info-content {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.65;
  color: #334155;
  font-family: inherit;
}

.script-list {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 20px;
}

.script-card {
  width: 220px;
  cursor: pointer;
  transition: box-shadow 0.2s;
}

.script-card:hover,
.script-card.active {
  box-shadow: 0 0 0 2px rgba(79, 70, 229, 0.22);
}

.script-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
  margin-bottom: 8px;
}

.script-outline {
  font-size: 12px;
  color: #475569;
  line-height: 1.55;
  display: -webkit-box;
  -webkit-line-clamp: 6;
  -webkit-box-orient: vertical;
  overflow: hidden;
  white-space: pre-wrap;
}

.script-outline.is-empty {
  color: #94a3b8;
}

.editor-card { margin-bottom: 20px; }

.editor-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.editor-actions {
  display: flex;
  gap: 8px;
}

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
  border-radius: 14px;
  background: #f8faff;
  border: 1px solid #e7ecff;
  color: #475569;
  font-size: 12px;
}

.preview-form {
  margin-top: 4px;
}

.preview-editor :deep(.el-textarea__inner) {
  min-height: 320px;
  border-radius: 16px;
  border: 1px solid #dbe2f0;
  background: linear-gradient(180deg, #fbfdff 0%, #f7f9fc 100%);
  color: #0f172a;
  font-family: 'JetBrains Mono', 'SFMono-Regular', Consolas, 'Liberation Mono', monospace;
  font-size: 12px;
  line-height: 1.7;
  box-shadow: inset 0 1px 3px rgba(15, 23, 42, 0.04);
}

.preview-footer-tip {
  color: #64748b;
  font-size: 12px;
}

.outline-footer-tools {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
</style>
