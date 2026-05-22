<template>
  <div class="page-container">
    <div class="page-header">
      <span class="page-title">角色管理</span>
      <el-button type="primary" :icon="Plus" @click="showCreate = true">添加角色</el-button>
    </div>

    <div class="card-grid" v-if="characters.length">
      <div v-for="char in characters" :key="char.id" class="char-card" @click="openGallery(char)">
        <div class="char-avatar">
          <img v-if="char.imageUrl" :src="char.imageUrl" :alt="char.name" />
          <div v-else class="char-placeholder">
            <el-icon size="40" style="color: var(--text-muted)"><User /></el-icon>
          </div>
          <div v-if="charImageCount(char) > 1" class="char-image-badge">{{ charImageCount(char) }} 张</div>
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
            <span v-if="char.speechRate" class="char-rate"> · 语速 {{ char.speechRate }}%</span>
          </div>
          <div v-if="char.ttsNote" class="char-tts-note">{{ char.ttsNote }}</div>
          <audio
            v-if="previewAudio.id === char.id && previewAudio.url"
            :id="`preview-audio-${char.id}`"
            :src="previewAudio.url"
            controls
            class="char-audio"
          />
          <div class="char-actions" @click.stop>
            <el-button size="small" type="primary" :loading="generatingId === char.id" @click="generateImage(char)">
              AI生成图像
            </el-button>
            <el-button size="small" :loading="previewingId === char.id" @click="previewVoice(char)">
              预听
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

    <!-- Image gallery dialog -->
    <el-dialog v-model="galleryVisible" :title="galleryChar?.name + ' — 角色形象'" width="720px" destroy-on-close>
      <div v-if="galleryImages.length" class="gallery-grid">
        <div v-for="(url, idx) in galleryImages" :key="idx" class="gallery-item" @click="openPreview(idx)">
          <img :src="url" :alt="`${galleryChar?.name} 形象 ${idx + 1}`" />
          <span v-if="idx === 0" class="gallery-main-tag">主图</span>
        </div>
      </div>
      <el-empty v-else description="暂无角色图片，点击下方按钮生成" />
      <template #footer>
        <el-button type="primary" :loading="generatingId === galleryChar?.id" @click="galleryChar && generateImage(galleryChar)">
          AI生成图像
        </el-button>
      </template>
    </el-dialog>

    <!-- Fullscreen image preview -->
    <el-dialog v-model="previewImageVisible" width="90vw" destroy-on-close append-to-body>
      <div class="preview-image-wrap">
        <img :src="galleryImages[previewImageIdx]" :alt="`${galleryChar?.name} 形象`" />
      </div>
      <div class="preview-nav">
        <el-button :disabled="previewImageIdx <= 0" @click="previewImageIdx--">上一张</el-button>
        <span>{{ previewImageIdx + 1 }} / {{ galleryImages.length }}</span>
        <el-button :disabled="previewImageIdx >= galleryImages.length - 1" @click="previewImageIdx++">下一张</el-button>
      </div>
    </el-dialog>

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
            <el-option
              v-for="v in voices"
              :key="v.voiceId"
              :label="`${v.name}（${v.voiceId}）`"
              :value="v.voiceId"
            >
              <div class="voice-option">
                <div class="voice-option-head">
                  <span class="voice-option-name">{{ v.name }}</span>
                  <span class="voice-option-id">{{ v.voiceId }}</span>
                </div>
                <div v-if="v.description" class="voice-option-desc">{{ v.description }}</div>
              </div>
            </el-option>
          </el-select>
          <div class="voice-tip">音色列表会跟随当前默认 TTS 模型变化。若要使用更细的语气描述，请在 AI 配置中心将 TTS 模型设为 qwen3-tts-instruct-flash。</div>
        </el-form-item>
        <el-form-item label="语速">
          <el-input-number v-model="form.speechRate" :min="50" :max="200" :step="5" placeholder="100=正常" />
          <span class="field-hint">100=1.0x，可空</span>
        </el-form-item>
        <el-form-item label="导演说明">
          <el-input v-model="form.ttsNote" type="textarea" :rows="2" placeholder="合并进 TTS 演绎指令，如：语气更冷、句尾略收" />
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
import { nextTick, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { characterApi } from '@/api/character'
import { taskApi } from '@/api/task'

interface VoiceOption {
  voiceId: string
  name: string
  description?: string
  gender?: string
  language?: string
}

const route = useRoute()
const projectId = route.params.id

const characters = ref<any[]>([])
const voices = ref<VoiceOption[]>([])
const showCreate = ref(false)
const submitting = ref(false)
const generatingId = ref<any>(null)
const previewingId = ref<any>(null)
const previewAudio = ref<{ id: number | null; url: string; text: string }>({ id: null, url: '', text: '' })
const galleryVisible = ref(false)
const galleryChar = ref<any>(null)
const galleryImages = ref<string[]>([])
const previewImageVisible = ref(false)
const previewImageIdx = ref(0)

const form = ref({
  name: '', gender: 'male', age: '', personality: '', appearance: '', description: '', voiceId: '', voiceName: '',
  speechRate: undefined as number | undefined, ttsNote: '',
})

function onVoiceChange(val: string) {
  const voice = voices.value.find(v => v.voiceId === val)
  if (voice) form.value.voiceName = voice.name
}

function parseImageUrls(raw: any): string[] {
  if (!raw) return []
  if (Array.isArray(raw)) return raw.filter(Boolean)
  if (typeof raw === 'string') {
    try {
      const arr = JSON.parse(raw)
      if (Array.isArray(arr)) return arr.filter(Boolean)
    } catch { /* not JSON */ }
    return raw.split(',').map((s: string) => s.trim()).filter(Boolean)
  }
  return []
}

function charImageCount(char: any): number {
  const urls = parseImageUrls(char.imageUrls)
  return urls.length || (char.imageUrl ? 1 : 0)
}

function openGallery(char: any) {
  galleryChar.value = char
  const urls = parseImageUrls(char.imageUrls)
  galleryImages.value = urls.length > 0 ? urls : (char.imageUrl ? [char.imageUrl] : [])
  galleryVisible.value = true
}

function openPreview(idx: number) {
  previewImageIdx.value = idx
  previewImageVisible.value = true
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
        ElMessage.success(task.message || '角色图像生成成功')
        await load()
        // 如果画廊正在展示该角色，刷新画廊数据
        if (galleryVisible.value && galleryChar.value?.id === char.id) {
          const updated = characters.value.find((c: any) => c.id === char.id)
          if (updated) {
            galleryChar.value = updated
            const urls = parseImageUrls(updated.imageUrls)
            galleryImages.value = urls.length > 0 ? urls : (updated.imageUrl ? [updated.imageUrl] : [])
          }
        }
      } else if (task.status === 'FAILED') {
        clearInterval(timer)
        generatingId.value = null
        ElMessage.error(task.message || '角色图像生成失败')
      }
    }, 2000)
  } catch {
    generatingId.value = null
  }
}

