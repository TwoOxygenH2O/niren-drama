<template>
  <div class="space-projects-root">
    <header class="projects-hero">
      <div>
        <p class="projects-kicker">项目与剧集管理中心</p>
        <h1>项目库</h1>
        <span>管理、追踪和进入所有竖屏短剧项目</span>
      </div>
      <div class="projects-hero-actions">
        <el-input v-model="keyword" class="space-search" clearable placeholder="搜索项目..." />
        <button type="button" class="ghost-filter">全部题材</button>
        <button type="button" class="ghost-filter">最近更新</button>
        <el-button type="primary" :icon="Plus" class="space-new" @click="showCreate = true">新建项目</el-button>
      </div>
    </header>

    <nav class="space-tabs" aria-label="内容分类">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        type="button"
        class="space-tab"
        :class="{ 'space-tab--active': activeTab === tab.key }"
        @click="activeTab = tab.key"
      >
        {{ tab.label }}
      </button>
    </nav>

    <div v-loading="loading" class="space-cards-wrap">
      <div v-if="!displayProjects.length && !loading" class="space-empty">
        {{
          projects.length
            ? '没有匹配项目，调整分类或关键词'
            : '项目库为空，创建第一部短剧'
        }}
      </div>
      <div class="space-cards">
        <article
          v-for="row in displayProjects"
          :key="row.id"
          class="proj-card"
          role="button"
          tabindex="0"
          @click="goEpisodes(row.id)"
          @keydown.enter.prevent="goEpisodes(row.id)"
        >
          <div class="proj-card-visual-wrap">
            <div class="proj-card-cover" :style="coverLayerStyle(row)">
              <span v-if="!coverUrl(row)" class="proj-card-cover-ph" aria-hidden="true">{{ titleInitial(row.name) }}</span>
              <span class="poster-title">{{ row.name }}</span>
            </div>
            <el-popconfirm title="确认删除该项目？" @confirm.stop="handleDelete(row.id)">
              <template #reference>
                <button type="button" class="proj-card-del" title="删除" aria-label="删除项目" @click.stop>···</button>
              </template>
            </el-popconfirm>
          </div>
          <div class="proj-card-body">
            <div class="tag-row">
              <span>{{ formatGenreLabel(row.genre) }}</span>
              <span>{{ formatProjectTypeLabel(row.projectType) }}</span>
              <span>{{ modeBadge() }}</span>
            </div>
            <h2 class="proj-card-title">{{ row.name }}</h2>
            <p class="proj-card-episode">剧集 {{ row.currentEpisode || row.episodeNo || 0 }}/{{ row.episodes || 20 }}</p>
            <div class="project-progress"><span :style="{ width: `${Math.min(100, Math.max(10, ((Number(row.currentEpisode || row.episodeNo || 0) || 0) / Number(row.episodes || 20)) * 100))}%` }" /></div>
            <div class="proj-card-footer">
              <span class="proj-card-date">{{ formatDate(row.createTime) }}</span>
            </div>
          </div>
        </article>
      </div>
    </div>

    <div v-if="total > 0" class="space-pager-bar">
      <el-pagination
        class="space-pager"
        background
        :hide-on-single-page="false"
        :total="total"
        :page-size="pageSize"
        :current-page="page"
        :page-sizes="[12, 24, 48]"
        layout="total, sizes, prev, pager, next, jumper"
        @current-change="onPageChange"
        @size-change="onPageSizeChange"
      />
    </div>

    <el-dialog v-model="showCreate" title="新建项目" width="500px" append-to-body @close="resetForm">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="项目名称" prop="name">
          <el-input v-model="form.name" placeholder="如：都市爱情短剧第一季" />
        </el-form-item>
        <el-form-item label="项目类型" prop="projectType">
          <el-select v-model="form.projectType" placeholder="选择项目类型" style="width: 100%">
            <el-option v-for="option in PROJECT_TYPE_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="题材风格" prop="genre">
          <el-select v-model="form.genre" placeholder="选择题材" style="width: 100%">
            <el-option v-for="option in GENRE_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="剧集数量" prop="episodes">
          <el-input-number v-model="form.episodes" :min="1" :max="100" style="width: 100%" />
        </el-form-item>
        <el-form-item label="单集时长(秒)" prop="episodeDuration">
          <el-input-number v-model="form.episodeDuration" :min="60" :max="600" :step="30" style="width: 100%" />
        </el-form-item>
        <el-form-item label="项目描述">
          <el-input v-model="form.description" type="textarea" :rows="3" placeholder="可选，简要描述项目" />
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
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { projectApi } from '@/api/project'
import {
  DEFAULT_GENRE,
  DEFAULT_PROJECT_TYPE,
  GENRE_OPTIONS,
  PROJECT_TYPE_OPTIONS,
  formatGenreLabel,
  formatProjectTypeLabel,
} from '@/constants/project'

