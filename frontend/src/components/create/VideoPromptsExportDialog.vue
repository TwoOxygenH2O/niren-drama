<template>
  <el-dialog
    v-model="visible"
    :title="dialogTitle"
    width="min(960px, 96vw)"
    align-center
    append-to-body
    destroy-on-close
    class="vp-export-dialog"
    @opened="onOpen"
  >
    <div v-loading="loading" class="vp-body">
      <p v-if="errorHint" class="vp-err">{{ errorHint }}</p>

      <section v-if="!errorHint && consistencyBlock" class="vp-block vp-block--global">
        <h4 class="vp-block-title">画风与项目一致性（建议在外站同一模型 / 风格下生成）</h4>
        <pre class="vp-pre">{{ consistencyBlock }}</pre>
        <button type="button" class="vp-copy" @click="copyText(consistencyBlock, '已复制一致性说明')">复制一致性说明</button>
      </section>

      <div v-if="shots.length" class="vp-shot-list">
        <section v-for="s in shots" :key="s.id" class="vp-block vp-block--shot">
          <div class="vp-shot-head">
            <span class="vp-shot-no">镜头 {{ s.shotNo }}</span>
            <span v-if="s.dynamicSelected" class="vp-tag">动态 · 文生视频</span>
            <span v-else class="vp-tag vp-tag--muted">静态 Key · 图生视频时可作首帧</span>
          </div>

          <div v-if="resolveImgUrl(s.imageUrl)" class="vp-ref-img-wrap">
            <span class="vp-label">参考图（分镜关键帧）</span>
            <div class="vp-ref-img-inner">
              <img class="vp-ref-img" :src="resolveImgUrl(s.imageUrl)!" alt="" />
            </div>
          </div>

          <div class="vp-field">
            <span class="vp-label">图片提示词</span>
            <pre class="vp-pre vp-pre--sm">{{ s.imagePrompt || '—' }}</pre>
          </div>

          <div class="vp-field">
            <span class="vp-label">视频提示词（外站文生视频建议整段粘贴）</span>
            <pre class="vp-pre">{{ fullVideoPromptForShot(s) }}</pre>
          </div>

          <div class="vp-field">
            <span class="vp-label">画面描述（补充语境）</span>
            <pre class="vp-pre vp-pre--sm">{{ s.description || '—' }}</pre>
          </div>

          <div class="vp-shot-actions">
            <button type="button" class="vp-copy" @click="copyShotVideoPrompt(s)">复制本镜 · 视频提示词</button>
            <button type="button" class="vp-copy" @click="copyShotPack(s)">复制本镜 · 全套文本</button>
          </div>
        </section>
      </div>

      <p v-if="!loading && !errorHint && !shots.length" class="vp-empty">暂无本集分镜数据，请先完成「拆解分镜」或稍后重试。</p>
    </div>

    <template #footer>
      <div class="vp-footer">
        <el-button @click="visible = false">关闭</el-button>
        <el-button type="primary" :disabled="!shots.length" @click="copyAll">复制全部（含一致性 + 各镜）</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { storyboardApi } from '@/api/storyboard'

const props = defineProps<{
  modelValue: boolean
  projectId: string | number
  episodeNo: number
  scriptId: number | null
  project: Record<string, unknown> | null
}>()

const emit = defineEmits<{
  'update:modelValue': [boolean]
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

const loading = ref(false)
const shots = ref<any[]>([])
const errorHint = ref('')

const dialogTitle = computed(
  () => `第 ${String(props.episodeNo).padStart(2, '0')} 集 · 外站视频提示词`,
)

const consistencyBlock = computed(() => {
  const p = props.project
  if (!p) return ''
  const name = String(p.name ?? '')
  const pt = String(p.projectType ?? '')
  const g = String(p.genre ?? '')
  const ci = String(p.commonInfo ?? '').trim()
  const ciShort = ci.length > 1200 ? ci.slice(0, 1200) + '\n…（项目圣经已截断，完整内容见项目详情）' : ci
  const lines = [
    `项目：《${name || '未命名'}》`,
    `类型：${pt || '—'}　题材：${g || '—'}`,
    '',
    '【视觉与叙事一致约束】',
    ciShort || '（暂无项目通用信息，建议在模型侧固定画风关键词与纵横比 9:16。）',
    '',
    '提示：在外站批量生成时，请固定同一模型、相似采样与纵横比；每镜单独粘贴下方「视频提示词」，必要时附加参考图。',
  ]
  return lines.join('\n')
})

function resolveImgUrl(url: unknown): string | null {
  if (!url || typeof url !== 'string') return null
  const u = url.trim()
  return u.length ? u : null
}

/** 与后端 enrich 思路一致：视频生成优先用专门 videoPrompt，否则回退描述 */
function fullVideoPromptForShot(s: any): string {
  const vp = s?.videoPrompt
  if (typeof vp === 'string' && vp.trim()) return vp.trim()
  const d = s?.description
  if (typeof d === 'string' && d.trim()) return d.trim()
  return '（暂无专用视频提示词，请在外站用画面描述 + 图片提示词自行组合）'
}

function shotPackText(s: any): string {
  const img = resolveImgUrl(s.imageUrl)
  const lines = [
    `—— 镜头 ${s.shotNo} ——`,
    img ? `参考图 URL：${img}` : '参考图：无',
    '',
    '图片提示词：',
    String(s.imagePrompt || '—'),
    '',
    '视频提示词：',
    fullVideoPromptForShot(s),
    '',
    '画面描述：',
    String(s.description || '—'),
  ]
  return lines.join('\n')
}

function buildAllText(): string {
  const parts: string[] = []
  if (consistencyBlock.value) {
    parts.push(consistencyBlock.value)
    parts.push('\n\n======== 各镜头 ========\n')
  }
  const sorted = [...shots.value].sort(
    (a, b) => (Number(a.shotNo) || 0) - (Number(b.shotNo) || 0),
  )
  sorted.forEach((s) => {
    parts.push(shotPackText(s))
    parts.push('\n')
  })
  return parts.join('\n').trim()
}

async function copyText(text: string, okMsg = '已复制到剪贴板') {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(okMsg)
  } catch {
    ElMessage.error('复制失败，请手动选择文本复制')
  }
}

