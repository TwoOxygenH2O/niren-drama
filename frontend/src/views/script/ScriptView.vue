<template>
  <div class="page-container">
    <div class="page-header">
      <span class="page-title">✍️ 剧本生成</span>
    </div>

    <!-- Generate form -->
    <el-card class="gen-card">
      <template #header><b>AI 生成剧本</b></template>
      <el-form :model="genForm" label-width="100px">
        <el-form-item label="创意描述">
          <el-input
            v-model="genForm.idea"
            type="textarea"
            :rows="4"
            placeholder="一句话描述你的剧情创意，越具体越好。例如：离婚当天，我觉醒了读心术，发现前夫一家全在演戏..."
          />
        </el-form-item>
        <el-form-item label="题材风格">
          <el-select v-model="genForm.genre" placeholder="选择题材" style="width: 200px">
            <el-option label="都市言情" value="都市言情" />
            <el-option label="玄幻奇幻" value="玄幻奇幻" />
            <el-option label="悬疑惊悚" value="悬疑惊悚" />
            <el-option label="都市职场" value="都市职场" />
            <el-option label="古装历史" value="古装宫廷" />
            <el-option label="甜宠逆袭" value="甜宠逆袭" />
            <el-option label="战神归来" value="战神归来" />
            <el-option label="豪门复仇" value="豪门复仇" />
            <el-option label="穿越重生" value="穿越重生" />
            <el-option label="萌宝助攻" value="萌宝助攻" />
          </el-select>
        </el-form-item>
        <el-form-item label="总集数">
          <el-input-number v-model="genForm.totalEpisodes" :min="1" :max="120" />
        </el-form-item>
        <el-form-item label="集数">
          <el-input-number v-model="genForm.episodeNo" :min="1" :max="100" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="generating" :disabled="!genForm.idea" @click="handleGenerate">
            🤖 AI 生成剧本
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- Script list -->
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
        <div class="script-prompt" v-if="script.aiPrompt">{{ script.aiPrompt }}</div>
      </el-card>
    </div>

    <!-- Script editor -->
    <el-card v-if="selectedScript" class="editor-card">
      <template #header>
        <div style="display:flex; justify-content:space-between; align-items:center">
          <b>{{ selectedScript.title || '剧本内容' }}</b>
          <div>
            <el-button size="small" @click="$router.push(`/projects/${projectId}/storyboard`)">
              → 生成分镜
            </el-button>
            <el-button size="small" type="primary" :loading="saving" @click="saveScript">保存</el-button>
          </div>
        </div>
      </template>
      <el-input
        v-model="selectedScript.content"
        type="textarea"
        :rows="30"
        placeholder="剧本内容..."
        style="font-family: monospace; font-size: 13px"
      />
    </el-card>

    <el-dialog
      v-model="previewVisible"
      title="AI 剧本预览"
      width="78%"
      top="4vh"
      destroy-on-close
      class="preview-dialog"
      :close-on-click-modal="false"
    >
      <div class="preview-meta">
        <el-tag type="primary">第 {{ previewForm.episodeNo }} 集</el-tag>
        <el-tag v-if="previewGenerating" type="warning">AI 生成中</el-tag>
        <el-tag v-else type="success">可编辑</el-tag>
      </div>
      <el-form label-width="80px" class="preview-form">
        <el-form-item label="标题">
          <el-input v-model="previewForm.title" placeholder="输入剧本标题" />
        </el-form-item>
        <el-form-item label="内容">
          <el-input
            v-model="previewForm.content"
            type="textarea"
            :rows="24"
            placeholder="AI 生成内容会在这里实时出现"
            style="font-family: monospace; font-size: 13px"
          />
        </el-form-item>
      </el-form>
      <div v-if="previewError" class="preview-error">{{ previewError }}</div>
      <template #footer>
        <el-button @click="previewVisible = false">关闭</el-button>
        <el-button type="primary" :disabled="!previewForm.content || previewGenerating" :loading="previewSaving" @click="savePreviewScript">
          保存到剧本库
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { scriptApi } from '@/api/script'

const route = useRoute()
const projectId = route.params.id

const scripts = ref<any[]>([])
const selectedScript = ref<any>(null)
const generating = ref(false)
const saving = ref(false)
const previewVisible = ref(false)
const previewGenerating = ref(false)
const previewSaving = ref(false)
const previewError = ref('')

const previewForm = ref({
  id: '',
  projectId: projectId as string,
  episodeNo: 1,
  title: '',
  content: '',
  aiPrompt: '',
})

const genForm = ref({ idea: '', genre: '都市言情', episodeNo: 1, totalEpisodes: 30 })

const scriptStatusLabel = (s: string) => ({ draft: '草稿', ai_generated: 'AI生成', reviewed: '已审核' }[s] || s)

async function handleGenerate() {
  if (!genForm.value.idea) return
  generating.value = true
  previewGenerating.value = true
  previewError.value = ''
  previewForm.value = {
    id: '',
    projectId: projectId as string,
    episodeNo: genForm.value.episodeNo,
    title: `第${genForm.value.episodeNo}集`,
    content: '',
    aiPrompt: genForm.value.idea,
  }
  previewVisible.value = true

  try {
    await scriptApi.generatePreviewStream(
      { projectId: projectId as string, ...genForm.value },
      {
        onChunk: (content) => {
          previewForm.value.content += content
        },
        onDone: () => {
          previewGenerating.value = false
          ElMessage.success('剧本生成完成，请确认并保存')
        },
        onError: (message) => {
          previewError.value = message
          previewGenerating.value = false
        },
      },
    )
  } catch (error: any) {
    previewError.value = error?.message || '剧本生成失败'
    previewGenerating.value = false
    generating.value = false
    return
  }
  generating.value = false
}

async function loadScripts() {
  const res = await scriptApi.listByProject(projectId as string)
  scripts.value = res.data.data || []
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
    })
    ElMessage.success('保存成功')
  } finally {
    saving.value = false
  }
}

async function savePreviewScript() {
  previewSaving.value = true
  try {
    const res = await scriptApi.create({
      projectId: projectId as string,
      episodeNo: previewForm.value.episodeNo,
      title: previewForm.value.title,
      content: previewForm.value.content,
      aiPrompt: previewForm.value.aiPrompt,
    })
    ElMessage.success('剧本已保存')
    previewVisible.value = false
    await loadScripts()
    selectedScript.value = { ...res.data.data }
  } finally {
    previewSaving.value = false
  }
}

onMounted(loadScripts)
</script>

<style scoped>
.page-container { padding: 24px; }
.page-header { margin-bottom: 20px; }
.page-title { font-size: 20px; font-weight: 600; }

.gen-card { margin-bottom: 20px; }

.script-list { display: flex; gap: 12px; flex-wrap: wrap; margin-bottom: 20px; }

.script-card {
  width: 200px;
  cursor: pointer;
  transition: box-shadow 0.2s;
}
.script-card:hover, .script-card.active { box-shadow: 0 0 0 2px #6366f1; }

.script-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
  margin-bottom: 8px;
}
.script-prompt { font-size: 12px; color: #718096; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.editor-card { margin-bottom: 20px; }

.preview-meta {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}

.preview-form {
  margin-top: 8px;
}

.preview-error {
  margin-top: 12px;
  color: #dc2626;
  font-size: 13px;
}
</style>
