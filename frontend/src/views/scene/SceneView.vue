<template>
  <div class="page-container">
    <div class="page-header">
      <span class="page-title">🌄 场景管理</span>
      <el-button type="primary" :icon="Plus" @click="showCreate = true">添加场景</el-button>
    </div>

    <div class="card-grid" v-if="scenes.length">
      <div v-for="scene in scenes" :key="scene.id" class="scene-card">
        <div class="scene-image">
          <img v-if="scene.imageUrl" :src="scene.imageUrl" :alt="scene.name" />
          <div v-else class="scene-placeholder">
            <el-icon size="40" style="color: var(--text-muted)"><Sunny /></el-icon>
          </div>
        </div>
        <div class="scene-info">
          <div class="scene-name">{{ scene.name }}</div>
          <div class="scene-meta">
            <span v-if="scene.timeOfDay" class="scene-tag">{{ timeLabel(scene.timeOfDay) }}</span>
            <span v-if="scene.location" class="scene-tag">{{ locationLabel(scene.location) }}</span>
          </div>
          <div v-if="scene.description" class="scene-desc">{{ scene.description }}</div>
          <div class="scene-actions">
            <el-button size="small" type="primary" @click="generateImage(scene)">AI生成背景</el-button>
            <el-popconfirm title="确认删除？" @confirm="deleteScene(scene.id)">
              <template #reference>
                <el-button size="small" type="danger" text>删除</el-button>
              </template>
            </el-popconfirm>
          </div>
        </div>
      </div>
    </div>
    <el-empty v-else description="暂无场景，请添加剧中场景" />

    <el-dialog v-model="showCreate" title="添加场景" width="500px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="场景名" required>
          <el-input v-model="form.name" placeholder="如：总裁办公室" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="3" placeholder="场景环境描述..." />
        </el-form-item>
        <el-form-item label="时间">
          <el-select v-model="form.timeOfDay" placeholder="选择时间">
            <el-option label="白天" value="day" />
            <el-option label="夜晚" value="night" />
            <el-option label="清晨" value="dawn" />
            <el-option label="黄昏" value="dusk" />
          </el-select>
        </el-form-item>
        <el-form-item label="地点">
          <el-select v-model="form.location" placeholder="选择地点类型">
            <el-option label="室内" value="indoor" />
            <el-option label="室外" value="outdoor" />
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
import request from '@/api/request'
import { taskApi } from '@/api/task'

const route = useRoute()
const projectId = route.params.id

const scenes = ref<any[]>([])
const showCreate = ref(false)
const submitting = ref(false)
const form = ref({ name: '', description: '', timeOfDay: 'day', location: 'indoor' })

const timeLabel = (t: string) => ({ day: '白天', night: '夜晚', dawn: '清晨', dusk: '黄昏' }[t] || t)
const locationLabel = (l: string) => ({ indoor: '室内', outdoor: '室外' }[l] || l)

async function load() {
  const res = await request.get(`/scenes/project/${projectId}`)
  scenes.value = res.data.data || []
}

async function handleCreate() {
  if (!form.value.name) return ElMessage.warning('请输入场景名')
  submitting.value = true
  try {
    await request.post('/scenes', { projectId: projectId as string, ...form.value })
    ElMessage.success('场景创建成功')
    showCreate.value = false
    load()
  } finally {
    submitting.value = false
  }
}

async function generateImage(scene: any) {
  const res = await request.post(`/scenes/${scene.id}/generate-image`)
  const taskId = res.data.data.id
  ElMessage.info('后台生成中，请稍候...')
  const timer = setInterval(async () => {
    const r = await taskApi.get(taskId)
    if (r.data.data.status === 'SUCCESS') {
      clearInterval(timer)
      ElMessage.success('场景图生成成功')
      load()
    } else if (r.data.data.status === 'FAILED') {
      clearInterval(timer)
      ElMessage.error('场景图生成失败')
    }
  }, 2000)
}

async function deleteScene(id: number) {
  await request.delete(`/scenes/${id}`)
  ElMessage.success('删除成功')
  load()
}

onMounted(load)
</script>

<style scoped>
.page-container { padding: 24px; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 24px; }
.page-title { font-size: 20px; font-weight: 600; }
.card-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 20px; }

.scene-card { background: var(--bg-card); border-radius: var(--radius-md); overflow: hidden; box-shadow: var(--shadow-sm); }
.scene-image { height: 160px; background: linear-gradient(135deg, rgba(6, 182, 212, 0.1), rgba(16, 185, 129, 0.1)); overflow: hidden; }
.scene-image img { width: 100%; height: 100%; object-fit: cover; }
.scene-placeholder { height: 100%; display: flex; align-items: center; justify-content: center; }
.scene-info { padding: 16px; }
.scene-name { font-size: 15px; font-weight: 700; color: var(--text-primary); margin-bottom: 6px; }
.scene-meta { display: flex; gap: 8px; margin-bottom: 8px; }
.scene-tag { font-size: 11px; color: var(--text-secondary); background: var(--bg-muted); padding: 2px 8px; border-radius: var(--radius-sm); }
.scene-desc { font-size: 13px; color: var(--text-muted); margin-bottom: 12px; }
.scene-actions { display: flex; gap: 8px; }
</style>