function copyShotVideoPrompt(s: any) {
  copyText(fullVideoPromptForShot(s), '已复制本镜视频提示词')
}

function copyShotPack(s: any) {
  copyText(shotPackText(s), '已复制本镜全套')
}

function copyAll() {
  copyText(buildAllText(), '已复制全部提示词')
}

async function onOpen() {
  errorHint.value = ''
  shots.value = []
  if (props.scriptId == null) {
    errorHint.value = '当前集未关联剧本，无法加载分镜。'
    return
  }
  loading.value = true
  try {
    const res = await storyboardApi.listByScript(props.scriptId)
    const rows = (res as any).data?.data ?? []
    shots.value = Array.isArray(rows)
      ? [...rows].sort((a: any, b: any) => (Number(a.shotNo) || 0) - (Number(b.shotNo) || 0))
      : []
  } catch (e: unknown) {
    errorHint.value = e instanceof Error ? e.message : '加载分镜失败'
  } finally {
    loading.value = false
  }
}

</script>

<style>
.vp-export-dialog.el-dialog {
  --el-dialog-bg-color: rgba(18, 20, 26, 0.98);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 16px;
}
.vp-export-dialog .el-dialog__title {
  color: #f1f5f9;
  font-weight: 700;
}
.vp-export-dialog .el-dialog__body {
  padding-top: 8px;
}
</style>

<style scoped>
.vp-body {
  min-height: 120px;
  max-height: min(70vh, 720px);
  overflow-y: auto;
  color: #e8eaef;
}

.vp-err {
  color: #fda4af;
  font-size: 13px;
  margin: 0 0 12px;
}

.vp-empty {
  color: rgba(148, 163, 184, 0.95);
  font-size: 13px;
  margin: 16px 0;
}

.vp-block {
  margin-bottom: 16px;
  padding: 14px;
  border-radius: 12px;
  border: 1px solid rgba(255, 255, 255, 0.06);
  background: rgba(0, 0, 0, 0.35);
}

.vp-block--global {
  border-color: rgba(129, 140, 248, 0.25);
  background: rgba(79, 70, 229, 0.08);
}

.vp-block-title {
  margin: 0 0 10px;
  font-size: 13px;
  font-weight: 700;
  color: #c7d2fe;
}

.vp-block--shot {
  background: rgba(15, 17, 22, 0.96);
}

.vp-shot-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.vp-shot-no {
  font-size: 15px;
  font-weight: 800;
  color: #f8fafc;
}

.vp-tag {
  font-size: 11px;
  padding: 3px 10px;
  border-radius: 999px;
  background: rgba(52, 211, 153, 0.15);
  color: #6ee7b7;
  border: 1px solid rgba(52, 211, 153, 0.35);
}

.vp-tag--muted {
  background: rgba(148, 163, 184, 0.12);
  color: rgba(226, 232, 240, 0.85);
  border-color: rgba(148, 163, 184, 0.25);
}

.vp-field {
  margin-bottom: 12px;
}

.vp-label {
  display: block;
  font-size: 11px;
  color: rgba(148, 163, 184, 0.95);
  margin-bottom: 6px;
}

.vp-ref-img-wrap {
  margin-bottom: 12px;
}

.vp-ref-img-inner {
  margin-top: 6px;
  border-radius: 10px;
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.08);
  max-width: 280px;
  background: #000;
}

.vp-ref-img {
  display: block;
  width: 100%;
  height: auto;
  max-height: 200px;
  object-fit: contain;
}

.vp-pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 12px;
  line-height: 1.55;
  color: rgba(226, 232, 240, 0.95);
  font-family: ui-monospace, 'Cascadia Mono', 'Segoe UI Mono', monospace;
  background: rgba(0, 0, 0, 0.4);
  padding: 10px 12px;
  border-radius: 8px;
  border: 1px solid rgba(255, 255, 255, 0.06);
}

.vp-pre--sm {
  font-size: 11px;
  color: rgba(203, 213, 225, 0.88);
}

.vp-shot-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

.vp-copy {
  border: none;
  cursor: pointer;
  padding: 8px 12px;
  border-radius: 10px;
  font-size: 12px;
  font-weight: 600;
  color: #e0e7ff;
  background: rgba(99, 102, 241, 0.25);
  border: 1px solid rgba(129, 140, 248, 0.35);
}
.vp-copy:hover {
  background: rgba(99, 102, 241, 0.4);
}

.vp-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  flex-wrap: wrap;
}
</style>