const router = useRouter()

const tabs = [
  { key: 'all', label: '全部' },
  { key: 'story', label: '故事' },
  { key: 'digital', label: '数字人' },
  { key: 'drama', label: '短剧漫剧' },
  { key: 'mv', label: '音乐MV' },
  { key: 'knowledge', label: '知识分享' },
] as const

type TabKey = (typeof tabs)[number]['key']

const activeTab = ref<TabKey>('all')
const keyword = ref('')
const projects = ref<any[]>([])
const loading = ref(false)
const showCreate = ref(false)
const submitting = ref(false)
const formRef = ref()
const page = ref(1)
const pageSize = ref(12)
const total = ref(0)

let keywordDebounce: ReturnType<typeof setTimeout> | undefined

const form = ref({
  name: '',
  projectType: DEFAULT_PROJECT_TYPE,
  genre: DEFAULT_GENRE,
  episodes: 20,
  episodeDuration: 60,
  description: '',
})
const rules = {
  name: [{ required: true, message: '请输入项目名称', trigger: 'blur' }],
  projectType: [{ required: true, message: '请选择项目类型', trigger: 'change' }],
  episodes: [{ required: true, message: '请填写集数', trigger: 'blur' }],
  episodeDuration: [{ required: true, message: '请填写时长', trigger: 'blur' }],
}

function formatDate(raw: unknown) {
  if (raw == null) return '—'
  const d = new Date(String(raw))
  if (Number.isNaN(d.getTime())) return '—'
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return `${y}/${m}/${day} ${hh}:${mm}`
}

function modeBadge() {
  return '全能模式'
}

function coverUrl(row: any): string {
  const u = row?.coverImage ?? row?.cover_image
  return typeof u === 'string' && u.trim() ? u.trim() : ''
}

/** 无封面时按项目 id 生成稳定渐变，接近参考站卡片底图 */
function gradientForProject(id: number) {
  const hues = [262, 215, 190, 330, 175, 205, 45, 280]
  const h = hues[Math.abs(Number(id)) % hues.length]
  return `linear-gradient(to bottom, hsla(${h}, 42%, 42%, 0.35), hsla(${h}, 38%, 14%, 0.92))`
}

function coverLayerStyle(row: any) {
  const url = coverUrl(row)
  const base = {
    backgroundSize: 'cover',
    backgroundPosition: 'center',
  } as Record<string, string>
  if (url) {
    base.backgroundImage = `linear-gradient(to bottom, rgba(255,255,255,0.06), rgba(0,0,0,0.42)), url("${url.replace(/"/g, '\\"')}")`
    return base
  }
  base.backgroundImage = `${gradientForProject(Number(row.id))}, radial-gradient(ellipse 80% 60% at 50% 20%, rgba(255,255,255,0.12), transparent 55%)`
  return base
}

function titleInitial(name: unknown) {
  const s = String(name || '?').trim()
  return s ? s.charAt(0) : '?'
}

function tabMatch(row: any, tab: TabKey): boolean {
  if (tab === 'all') return true
  const t = String(row.projectType || '')
  const g = String(row.genre || '')
  switch (tab) {
    case 'story':
      return t.includes('真人') || t.includes('短剧') || /都市|古装|复仇|情感/.test(g)
    case 'digital':
      return t.includes('数字') || g.includes('数字人')
    case 'drama':
      return t.includes('漫画') || t.includes('漫剧')
    case 'mv':
      return t.includes('MV') || g.includes('音乐')
    case 'knowledge':
      return g.includes('知识') || t.includes('知识')
    default:
      return true
  }
}

