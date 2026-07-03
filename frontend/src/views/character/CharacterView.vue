<template>
  <div class="character-workbench">
    <section class="character-hero">
      <div>
        <p>人物档案台</p>
        <h1>角色一致性锁定</h1>
        <span>定妆图、音色、年龄与人物性格在这里合并成可复用的角色资产。</span>
      </div>
      <el-button type="primary" :icon="Plus" @click="showCreate = true">添加角色</el-button>
    </section>

    <template v-if="characters.length">
      <section v-if="heroCharacter" class="identity-layout">
        <article class="portrait-stage" @click="heroCharacter && openGallery(heroCharacter)">
          <div class="portrait-frame">
            <img v-if="heroCharacter.imageUrl" :src="heroCharacter.imageUrl" :alt="heroCharacter.name" loading="lazy" />
            <div v-else class="portrait-placeholder">
              <el-icon size="56"><User /></el-icon>
            </div>
            <span class="portrait-badge">主角色</span>
          </div>
          <div class="portrait-copy">
            <p>{{ heroCharacter.gender === 'female' ? '女性角色' : '男性角色' }} · {{ heroCharacter.age || '-' }} 岁</p>
            <h2>{{ heroCharacter.name }}</h2>
            <span>{{ heroCharacter.description || heroCharacter.personality || '尚未补充人物小传' }}</span>
          </div>
        </article>

        <aside class="lock-panel">
          <div class="lock-panel-head">
            <span>一致性状态</span>
            <b>{{ lockedPortraitCount }}/{{ characters.length }}</b>
          </div>
          <div class="lock-row">
            <span>定妆图</span>
            <strong>{{ lockedPortraitCount }} 个已锁定</strong>
          </div>
          <div class="lock-row">
            <span>音色</span>
            <strong>{{ voiceConfiguredCount }} 个已配置</strong>
          </div>
          <div class="lock-row">
            <span>角色总数</span>
            <strong>{{ characters.length }} 个</strong>
          </div>
        </aside>
      </section>

      <section class="roster-strip">
        <article v-for="char in characters" :key="char.id" class="char-card" @click="openGallery(char)">
          <div class="char-avatar">
            <img v-if="char.imageUrl" :src="char.imageUrl" :alt="char.name" loading="lazy" />
            <div v-else class="char-placeholder">
              <el-icon size="34"><User /></el-icon>
            </div>
            <div v-if="charImageCount(char) > 1" class="char-image-badge">{{ charImageCount(char) }} 张</div>
          </div>
          <div class="char-info">
            <div class="char-name">{{ char.name }}</div>
            <div class="char-meta">
              <span>{{ char.gender === 'female' ? '女' : '男' }}</span>
              <span>{{ char.age || '-' }} 岁</span>
            </div>
            <div class="char-desc" v-if="char.description">{{ char.description }}</div>
            <div class="char-voice" v-if="char.voiceName">
              <el-icon size="14"><Microphone /></el-icon>
              {{ char.voiceName }}
              <span v-if="char.speechRate" class="char-rate">语速 {{ char.speechRate }}%</span>
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
                生成定妆
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
        </article>
      </section>
    </template>

    <section v-else class="empty-panel">
      <h2>角色档案待建立</h2>
      <p>人物定妆、音色与身份描述会在这里形成统一档案。</p>
      <el-button type="primary" :icon="Plus" @click="showCreate = true">添加角色</el-button>
    </section>

    <!-- Image gallery dialog -->
    <el-dialog v-model="galleryVisible" :title="galleryChar?.name + ' — 角色形象'" width="720px" destroy-on-close>
      <div v-if="galleryImages.length" class="gallery-grid">
        <div v-for="(url, idx) in galleryImages" :key="idx" class="gallery-item" @click="openPreview(idx)">
          <img :src="url" :alt="`${galleryChar?.name} 形象 ${idx + 1}`" loading="lazy" />
          <span v-if="idx === 0" class="gallery-main-tag">主图</span>
        </div>
      </div>
      <el-empty v-else description="尚未生成角色定妆图" />
      <template #footer>
        <el-button type="primary" :loading="generatingId === galleryChar?.id" @click="galleryChar && generateImage(galleryChar)">
          生成图像
        </el-button>
      </template>
    </el-dialog>

    <!-- Fullscreen image preview -->
    <el-dialog v-model="previewImageVisible" width="90vw" destroy-on-close append-to-body>
      <div class="preview-image-wrap">
        <img :src="galleryImages[previewImageIdx]" :alt="`${galleryChar?.name} 形象`" loading="lazy" />
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
          <div class="voice-tip">音色列表会跟随当前默认配音模型变化。若要使用更细的语气描述，请在模型配置中将配音模型设为 qwen3-tts-instruct-flash。</div>
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
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Microphone, Plus, User } from '@element-plus/icons-vue'
import { characterApi } from '@/api/character'
import { taskApi } from '@/api/task'
import { useTaskPolling } from '@/composables/useTaskPolling'

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

