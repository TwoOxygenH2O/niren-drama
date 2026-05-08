<template>
  <div class="space-projects-root">
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

    <div class="space-toolbar">
      <el-input
        v-model="keyword"
        class="space-search"
        clearable
        placeholder="搜索项目"
      />
      <el-button type="primary" :icon="Plus" class="space-new" @click="showCreate = true">新建项目</el-button>
    </div>

    <div v-loading="loading" class="space-cards-wrap">
      <div v-if="!displayProjects.length && !loading" class="space-empty">
        {{
          projects.length
            ? '当前筛选条件下暂无项目，试试其它分类或关键词'
            : '暂无项目，点击「新建项目」开始创作'
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
              <span v-if="!coverUrl(row)" class="proj-card-cover-ph" aria-hidden="true">{{
                titleInitial(row.name)
              }}</span>
            </div>
          </div>
          <div class="proj-card-body">
            <h2 class="proj-card-title">{{ row.name }}</h2>
            <div class="proj-card-footer">
              <span class="proj-card-date">{{ formatDate(row.createTime) }}</span>
              <span class="proj-card-badge">{{ modeBadge() }}</span>
            </div>
          </div>
          <el-popconfirm title="确认删除该项目？" @confirm.stop="handleDelete(row.id)">
            <template #reference>
              <button type="button" class="proj-card-del" title="删除" @click.stop>×</button>
            </template>
          </el-popconfirm>
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
  DEFAULT_PROJECT_TYPE,
  GENRE_OPTIONS,
  PROJECT_TYPE_OPTIONS,
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
  genre: '',
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
      return t.includes('真人') || t.includes('短剧') || /都市|古装|情感/.test(g)
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
    genre: '',
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
  background: #0a0a0c;
  color: #e8eaef;
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
  border-radius: 999px;
  border: 1px solid transparent;
  background: rgba(255, 255, 255, 0.06);
  color: rgba(226, 232, 240, 0.85);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.18s, color 0.18s, border-color 0.18s;
}
.space-tab:hover {
  background: rgba(255, 255, 255, 0.1);
  color: #fff;
}
.space-tab--active {
  background: rgba(99, 102, 241, 0.22);
  border-color: rgba(129, 140, 248, 0.45);
  color: #e0e7ff;
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
  background: rgba(255, 255, 255, 0.06);
  box-shadow: none;
  border-radius: 10px;
}
.space-search :deep(.el-input__inner) {
  color: #f1f5f9;
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
  color: rgba(148, 163, 184, 0.95);
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
  border-radius: 16px;
  border: 1px solid rgba(255, 255, 255, 0.06);
  background: rgba(22, 24, 30, 0.98);
  box-shadow: 0 8px 28px rgba(0, 0, 0, 0.35);
  cursor: pointer;
  user-select: none;
  transition: border-color 0.2s, box-shadow 0.2s, transform 0.2s;
  text-align: left;
}
.proj-card:hover {
  border-color: rgba(129, 140, 248, 0.35);
  box-shadow: 0 18px 48px rgba(0, 0, 0, 0.45);
  transform: translateY(-2px);
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
  color: #f8fafc;
  margin: 0;
  letter-spacing: 0.02em;
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
  color: rgba(148, 163, 184, 0.9);
}

.proj-card-badge {
  padding: 4px 10px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.08);
  color: rgba(226, 232, 240, 0.85);
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
  border-radius: 8px;
  background: rgba(0, 0, 0, 0.45);
  color: rgba(248, 250, 252, 0.85);
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
  background: rgba(239, 68, 68, 0.35);
  color: #fff;
}

.space-pager-bar {
  flex-shrink: 0;
  padding-top: 16px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
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
  color: rgba(203, 213, 225, 0.92);
}
.space-pager :deep(.btn-prev),
.space-pager :deep(.btn-next),
.space-pager :deep(.number) {
  background: rgba(255, 255, 255, 0.06);
}
.space-pager :deep(.el-pagination.is-background .el-pager li) {
  background: rgba(255, 255, 255, 0.06);
}
.space-pager :deep(.el-pagination.is-background .el-pager li.is-active) {
  background: rgba(99, 102, 241, 0.45);
}
</style>