/** 服务端已按关键词筛名称；此处仅做分类 Tab 客户端过滤 */
const displayProjects = computed(() =>
  projects.value.filter((row) => tabMatch(row, activeTab.value)),
)

function goEpisodes(id: number) {
  router.push(`/projects/${id}/episodes`)
}

async function load() {
  loading.value = true
  try {
    const res = await projectApi.list({
      page: page.value,
      size: pageSize.value,
      keyword: keyword.value.trim() || undefined,
    })
    const data = res.data?.data ?? res.data
    projects.value = data?.records || []
    total.value = Number(data?.total) || 0
  } finally {
    loading.value = false
  }
}

function onPageChange(p: number) {
  page.value = p
  load()
}

function onPageSizeChange(size: number) {
  pageSize.value = size
  page.value = 1
  load()
}

watch(activeTab, () => {
  page.value = 1
  load()
})

watch(keyword, () => {
  if (keywordDebounce) clearTimeout(keywordDebounce)
  keywordDebounce = setTimeout(() => {
    keywordDebounce = undefined
    page.value = 1
    load()
  }, 380)
})

async function handleCreate() {
  await formRef.value.validate()
  submitting.value = true
  try {
    await projectApi.create(form.value)
    ElMessage.success('项目创建成功')
    showCreate.value = false
    load()
  } finally {
    submitting.value = false
  }
}

async function handleDelete(id: number) {
  await projectApi.delete(id)
  ElMessage.success('删除成功')
  load()
}

function resetForm() {
  form.value = {
    name: '',
    projectType: DEFAULT_PROJECT_TYPE,
    genre: DEFAULT_GENRE,
    episodes: 20,
    episodeDuration: 60,
    description: '',
  }
  formRef.value?.clearValidate()
}

onMounted(load)

onUnmounted(() => {
  if (keywordDebounce) clearTimeout(keywordDebounce)
})
</script>

<style scoped>
/* 占满 MainLayout 主区域；中间卡片网格滚动，底部分页条常驻 */
.space-projects-root {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  gap: 8px;
  padding: 28px 40px 24px 112px;
  width: 100%;
  box-sizing: border-box;
  background: var(--page-environment);
  color: var(--text-primary);
}

.space-tabs {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
  margin-bottom: 8px;
}

.space-tab {
  padding: 8px 16px;
  border-radius: var(--radius-full);
  border: 1px solid transparent;
  background: var(--bg-muted);
  color: var(--text-secondary);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.18s, color 0.18s, border-color 0.18s;
}
.space-tab:hover {
  background: var(--bg-card-hover);
  color: var(--text-primary);
}
.space-tab--active {
  background: var(--primary-glow);
  border-color: var(--primary-light);
  color: var(--primary);
}

.space-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.space-search {
  max-width: 320px;
  flex: 1;
}

.space-search :deep(.el-input__wrapper) {
  background: var(--bg-muted);
  box-shadow: none;
  border-radius: var(--radius-md);
}
.space-search :deep(.el-input__inner) {
  color: var(--text-primary);
}

.space-cards-wrap {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
}

.space-empty {
  padding: 48px 16px;
  text-align: center;
  color: var(--text-muted);
  font-size: 14px;
}

