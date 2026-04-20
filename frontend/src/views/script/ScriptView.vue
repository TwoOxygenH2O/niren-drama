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

      <!-- Progress -->
      <div v-if="currentTask" class="task-progress">
        <el-progress
          :percentage="currentTask.progress"
          :status="taskStatus(currentTask.status)"
        />
        <div class="task-msg">{{ currentTask.message }}</div>
      </div>
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
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { scriptApi } from '@/api/script'
import { taskApi } from '@/api/task'

const route = useRoute()
const projectId = route.params.id

const scripts = ref<any[]>([])
const selectedScript = ref<any>(null)
const generating = ref(false)
const saving = ref(false)
const currentTask = ref<any>(null)
let pollTimer: any = null

const genForm = ref({ idea: '', genre: '都市言情', episodeNo: 1, totalEpisodes: 30 })

const scriptStatusLabel = (s: string) => ({ draft: '草稿', ai_generated: 'AI生成', reviewed: '已审核' }[s] || s)
const taskStatus = (s: string) => s === 'SUCCESS' ? 'success' : s === 'FAILED' ? 'exception' : undefined

async function handleGenerate() {
  if (!genForm.value.idea) return
  generating.value = true
  try {
    const res = await scriptApi.generate({ projectId: projectId as string, ...genForm.value })
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
          ElMessage.success('剧本生成成功！')
          loadScripts()
        } else {
          ElMessage.error('剧本生成失败')
        }
      }
    } catch {}
  }, 2000)
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

onMounted(loadScripts)
onUnmounted(() => clearInterval(pollTimer))
</script>

<style scoped>
.page-container { padding: 24px; }
.page-header { margin-bottom: 20px; }
.page-title { font-size: 20px; font-weight: 600; }

.gen-card { margin-bottom: 20px; }

.task-progress { margin-top: 16px; }
.task-msg { font-size: 13px; color: #718096; margin-top: 8px; }

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
</style>
