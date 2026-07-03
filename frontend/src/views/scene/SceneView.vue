<template>
  <div class="scene-console">
    <section class="scene-header">
      <div>
        <p>空间与光线台</p>
        <h1>场景空间控制</h1>
        <span>管理地点、时段、光线气质和可复用背景，减少镜头间空间漂移。</span>
      </div>
      <el-button type="primary" :icon="Plus" @click="showCreate = true">添加场景</el-button>
    </section>

    <template v-if="scenes.length">
      <section v-if="heroScene" class="space-board">
        <article class="space-preview">
          <div class="scene-image-large">
            <img v-if="heroScene.imageUrl" :src="heroScene.imageUrl" :alt="heroScene.name" />
            <div v-else class="scene-placeholder">
              <el-icon size="56"><Sunny /></el-icon>
            </div>
            <div class="camera-line camera-line-a" />
            <div class="camera-line camera-line-b" />
          </div>
          <div class="space-copy">
            <p>{{ locationLabel(heroScene.location) }} · {{ timeLabel(heroScene.timeOfDay) }}</p>
            <h2>{{ heroScene.name }}</h2>
            <span>{{ heroScene.description || '空间描述待补充' }}</span>
          </div>
        </article>

        <aside class="light-panel">
          <div class="light-title">光线参数</div>
          <div class="light-meter">
            <span>主光</span>
            <i style="--w: 78%" />
          </div>
          <div class="light-meter">
            <span>环境光</span>
            <i style="--w: 62%" />
          </div>
          <div class="light-meter">
            <span>景深</span>
            <i style="--w: 48%" />
          </div>
        </aside>
      </section>

      <section class="scene-timeline">
        <article v-for="scene in scenes" :key="scene.id" class="scene-card">
          <div class="scene-thumb">
            <img v-if="scene.imageUrl" :src="scene.imageUrl" :alt="scene.name" />
            <div v-else class="scene-placeholder">
              <el-icon size="34"><Sunny /></el-icon>
            </div>
          </div>
          <div class="scene-info">
            <div class="scene-name">{{ scene.name }}</div>
            <div class="scene-meta">
              <span v-if="scene.timeOfDay">{{ timeLabel(scene.timeOfDay) }}</span>
              <span v-if="scene.location">{{ locationLabel(scene.location) }}</span>
            </div>
            <div v-if="scene.description" class="scene-desc">{{ scene.description }}</div>
            <div class="scene-actions">
              <el-button size="small" type="primary" @click="generateImage(scene)">生成背景</el-button>
              <el-popconfirm title="确认删除？" @confirm="deleteScene(scene.id)">
                <template #reference>
                  <el-button size="small" type="danger" text>删除</el-button>
                </template>
              </el-popconfirm>
            </div>
          </div>
        </article>
      </section>
    </template>

    <section v-else class="scene-empty">
      <h2>场景空间待建立</h2>
      <p>地点、时段和光线设定会在这里形成空间档案。</p>
      <el-button type="primary" :icon="Plus" @click="showCreate = true">添加场景</el-button>
    </section>

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
import { computed, ref, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus, Sunny } from '@element-plus/icons-vue'
import request from '@/api/request'
import { taskApi } from '@/api/task'

const route = useRoute()
const projectId = route.params.id

const scenes = ref<any[]>([])
const showCreate = ref(false)
const submitting = ref(false)
const sceneTaskTimer = ref<number | null>(null)
const form = ref({ name: '', description: '', timeOfDay: 'day', location: 'indoor' })
const heroScene = computed(() => scenes.value[0] || null)

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

function stopSceneTaskPolling() {
  if (sceneTaskTimer.value !== null) {
    window.clearInterval(sceneTaskTimer.value)
    sceneTaskTimer.value = null
  }
}

async function generateImage(scene: any) {
  const res = await request.post(`/scenes/${scene.id}/generate-image`)
  const taskId = res.data.data.id
  ElMessage.info('后台生成中，请稍候...')
  stopSceneTaskPolling()
  let attempts = 0
  sceneTaskTimer.value = window.setInterval(async () => {
    attempts += 1
    if (attempts > 150) {
      stopSceneTaskPolling()
      ElMessage.warning('生成任务仍在后台执行，可稍后刷新查看')
      return
    }
    try {
      const r = await taskApi.get(taskId)
      const task = r.data.data
      if (task.status === 'SUCCESS') {
        stopSceneTaskPolling()
        ElMessage.success('场景图生成成功')
        await load()
      } else if (task.status === 'FAILED') {
        stopSceneTaskPolling()
        ElMessage.error(task.message || '场景图生成失败')
      }
    } catch { /* 单次查询失败不中断轮询，由 attempts 上限兜底 */ }
  }, 2000)
}

async function deleteScene(id: number) {
  await request.delete(`/scenes/${id}`)
  ElMessage.success('删除成功')
  load()
}