/* 参考：grid-cols-3 → xl:5 → 2xl:6 → 3xl:7 → 4xl:8 */
.space-cards {
  display: grid;
  gap: 16px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

@media (min-width: 520px) {
  .space-cards {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (min-width: 1024px) {
  .space-cards {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
}

@media (min-width: 1280px) {
  .space-cards {
    grid-template-columns: repeat(5, minmax(0, 1fr));
  }
}

@media (min-width: 1536px) {
  .space-cards {
    grid-template-columns: repeat(6, minmax(0, 1fr));
  }
}

@media (min-width: 1920px) {
  .space-cards {
    grid-template-columns: repeat(7, minmax(0, 1fr));
  }
}

@media (min-width: 2560px) {
  .space-cards {
    grid-template-columns: repeat(8, minmax(0, 1fr));
  }
}

.proj-card {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 4px;
  height: 320px;
  border-radius: var(--radius-lg);
  border: 1px solid var(--border);
  background: var(--bg-card);
  box-shadow: var(--shadow-sm);
  cursor: pointer;
  user-select: none;
  transition: border-color 0.2s, box-shadow 0.2s, transform 0.2s;
  text-align: left;
}
.proj-card:hover {
  border-color: var(--primary-light);
  box-shadow: var(--shadow-lg);
  transform: translateY(-1px);
}

.proj-card-visual-wrap {
  flex: 1;
  min-height: 0;
  border-radius: 12px;
  overflow: hidden;
  background: linear-gradient(to bottom, rgba(255, 255, 255, 0.06), rgba(255, 255, 255, 0));
}

.proj-card-cover {
  width: 100%;
  height: 100%;
  min-height: 200px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.proj-card-cover-ph {
  font-size: 52px;
  font-weight: 800;
  color: rgba(248, 250, 252, 0.22);
  text-transform: uppercase;
  pointer-events: none;
}

.proj-card-body {
  flex-shrink: 0;
  padding: 8px 8px 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.proj-card-title {
  font-size: 14px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0;
  letter-spacing: -0.02em;
  line-height: 1.35;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.proj-card-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  font-size: 12px;
}

.proj-card-date {
  color: var(--text-muted);
}

.proj-card-badge {
  padding: 4px 10px;
  border-radius: var(--radius-full);
  background: var(--bg-muted);
  color: var(--text-secondary);
  font-weight: 600;
}

.proj-card-del {
  position: absolute;
  top: 10px;
  right: 10px;
  z-index: 3;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: var(--radius-sm);
  background: rgba(0, 0, 0, 0.45);
  color: var(--text-primary);
  font-size: 18px;
  line-height: 1;
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.15s, background 0.15s;
}
.proj-card:hover .proj-card-del {
  opacity: 1;
}
.proj-card-del:hover {
  background: var(--color-danger);
  color: #fff;
}

.space-pager-bar {
  flex-shrink: 0;
  padding-top: 16px;
  border-top: 1px solid var(--border);
  display: flex;
  justify-content: center;
  flex-wrap: wrap;
  gap: 8px;
}

.space-pager {
  justify-content: center;
}
.space-pager :deep(.el-pagination__total),
.space-pager :deep(.el-pagination__jump),
.space-pager :deep(.el-pagination__sizes .el-select .el-input__wrapper) {
  color: var(--text-secondary);
}
.space-pager :deep(.btn-prev),
.space-pager :deep(.btn-next),
.space-pager :deep(.number) {
  background: var(--bg-muted);
}
.space-pager :deep(.el-pagination.is-background .el-pager li) {
  background: var(--bg-muted);
}
.space-pager :deep(.el-pagination.is-background .el-pager li.is-active) {
  background: var(--primary);
}
.space-projects-root {
  min-height: 100%;
  padding: 28px 30px 48px;
  background: var(--page-environment);
  color: #f7fbff;
  overflow: auto;
}

.projects-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding-bottom: 28px;
  border-bottom: 1px solid rgba(150, 190, 255, 0.13);
}

.projects-kicker {
  margin: 0 0 8px;
  color: var(--primary);
  font-size: 13px;
  font-weight: 800;
}

.projects-hero h1 {
  margin: 0;
  font-size: 31px;
  letter-spacing: 0;
}

.projects-hero span {
  color: #9aa8bd;
}

.projects-hero-actions {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.space-search {
  width: 300px;
}

.ghost-filter {
  height: 42px;
  padding: 0 18px;
  border: 1px solid rgba(150, 190, 255, 0.16);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.045);
  color: #dbe8ff;
}

.space-new {
  height: 44px;
  border-radius: 8px;
  background: linear-gradient(100deg, #f7fbff, var(--primary), var(--secondary)) !important;
  color: #03101d;
  box-shadow: var(--shadow-primary);
}

.space-tabs {
  margin: 28px 0 22px;
  display: flex;
  gap: 30px;
  border-bottom: 1px solid rgba(150, 190, 255, 0.11);
}

.space-tab {
  position: relative;
  height: 44px;
  border: 0;
  background: transparent;
  color: #aab5c8;
  font-size: 15px;
  font-weight: 650;
}

.space-tab--active {
  color: var(--primary);
}

.space-tab--active::after {
  content: "";
  position: absolute;
  left: 0;
  right: 0;
  bottom: -1px;
  height: 2px;
  background: linear-gradient(90deg, var(--secondary), var(--primary));
  box-shadow: 0 0 18px rgba(103, 232, 249, 0.45);
}

.space-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(230px, 1fr));
  gap: 18px;
}

.proj-card {
  position: relative;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--surface-panel);
  box-shadow: var(--shadow-md);
  transition: transform 0.18s, border-color 0.18s, box-shadow 0.18s;
}

.proj-card:hover {
  transform: translateY(-1px);
  border-color: rgba(103, 232, 249, 0.26);
  box-shadow: var(--shadow-lg);
}

.proj-card-visual-wrap {
  position: relative;
  padding: 12px 12px 0;
}

.proj-card-cover {
  position: relative;
  height: auto;
  aspect-ratio: 3 / 4;
  border-radius: 8px;
  overflow: hidden;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  padding: 22px 16px;
  background-color: #0b1220;
  box-shadow: inset 0 -80px 90px rgba(0, 0, 0, 0.48);
}

.proj-card-cover::after {
  content: "";
  position: absolute;
  inset: 0;
  background:
    linear-gradient(180deg, transparent 34%, rgba(0, 0, 0, 0.54)),
    radial-gradient(circle at 50% 8%, rgba(255, 255, 255, 0.16), transparent 24%);
}

.poster-title {
  position: relative;
  z-index: 1;
  max-width: 100%;
  color: #fff;
  text-align: center;
  font-family: var(--font-sans);
  font-size: 24px;
  line-height: 1.05;
  text-transform: uppercase;
  text-shadow: 0 3px 16px rgba(0, 0, 0, 0.82);
  word-break: break-word;
}

.proj-card-cover-ph {
  position: absolute;
  inset: 18% auto auto 50%;
  transform: translateX(-50%);
  z-index: 1;
  width: 80px;
  height: 80px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  color: rgba(255, 255, 255, 0.62);
  font-size: 46px;
  font-weight: 900;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.12);
}

.proj-card-del {
  position: absolute;
  top: 18px;
  right: 18px;
  z-index: 2;
  width: 32px;
  height: 28px;
  border: 0;
  border-radius: 8px;
  background: rgba(3, 6, 11, 0.45);
  color: #dbe8ff;
  font-weight: 900;
}

.proj-card-body {
  padding: 14px 18px 18px;
}

.tag-row {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  margin-bottom: 12px;
}

.tag-row span {
  padding: 3px 8px;
  border-radius: 6px;
  border: 1px solid rgba(139, 92, 246, 0.28);
  background: rgba(139, 92, 246, 0.16);
  color: #c4b5fd;
  font-size: 12px;
}

.tag-row span:nth-child(2) {
  color: #93c5fd;
  border-color: rgba(59, 130, 246, 0.28);
  background: rgba(59, 130, 246, 0.14);
}

.tag-row span:nth-child(3) {
  color: #67e8f9;
  border-color: rgba(24, 216, 255, 0.28);
  background: rgba(24, 216, 255, 0.12);
}

.proj-card-title {
  margin: 0;
  color: #f7fbff;
  font-size: 16px;
  line-height: 1.3;
}

.proj-card-episode {
  margin: 12px 0 8px;
  color: #aab5c8;
  font-size: 13px;
}

.project-progress {
  height: 5px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.08);
  overflow: hidden;
}

.project-progress span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, var(--secondary), var(--primary));
}

.proj-card-footer {
  margin-top: 12px;
  color: #74839a;
  font-size: 12px;
}

.space-empty {
  min-height: 280px;
  display: grid;
  place-items: center;
  border: 1px dashed rgba(150, 190, 255, 0.2);
  border-radius: 8px;
  color: #9aa8bd;
}

.space-pager-bar {
  margin-top: 24px;
  display: flex;
  justify-content: center;
}

@media (max-width: 980px) {
  .projects-hero {
    align-items: flex-start;
    flex-direction: column;
  }

  .projects-hero-actions,
  .space-search {
    width: 100%;
  }
}
</style>