async function previewVoice(char: any) {
  previewingId.value = char.id
  try {
    const text = `${char.name || '角色'}，这句是语音预听，用来确认音色、语速和情绪是否匹配。`
    const res = await characterApi.previewTts(char.id, text)
    const data = res.data.data || {}
    if (!data.audioUrl) {
      ElMessage.error('预听失败：未返回音频')
      return
    }
    previewAudio.value = { id: char.id, url: data.audioUrl, text: data.text || text }
    await nextTick()
    const audio = document.getElementById(`preview-audio-${char.id}`) as HTMLAudioElement | null
    if (audio) {
      audio.currentTime = 0
      await audio.play()
    }
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '预听失败')
  } finally {
    previewingId.value = null
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
  background: var(--bg-card);
  border-radius: var(--radius-md);
  overflow: hidden;
  box-shadow: var(--shadow-sm);
  display: flex;
  flex-direction: column;
}

.char-avatar {
  height: 200px;
  background: linear-gradient(135deg, var(--primary-glow), rgba(236, 72, 153, 0.1));
  overflow: hidden;
}
.char-avatar img { width: 100%; height: 100%; object-fit: cover; }
.char-placeholder { height: 100%; display: flex; align-items: center; justify-content: center; }

.char-info { padding: 16px; flex: 1; }
.char-name { font-size: 16px; font-weight: 700; color: var(--text-primary); margin-bottom: 6px; }
.char-meta { display: flex; gap: 8px; margin-bottom: 8px; }
.char-gender, .char-age { font-size: 12px; color: var(--text-muted); background: var(--bg-muted); padding: 2px 8px; border-radius: var(--radius-sm); }
.char-desc { font-size: 13px; color: var(--text-secondary); margin-bottom: 8px; line-height: 1.5; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.char-voice { font-size: 12px; color: var(--primary); display: flex; align-items: center; gap: 4px; margin-bottom: 6px; flex-wrap: wrap; }
.char-rate { color: var(--text-muted); font-weight: 500; }
.char-tts-note { font-size: 11px; color: var(--text-muted); margin-bottom: 12px; line-height: 1.4; }
.char-audio { width: 100%; margin-bottom: 10px; }
.field-hint { margin-left: 8px; font-size: 12px; color: var(--text-muted); }
.char-actions { display: flex; gap: 8px; }
.voice-tip { margin-top: 8px; font-size: 12px; line-height: 1.5; color: var(--text-muted); }
.voice-option { display: flex; flex-direction: column; gap: 4px; padding: 2px 0; }
.voice-option-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.voice-option-name { font-weight: 600; color: var(--text-primary); }
.voice-option-id { font-size: 12px; color: var(--text-muted); }
.voice-option-desc { font-size: 12px; line-height: 1.4; color: var(--text-secondary); white-space: normal; }

.char-card { cursor: pointer; transition: transform 0.15s, box-shadow 0.15s; }
.char-card:hover { transform: translateY(-2px); box-shadow: var(--shadow-md); }

.char-image-badge {
  position: absolute;
  bottom: 8px;
  right: 8px;
  background: rgba(0,0,0,0.6);
  color: #fff;
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 10px;
}
.char-avatar { position: relative; }

.gallery-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 12px;
}
.gallery-item {
  position: relative;
  border-radius: var(--radius-md);
  overflow: hidden;
  cursor: pointer;
  aspect-ratio: 3 / 4;
  background: var(--bg-muted);
}
.gallery-item img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.2s;
}
.gallery-item:hover img { transform: scale(1.05); }
.gallery-main-tag {
  position: absolute;
  top: 6px;
  left: 6px;
  background: var(--primary);
  color: #fff;
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 4px;
}

.preview-image-wrap {
  display: flex;
  justify-content: center;
  align-items: center;
  max-height: 75vh;
}
.preview-image-wrap img {
  max-width: 100%;
  max-height: 75vh;
  object-fit: contain;
}
.preview-nav {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 16px;
  margin-top: 12px;
  font-size: 14px;
  color: var(--text-secondary);
}
</style>