onMounted(load)
onUnmounted(stopSceneTaskPolling)
</script>

<style scoped>
.scene-console {
  min-height: 100%;
  padding: 30px;
  background: var(--page-environment);
}

.scene-header,
.space-board,
.scene-timeline,
.scene-empty {
  max-width: 1220px;
  margin: 0 auto;
}

.scene-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 22px;
}

.scene-header p {
  margin: 0 0 8px;
  color: var(--primary);
  font-size: 14px;
  font-weight: 800;
}

.scene-header h1 {
  margin: 0;
  font-size: clamp(30px, 4vw, 46px);
  line-height: 1.08;
  letter-spacing: 0;
}

.scene-header span {
  display: block;
  margin-top: 10px;
  color: var(--text-secondary);
  font-size: 16px;
}

.space-board {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 18px;
  margin-bottom: 18px;
}

.space-preview,
.light-panel,
.scene-card,
.scene-empty {
  border: 1px solid var(--border);
  border-radius: 20px;
  background: var(--surface-panel);
  backdrop-filter: blur(var(--glass-blur)) saturate(145%);
  box-shadow: var(--shadow-md), inset 0 1px 0 rgba(255, 255, 255, 0.055);
}

.space-preview {
  overflow: hidden;
}

.scene-image-large {
  position: relative;
  height: 430px;
  margin: 18px;
  overflow: hidden;
  border-radius: 18px;
  border: 1px solid var(--border);
  background:
    linear-gradient(rgba(255, 255, 255, 0.035) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.035) 1px, transparent 1px),
    radial-gradient(circle at 22% 18%, rgba(91, 208, 255, 0.13), transparent 34%),
    radial-gradient(circle at 76% 84%, rgba(255, 208, 138, 0.1), transparent 40%),
    rgba(255, 255, 255, 0.04);
  background-size: 36px 36px, 36px 36px, auto, auto, auto;
}

.scene-image-large img,
.scene-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.scene-placeholder {
  height: 100%;
  display: grid;
  place-items: center;
  color: var(--text-muted);
}

.camera-line {
  position: absolute;
  inset: 13%;
  border: 1px solid rgba(255, 255, 255, 0.18);
  border-radius: 18px;
  pointer-events: none;
}

.camera-line-b {
  inset: 26%;
  border-color: rgba(103, 232, 249, 0.24);
}

.space-copy {
  padding: 0 28px 28px;
}

.space-copy p {
  margin: 0 0 8px;
  color: var(--primary);
  font-weight: 800;
}

.space-copy h2 {
  margin: 0 0 10px;
  font-size: 36px;
  line-height: 1.12;
  letter-spacing: 0;
}

.space-copy span {
  color: var(--text-secondary);
  font-size: 16px;
  line-height: 1.65;
}

.light-panel {
  display: flex;
  flex-direction: column;
  justify-content: end;
  gap: 20px;
  padding: 24px;
}

.light-title {
  margin-bottom: auto;
  font-size: 22px;
  font-weight: 850;
}

.light-meter {
  display: grid;
  gap: 9px;
}

.light-meter span {
  color: var(--text-secondary);
  font-size: 14px;
}

.light-meter i {
  display: block;
  width: 100%;
  height: 8px;
  overflow: hidden;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.065);
}

.light-meter i::before {
  content: "";
  display: block;
  width: var(--w);
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, var(--primary), var(--secondary), var(--accent));
}

.scene-timeline {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}

.scene-card {
  overflow: hidden;
}

.scene-thumb {
  height: 170px;
  overflow: hidden;
  background:
    radial-gradient(circle at 22% 18%, rgba(91, 208, 255, 0.12), transparent 36%),
    radial-gradient(circle at 82% 76%, rgba(255, 208, 138, 0.1), transparent 40%),
    rgba(255, 255, 255, 0.04);
}

.scene-info { padding: 16px; }
.scene-name { margin-bottom: 8px; font-size: 18px; font-weight: 850; color: var(--text-primary); }
.scene-meta { display: flex; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
.scene-meta span { color: var(--text-secondary); background: var(--bg-muted); padding: 4px 9px; border-radius: 999px; font-size: 14px; }
.scene-desc { color: var(--text-muted); margin-bottom: 12px; line-height: 1.55; }
.scene-actions { display: flex; gap: 8px; flex-wrap: wrap; }

.scene-empty {
  min-height: 420px;
  display: grid;
  place-items: center;
  text-align: center;
  padding: 34px;
}

.scene-empty h2 {
  margin: 0 0 10px;
  font-size: 28px;
}

.scene-empty p {
  margin: 0 0 20px;
  color: var(--text-secondary);
  font-size: 16px;
}

@media (max-width: 980px) {
  .space-board {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 680px) {
  .scene-console {
    padding: 20px;
  }

  .scene-header {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
