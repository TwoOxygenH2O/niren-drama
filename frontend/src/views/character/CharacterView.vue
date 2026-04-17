<template>
  <div class="page-container">
    <div class="page-header">
      <span class="page-title">👤 角色管理</span>
      <el-button type="primary" :icon="Plus" @click="showCreate = true">添加角色</el-button>
    </div>

    <div class="card-grid" v-if="characters.length">
      <div v-for="char in characters" :key="char.id" class="char-card">
        <div class="char-avatar">
          <img v-if="char.imageUrl" :src="char.imageUrl" :alt="char.name" />
          <div v-else class="char-placeholder">
            <el-icon size="40" color="#a0aec0"><User /></el-icon>
          </div>
        </div>
        <div class="char-info">
          <div class="char-name">{{ char.name }}</div>
          <div class="char-meta">
            <span class="char-gender">{{ char.gender === 'female' ? '♀ 女' : '♂ 男' }}</span>
            <span class="char-age">{{ char.age }}岁</span>
          </div>
          <div class="char-desc" v-if="char.description">{{ char.description }}</div>
          <div class="char-voice" v-if="char.voiceName">
            <el-icon size="12"><Microphone /></el-icon> {{ char.voiceName }}
          </div>
          <div class="char-actions">
            <el-button size="small" type="primary" :loading="generatingId === char.id" @click="generateImage(char)">
              AI生成图像
            </el-button>
            <el-popconfirm title="确认删除？" @confirm="deleteChar(char.id)">
              <template #reference>
                <el-button size="small" type="danger" text>删除</el-button>
              </template>
            </el-popconfirm>
          </div>
        </div>
      </div>
    </div>
    <el-empty v-else description="暂无角色，请添加剧中角色" />

    <!-- Create dialog -->
    <el-dialog v-model="showCreate" title="添加角色" width="560px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="角色名" required>
          <el-input v-model="form.name" placeholder="如：陆明浩" />
        </el-form-item>
        <el-form-item label="性别">
          <el-radio-group v-model="form.gender">
            <el-radio value="male">男</el-radio>
            <el-radio value="female">女</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="年龄">
          <el-input v-model="form.age" placeholder="如：28" style="width: 120px" />
        </el-form-item>
        <el-form-item label="性格">
          <el-input v-model="form.personality" placeholder="如：霸道冷漠，实则温柔" />
        </el-form-item>
        <el-form-item label="外貌">
          <el-input v-model="form.appearance" type="textarea" :rows="2" placeholder="如：身高185cm，黑发，深邃眼睛..." />
        </el-form-item>
        <el-form-item label="简介">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="角色背景故事..." />
        </el-form-item>
        <el-form-item label="音色">
          <el-select v-model="form.voiceId" placeholder="选择TTS音色" style="width: 100%" @change="onVoiceChange">
            <el-option v-for="v in voices" :key="v.voiceId" :label="v.name" :value="v.voiceId" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreate = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { characterApi } from '@/api/character'
import { taskApi } from '@/api/task'

const route = useRoute()
const projectId = route.params.id

const characters = ref<any[]>([])
const voices = ref<any[]>([])
const showCreate = ref(false)
const submitting = ref(false)
const generatingId = ref<any>(null)

const form = ref({
  name: '', gender: 'male', age: '', personality: '', appearance: '', description: '', voiceId: '', voiceName: ''
})

function onVoiceChange(val: string) {
  const voice = voices.value.find(v => v.voiceId === val)
  if (voice) form.value.voiceName = voice.name
}

async function load() {
  const res = await characterApi.listByProject(projectId as string)
  characters.value = res.data.data || []
}

async function handleCreate() {
  if (!form.value.name) return ElMessage.warning('请输入角色名')
  submitting.value = true
  try {
    await characterApi.create({ projectId: projectId as string, ...form.value })
    ElMessage.success('角色创建成功')
    showCreate.value = false
    load()
  } finally {
    submitting.value = false
  }
}

async function generateImage(char: any) {
  generatingId.value = char.id
  try {
    const res = await characterApi.generateImage(char.id)
    const taskId = res.data.data.id
    // Poll for completion
    const timer = setInterval(async () => {
      const r = await taskApi.get(taskId)
      const task = r.data.data
      if (task.status === 'SUCCESS') {
        clearInterval(timer)
        generatingId.value = null
        ElMessage.success('角色图像生成成功')
        load()
      } else if (task.status === 'FAILED') {
        clearInterval(timer)
        generatingId.value = null
        ElMessage.error('角色图像生成失败')
      }
    }, 2000)
  } catch {
    generatingId.value = null
  }
}

async function deleteChar(id: number) {
  await characterApi.delete(id)
  ElMessage.success('删除成功')
  load()
}

onMounted(async () => {
  load()
  try {
    const res = await taskApi.voices()
    voices.value = res.data.data || []
  } catch {}
})
</script>

<style scoped>
.page-container { padding: 24px; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 24px; }
.page-title { font-size: 20px; font-weight: 600; }

.card-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 20px; }

.char-card {
  background: #fff;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 2px 8px rgba(0,0,0,0.08);
  display: flex;
  flex-direction: column;
}

.char-avatar {
  height: 200px;
  background: linear-gradient(135deg, #e0e7ff, #f3e8ff);
  overflow: hidden;
}
.char-avatar img { width: 100%; height: 100%; object-fit: cover; }
.char-placeholder { height: 100%; display: flex; align-items: center; justify-content: center; }

.char-info { padding: 16px; flex: 1; }
.char-name { font-size: 16px; font-weight: 700; color: #1a202c; margin-bottom: 6px; }
.char-meta { display: flex; gap: 8px; margin-bottom: 8px; }
.char-gender, .char-age { font-size: 12px; color: #718096; background: #f0f0f0; padding: 2px 8px; border-radius: 8px; }
.char-desc { font-size: 13px; color: #4a5568; margin-bottom: 8px; line-height: 1.5; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.char-voice { font-size: 12px; color: #6366f1; display: flex; align-items: center; gap: 4px; margin-bottom: 12px; }
.char-actions { display: flex; gap: 8px; }
</style>