const heroCharacter = computed(() => characters.value[0] || null)
const lockedPortraitCount = computed(() => characters.value.filter((char: any) => char.imageUrl || charImageCount(char) > 0).length)
const voiceConfiguredCount = computed(() => characters.value.filter((char: any) => char.voiceName).length)
const imageTaskPolling = useTaskPolling({
  onTimeout: () => {
    generatingId.value = null
    ElMessage.warning('生成任务仍在后台执行，可稍后刷新页面查看结果')
  },
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
    imageTaskPolling.start(taskId, {
      onSuccess: async (task) => {
        generatingId.value = null
        ElMessage.success(String(task.message || '角色图像生成成功'))
        await load()
        if (galleryVisible.value && galleryChar.value?.id === char.id) {
          const updated = characters.value.find((c: any) => c.id === char.id)
          if (updated) {
            galleryChar.value = updated
            const urls = parseImageUrls(updated.imageUrls)
            galleryImages.value = urls.length > 0 ? urls : (updated.imageUrl ? [updated.imageUrl] : [])
          }
        }
      },
      onFailure: (task) => {
        generatingId.value = null
        ElMessage.error(String(task.message || '角色图像生成失败'))
      },
    })
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
.character-workbench {
  min-height: 100%;
  padding: 30px;
  background: var(--page-environment);
}

.character-hero,
.identity-layout,
.roster-strip,
.empty-panel {
  max-width: 1220px;
  margin: 0 auto;
}

.character-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 22px;
}

.character-hero p {
  margin: 0 0 8px;
  color: var(--primary);
  font-size: 14px;
  font-weight: 800;
}

.character-hero h1 {
  margin: 0;
  color: var(--text-primary);
  font-size: clamp(30px, 4vw, 46px);
  line-height: 1.08;
  letter-spacing: 0;
}

.character-hero span {
  display: block;
  margin-top: 10px;
  color: var(--text-secondary);
  font-size: 16px;
}

.identity-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 18px;
  margin-bottom: 18px;
}

.portrait-stage,
.lock-panel,
.char-card,
.empty-panel {
  border: 1px solid var(--border);
  background: var(--surface-panel);
  backdrop-filter: blur(var(--glass-blur)) saturate(145%);
  box-shadow: var(--shadow-md), inset 0 1px 0 rgba(255, 255, 255, 0.055);
}

.portrait-stage {
  display: grid;
  grid-template-columns: minmax(280px, 430px) minmax(0, 1fr);
  gap: 28px;
  min-height: 390px;
  padding: 18px;
  border-radius: 20px;
  cursor: pointer;
}

.portrait-frame {
  position: relative;
  min-height: 350px;
  overflow: hidden;
  border-radius: 18px;
  border: 1px solid var(--border);
  background:
    radial-gradient(circle at 24% 16%, rgba(91, 208, 255, 0.14), transparent 34%),
    radial-gradient(circle at 82% 78%, rgba(255, 208, 138, 0.11), transparent 38%),
    rgba(255, 255, 255, 0.045);
}

.portrait-frame img,
.char-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.portrait-placeholder,
.char-placeholder {
  height: 100%;
  display: grid;
  place-items: center;
  color: var(--text-muted);
}

.portrait-badge {
  position: absolute;
  left: 14px;
  bottom: 14px;
  padding: 6px 11px;
  border-radius: 999px;
  border: 1px solid rgba(255, 255, 255, 0.14);
  background: rgba(5, 9, 13, 0.54);
  color: #f7fbff;
  font-size: 14px;
  backdrop-filter: blur(24px);
}

.portrait-copy {
  align-self: end;
  padding: 0 14px 12px 0;
}

.portrait-copy p,
.lock-panel-head span,
.lock-row span {
  margin: 0;
  color: var(--text-muted);
  font-size: 14px;
}

.portrait-copy h2 {
  margin: 10px 0 12px;
  color: var(--text-primary);
  font-size: clamp(34px, 5vw, 58px);
  line-height: 1.04;
  letter-spacing: 0;
}

.portrait-copy span {
  color: var(--text-secondary);
  font-size: 16px;
  line-height: 1.7;
}

.lock-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 22px;
  border-radius: 20px;
}

.lock-panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: auto;
}

.lock-panel-head b {
  color: var(--primary);
  font-size: 30px;
}

.lock-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding-top: 14px;
  border-top: 1px solid var(--border);
}

.lock-row strong {
  color: var(--text-primary);
  font-size: 16px;
}

.roster-strip {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(250px, 1fr));
  gap: 16px;
}

.char-card {
  overflow: hidden;
  border-radius: 18px;
  cursor: pointer;
  transition: transform 0.18s, border-color 0.18s, background 0.18s;
}

.char-card:hover {
  transform: translateY(-1px);
  border-color: var(--border-strong);
  background: var(--surface-panel-strong);
}

.char-avatar {
  position: relative;
  height: 190px;
  overflow: hidden;
  background:
    radial-gradient(circle at 24% 18%, rgba(91, 208, 255, 0.13), transparent 36%),
    radial-gradient(circle at 80% 80%, rgba(180, 142, 255, 0.1), transparent 42%),
    rgba(255, 255, 255, 0.04);
}

.char-info { padding: 16px; }
.char-name { margin-bottom: 8px; color: var(--text-primary); font-size: 18px; font-weight: 800; }
.char-meta { display: flex; gap: 8px; margin-bottom: 10px; }
.char-meta span { color: var(--text-secondary); background: var(--bg-muted); padding: 4px 9px; border-radius: 999px; font-size: 14px; }
.char-desc { color: var(--text-secondary); margin-bottom: 10px; line-height: 1.55; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.char-voice { color: var(--primary); display: flex; align-items: center; gap: 6px; margin-bottom: 8px; flex-wrap: wrap; }
.char-rate { color: var(--text-muted); font-weight: 600; }
.char-tts-note { color: var(--text-muted); margin-bottom: 12px; line-height: 1.45; }
.char-audio { width: 100%; margin-bottom: 10px; }
.field-hint { margin-left: 8px; color: var(--text-muted); }
.char-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.voice-tip { margin-top: 8px; line-height: 1.5; color: var(--text-muted); }
.voice-option { display: flex; flex-direction: column; gap: 4px; padding: 2px 0; }
.voice-option-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.voice-option-name { font-weight: 700; color: var(--text-primary); }
.voice-option-id { color: var(--text-muted); }
.voice-option-desc { line-height: 1.4; color: var(--text-secondary); white-space: normal; }

.char-image-badge {
  position: absolute;
  bottom: 8px;
  right: 8px;
  border: 1px solid rgba(255, 255, 255, 0.14);
  background: rgba(5, 9, 13, 0.58);
  color: #fff;
  padding: 4px 9px;
  border-radius: 999px;
  backdrop-filter: blur(24px);
}

.empty-panel {
  min-height: 420px;
  display: grid;
  place-items: center;
  text-align: center;
  border-radius: 20px;
  padding: 34px;
}

.empty-panel h2 {
  margin: 0 0 10px;
  font-size: 28px;
}

.empty-panel p {
  margin: 0 0 20px;
  color: var(--text-secondary);
  font-size: 16px;
}

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
  background: linear-gradient(100deg, var(--primary), var(--secondary));
  color: #03101d;
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

@media (max-width: 980px) {
  .identity-layout,
  .portrait-stage {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 680px) {
  .character-workbench {
    padding: 20px;
  }

  .character-hero {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
