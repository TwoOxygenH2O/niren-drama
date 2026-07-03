<template>
  <div
    class="dashboard-immersive"
    v-loading="creatingProject || dashboardLoading"
    :element-loading-text="creatingProject ? '正在创建项目…' : '正在加载真实工作台数据…'"
  >
    <div class="dashboard-bg" aria-hidden="true" />
    <div class="dashboard-inner">
      <header class="dashboard-topbar">
        <div>
          <p class="dashboard-eyebrow">泥人剧场 · 创作中枢</p>
          <h1>用户工作台大盘</h1>
        </div>
        <div class="dashboard-actions">
          <span class="kbd-pill">⌘ K</span>
          <span v-if="dashboardBadge > 0" class="notify-dot">{{ dashboardBadge }}</span>
          <div class="mini-avatar">{{ (userStore.userInfo?.nickname || 'ND').slice(0, 2).toUpperCase() }}</div>
        </div>
      </header>

      <section class="inspiration-panel">
        <div class="panel-orbit" aria-hidden="true" />
        <div class="inspiration-head">
          <div class="spark-icon">✧</div>
          <h2>灵感输入栏</h2>
          <label class="auto-enhance">
            <span>自动增强</span>
            <input type="checkbox" checked />
          </label>
        </div>
        <textarea
          v-model="inspiration"
          class="inspiration-input"
          placeholder="描述你的下一场短剧戏，例如：雨夜天台，女主发现男主隐藏身份，镜头缓慢推进..."
          maxlength="500"
          rows="3"
          @keydown.ctrl.enter.prevent="goFromInspiration"
        />
        <div class="inspiration-footer">
          <div class="prompt-tools">
            <button type="button">上传素材</button>
            <button type="button">情绪板</button>
            <button type="button">参考图</button>
          </div>
          <div class="generate-pack">
            <span>{{ inspiration.length }} / 500</span>
            <button type="button" class="generate-btn" @click="goFromInspiration">生成 ✧</button>
          </div>
        </div>
        <div class="creation-controls" aria-label="创作设置">
          <label class="creation-field">
            <span>题材</span>
            <el-select v-model="selectedGenre" size="small" popper-class="dashboard-genre-dropdown">
              <el-option v-for="item in GENRE_OPTIONS" :key="item.value" :value="item.value" :label="item.label" />
            </el-select>
          </label>
          <div class="creation-field">
            <span>平台</span>
            <div class="creation-segment" aria-label="目标平台">
              <button
                v-for="item in PLATFORM_PROFILE_OPTIONS"
                :key="item.value"
                type="button"
                :class="{ active: platformProfile === item.value }"
                @click="setPlatformProfile(item.value)"
              >
                {{ item.label }}
              </button>
            </div>
          </div>
          <div class="creation-field">
            <span>质量</span>
            <div class="creation-segment" aria-label="生成意图">
              <button
                v-for="item in PRODUCTION_MODE_OPTIONS"
                :key="item.value"
                type="button"
                :class="{ active: productionMode === item.value }"
                @click="setProductionMode(item.value)"
              >
                {{ item.label }}
              </button>
            </div>
          </div>
          <div class="creation-field">
            <span>模型</span>
            <b>{{ videoModelLabel }}</b>
          </div>
          <div class="creation-field">
            <span>集数</span>
            <el-select v-model="episodeCount" size="small" style="width: 84px" popper-class="episode-count-dropdown">
              <el-option v-for="n in 40" :key="n" :value="n" :label="`${n}集`" />
            </el-select>
          </div>
        </div>
      </section>

      <section class="metric-grid" aria-label="生产指标">
        <article
          v-for="metric in metricCards"
          :key="metric.id"
          class="metric-card"
          :class="{
            'metric-card--violet': metric.tone === 'violet',
            'metric-card--review': metric.tone === 'review',
          }"
        >
          <div class="metric-top">
            <span class="metric-icon">{{ metricIcon(metric.id) }}</span>
            <b>{{ metric.title }}</b>
            <em>{{ metric.status }}</em>
          </div>
          <div class="metric-value">
            <strong>{{ metric.value }}</strong>
            <span v-if="metric.total">/ {{ metric.total }}</span>
          </div>
          <p>{{ metric.description }}</p>
          <div class="metric-viz">
            <div class="metric-track"><span :style="{ width: `${metric.progress}%` }" /></div>
            <div
              v-if="sparks[metric.id]"
              class="metric-spark"
              :class="sparks[metric.id].trend < 0 ? 'is-down' : 'is-up'"
            >
              <svg viewBox="0 0 116 34" preserveAspectRatio="none" aria-hidden="true">
                <path class="spark-area" :d="sparks[metric.id].area" />
                <path class="spark-line" :d="sparks[metric.id].line" />
                <circle class="spark-dot" :cx="sparks[metric.id].lastX" :cy="sparks[metric.id].lastY" r="2.2" />
              </svg>
            </div>
            <div v-else class="metric-spark metric-spark--empty" aria-hidden="true">
              <span>趋势累积中</span>
            </div>
          </div>
          <footer><span>{{ metric.footerLabel }}</span><b>{{ metric.footerValue }}</b></footer>
        </article>
        <article v-if="!dashboardLoading && metricCards.length === 0" class="metric-card metric-card--empty">
          <div class="metric-top"><span class="metric-icon">◎</span><b>暂无生产数据</b><em>空</em></div>
          <div class="metric-value"><strong>0</strong></div>
          <p>创建项目后显示剧集、镜头和生成任务。</p>
          <div class="metric-track"><span style="width: 0%" /></div>
          <footer><span>数据源</span><b>真实接口</b></footer>
        </article>
      </section>

      <section class="recent-panel">
        <div class="recent-head">
          <h2>最近生成</h2>
          <button type="button" @click="goToRecentTarget">查看全部</button>
        </div>
        <div class="recent-table">
          <div class="recent-row recent-row--head"><span>场景</span><span>项目</span><span>状态</span><span>进度</span><span>详情</span></div>
          <div v-for="row in recentRows" :key="`${row.project}-${row.scene}-${row.status}`" class="recent-row">
            <span>{{ row.scene }}</span>
            <span>{{ row.project }}</span>
            <span :class="row.statusTone">{{ row.status }}</span>
            <span>{{ row.progress }}</span>
            <span>{{ row.detail }}</span>
          </div>
          <div v-if="!dashboardLoading && recentRows.length === 0" class="recent-row recent-row--empty">
            <span>暂无镜头</span>
            <span>暂无项目</span>
            <span class="violet">无任务</span>
            <span>0%</span>
            <span>等待首个镜头</span>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { aiConfigApi } from '@/api/aiConfig'
import { dashboardApi, type DashboardMetric, type DashboardOverview, type DashboardRecentRow } from '@/api/dashboard'
import { projectApi } from '@/api/project'
import {
  DEFAULT_GENRE,
  DEFAULT_PLATFORM_PROFILE,
  DEFAULT_PRODUCTION_MODE,
  DEFAULT_PROJECT_TYPE,
  GENRE_OPTIONS,
  PLATFORM_PROFILE_OPTIONS,
  PRODUCTION_MODE_OPTIONS,
} from '@/constants/project'

type PlatformProfile = 'douyin' | 'hongguo'
type ProductionMode = 'preview' | 'publish'

const router = useRouter()
const userStore = useUserStore()
const inspiration = ref('')
const episodeCount = ref(20)
const selectedGenre = ref(DEFAULT_GENRE)
const platformProfile = ref<PlatformProfile>(DEFAULT_PLATFORM_PROFILE as PlatformProfile)
const productionMode = ref<ProductionMode>(DEFAULT_PRODUCTION_MODE as ProductionMode)
const videoModelName = ref('')
const creatingProject = ref(false)
const dashboardLoading = ref(false)
const dashboardOverview = ref<DashboardOverview | null>(null)

const INSPIRATION_KEY = 'niren.dashboard.inspiration'
const EPISODE_COUNT_KEY = 'niren.dashboard.episodeCount'
const GENRE_KEY = 'niren.dashboard.genre'
const PLATFORM_PROFILE_KEY = 'niren.dashboard.platformProfile'
const PRODUCTION_MODE_KEY = 'niren.dashboard.productionMode'

/** 与模型配置中心「文生视频」默认项的模型名称一致 */
const videoModelLabel = computed(() => {
  const m = videoModelName.value.trim()
  if (m) return `${m} 全能模式`
  return '请在模型配置中添加文生视频模型'
})

const metricCards = computed<DashboardMetric[]>(() => dashboardOverview.value?.metrics || [])

/* 指标趋势迷你图：记录每次真实加载时观测到的数值，形成本地滚动历史，
   据此画 sparkline；数据是真实累积的，不做任何虚构。 */
const METRIC_HISTORY_KEY = 'niren.dashboard.metricHistory'
const SPARK_W = 116
const SPARK_H = 34
const metricHistory = ref<Record<string, number[]>>({})

function loadMetricHistory() {
  try {
    const raw = localStorage.getItem(METRIC_HISTORY_KEY)
    const parsed = raw ? JSON.parse(raw) : {}
    metricHistory.value = parsed && typeof parsed === 'object' ? parsed : {}
  } catch {
    metricHistory.value = {}
  }
}

function numericValue(value: string): number {
  const cleaned = String(value ?? '').replace(/[^0-9.]/g, '')
  const parsed = parseFloat(cleaned)
  return Number.isFinite(parsed) ? parsed : 0
}

function recordMetricHistory(metrics: DashboardMetric[]) {
  const next: Record<string, number[]> = { ...metricHistory.value }
  for (const metric of metrics) {
    const series = Array.isArray(next[metric.id]) ? next[metric.id].slice() : []
    series.push(numericValue(metric.value))
    if (series.length > 16) series.splice(0, series.length - 16)
    next[metric.id] = series
  }
  metricHistory.value = next
  try {
    localStorage.setItem(METRIC_HISTORY_KEY, JSON.stringify(next))
  } catch {
    /* ignore */
  }
}

const sparks = computed(() => {
  const map: Record<string, { line: string; area: string; lastX: number; lastY: number; trend: number }> = {}
  for (const [id, points] of Object.entries(metricHistory.value)) {
    if (!Array.isArray(points) || points.length < 2) continue
    const max = Math.max(...points)
    const min = Math.min(...points)
    const span = max - min || 1
    const stepX = SPARK_W / (points.length - 1)
    const coords = points.map((v, i) => {
      const x = i * stepX
      const y = SPARK_H - 4 - ((v - min) / span) * (SPARK_H - 8)
      return [x, y] as [number, number]
    })
    const line = coords.map(([x, y], i) => `${i === 0 ? 'M' : 'L'}${x.toFixed(1)} ${y.toFixed(1)}`).join(' ')
    const area = `${line} L${SPARK_W} ${SPARK_H} L0 ${SPARK_H} Z`
    const last = coords[coords.length - 1]
    map[id] = {
      line,
      area,
      lastX: last[0],
      lastY: last[1],
      trend: points[points.length - 1] - points[0],
    }
  }
  return map
})
const recentRows = computed<DashboardRecentRow[]>(() => dashboardOverview.value?.recentRows || [])
const dashboardBadge = computed(() => {
  const production = dashboardOverview.value?.productionSummary || {}
  const tasks = dashboardOverview.value?.taskSummary || {}
  return Number(production.issueCount || 0) + Number(tasks.active || 0)
})

async function loadDefaultVideoModel() {
  try {
    const res = await aiConfigApi.list()
    const list = (res.data?.data || []) as Array<{
      configType?: string
      isDefault?: number
      model?: string
    }>
    const videos = list.filter((c) => c.configType === 'video')
    const def = videos.find((c) => c.isDefault === 1) || videos[0]
    videoModelName.value = (def?.model || '').trim()
  } catch {
    videoModelName.value = ''
  }
}

onMounted(() => {
  loadMetricHistory()
  loadDefaultVideoModel()
  loadDashboardOverview()
  try {
    const saved = sessionStorage.getItem(EPISODE_COUNT_KEY)
    if (saved) {
      const n = parseInt(saved, 10)
      if (n >= 1 && n <= 40) episodeCount.value = n
    }
    const savedGenre = sessionStorage.getItem(GENRE_KEY)
    if (savedGenre && GENRE_OPTIONS.some((item) => item.value === savedGenre)) {
      selectedGenre.value = savedGenre
    }
    const savedPlatform = sessionStorage.getItem(PLATFORM_PROFILE_KEY)
    if (savedPlatform === 'douyin' || savedPlatform === 'hongguo') {
      platformProfile.value = savedPlatform
    }
    const savedMode = sessionStorage.getItem(PRODUCTION_MODE_KEY)
    if (savedMode === 'preview' || savedMode === 'publish') {
      productionMode.value = savedMode
    }
  } catch { /* ignore */ }
})

async function loadDashboardOverview() {
  dashboardLoading.value = true
  try {
    const res = await dashboardApi.getOverview()
    dashboardOverview.value = res.data?.data || null
    if (dashboardOverview.value?.metrics?.length) {
      recordMetricHistory(dashboardOverview.value.metrics)
    }
  } catch {
    dashboardOverview.value = null
    ElMessage.warning('工作台真实数据加载失败')
  } finally {
    dashboardLoading.value = false
  }
}

function setPlatformProfile(value: PlatformProfile) {
  platformProfile.value = value
}

function setProductionMode(value: ProductionMode) {
  productionMode.value = value
}

function projectInspirationKey(id: string | number) {
  return `${INSPIRATION_KEY}:${id}`
}

async function goFromInspiration() {
  const text = inspiration.value.trim()
  if (!text) {
    ElMessage.warning('请先输入创作灵感')
    return
  }
  try {
    sessionStorage.setItem(EPISODE_COUNT_KEY, String(episodeCount.value))
    sessionStorage.setItem(GENRE_KEY, selectedGenre.value)
    sessionStorage.setItem(PLATFORM_PROFILE_KEY, platformProfile.value)
    sessionStorage.setItem(PRODUCTION_MODE_KEY, productionMode.value)
  } catch {
    /* ignore */
  }

  const episodes = episodeCount.value

  creatingProject.value = true
  try {
    const res = await projectApi.create({
      name: text.length > 40 ? `${text.slice(0, 37)}…` : text,
      description: text,
      projectType: DEFAULT_PROJECT_TYPE,
      genre: selectedGenre.value || DEFAULT_GENRE,
      episodes,
      episodeDuration: 60,
    })
    const project = res.data?.data
    const pid = project?.id
    if (!pid) {
      ElMessage.error('创建项目失败')
      return
    }
    try {
      sessionStorage.setItem(projectInspirationKey(pid), text)
      sessionStorage.removeItem(INSPIRATION_KEY)
    } catch {
      /* ignore */
    }
    await router.push(`/projects/${pid}/immersive`)
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '创建项目失败'
    ElMessage.error(msg)
  } finally {
    creatingProject.value = false
  }
}

function metricIcon(id: string) {
  const icons: Record<string, string> = {
    activeTasks: '✦',
    queue: '☰',
    firstFrames: '◎',
    videoReady: '⌁',
    environment: '▣',
    issues: '▻',
  }
  return icons[id] || '◎'
}

function goToRecentTarget() {
  const latestProject = dashboardOverview.value?.latestProject
  if (latestProject?.id) {
    router.push(`/projects/${latestProject.id}/immersive/workbench`)
    return
  }
  router.push('/projects')
}

</script>

<style scoped>
.dashboard-immersive {
  position: relative;
  display: block;
  min-height: 100%;
  padding: 0;
  overflow-x: clip;
  overflow-y: auto;
  background: var(--page-environment);
  color: #f7fbff;
}

.dashboard-bg {
  position: fixed;
  inset: 0 0 0 var(--sidebar-width);
  pointer-events: none;
  background:
    linear-gradient(rgba(255, 255, 255, 0.018) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.018) 1px, transparent 1px);
  background-size: 72px 72px;
  mask-image: linear-gradient(180deg, black, transparent 85%);
}

.dashboard-inner {
  position: relative;
  box-sizing: border-box;
  width: min(1480px, calc(100% - 56px));
  max-width: none;
  margin: 0 auto;
  padding: 30px 28px 48px;
}

.dashboard-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 22px;
}

.dashboard-eyebrow {
  margin: 0 0 6px;
  color: var(--primary);
  font-size: 13px;
  font-weight: 700;
}

.dashboard-topbar h1 {
  margin: 0;
  font-size: 28px;
  letter-spacing: 0;
}

.dashboard-actions {
  display: flex;
  align-items: center;
  gap: 16px;
}

.kbd-pill,
.mini-avatar {
  height: 38px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid rgba(150, 190, 255, 0.16);
  background: rgba(255, 255, 255, 0.055);
  color: #dbe8ff;
  border-radius: 8px;
}

.kbd-pill {
  padding: 0 15px;
}

.notify-dot {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: #6d5dfc;
  color: #fff;
  font-size: 12px;
  box-shadow: 0 0 22px rgba(109, 93, 252, 0.45);
}

.mini-avatar {
  width: 46px;
  font-weight: 800;
}

.inspiration-panel,
.metric-card,
.recent-panel {
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--surface-panel);
  box-shadow: var(--shadow-md), inset 0 1px 0 rgba(255, 255, 255, 0.11);
  backdrop-filter: blur(var(--glass-blur)) saturate(145%);
}

.inspiration-panel {
  position: relative;
  overflow: hidden;
  padding: 30px 34px 24px;
  border-color: rgba(103, 232, 249, 0.24);
  box-shadow: var(--shadow-md), 0 0 0 1px rgba(103, 232, 249, 0.08) inset;
}

.panel-orbit {
  position: absolute;
  inset: 0;
  background:
    radial-gradient(ellipse at 74% 20%, transparent 0 24%, rgba(139, 92, 246, 0.08) 25%, transparent 26%),
    radial-gradient(circle at 12% 0%, rgba(103, 232, 249, 0.1), transparent 28%);
  opacity: 0.9;
  pointer-events: none;
}

.inspiration-head {
  position: relative;
  display: flex;
  align-items: center;
  gap: 16px;
}

.spark-icon,
.metric-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border-radius: 8px;
  background: rgba(24, 216, 255, 0.12);
  color: var(--primary);
  box-shadow: 0 0 26px rgba(103, 232, 249, 0.14);
}

.inspiration-head h2 {
  margin: 0;
  font-size: 22px;
  color: var(--primary);
}

.auto-enhance {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 10px;
  color: #b9c4d6;
  font-size: 14px;
}

.auto-enhance input {
  width: 42px;
  height: 22px;
  appearance: none;
  border-radius: 999px;
  background: var(--primary);
  position: relative;
  cursor: pointer;
}

.auto-enhance input::after {
  content: "";
  position: absolute;
  top: 3px;
  right: 3px;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: #fff;
}

.inspiration-input {
  position: relative;
  width: 100%;
  min-height: 92px;
  margin-top: 26px;
  border: 0;
  outline: none;
  resize: vertical;
  background: transparent;
  color: #f7fbff;
  font-size: 21px;
  line-height: 1.55;
}

.inspiration-input::placeholder {
  color: #8897ae;
}

.inspiration-footer,
.creation-controls {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  flex-wrap: wrap;
}

.prompt-tools {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
}

.prompt-tools button,
.recent-head button,
.creation-segment button {
  border: 1px solid rgba(150, 190, 255, 0.16);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.055);
  color: #dbe8ff;
}

.prompt-tools button {
  height: 42px;
  padding: 0 18px;
}

.generate-pack {
  display: flex;
  align-items: center;
  gap: 24px;
  color: #b9c4d6;
}

.generate-btn {
  height: 50px;
  min-width: 190px;
  border: 0;
  border-radius: 8px;
  background: var(--primary);
  color: #fff;
  font-size: 18px;
  font-weight: 700;
  box-shadow: var(--shadow-primary);
  transition: background 0.18s, transform 0.18s;
}

.generate-btn:hover {
  background: var(--primary-dark);
  transform: translateY(-1px);
}

.creation-controls {
  margin-top: 22px;
  padding-top: 18px;
  border-top: 1px solid rgba(150, 190, 255, 0.12);
  justify-content: flex-start;
}

.creation-field {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  color: #9aa8bd;
  font-size: 13px;
}

.creation-field b {
  color: #f7fbff;
}

.creation-segment {
  display: inline-flex;
  gap: 6px;
}

.creation-segment button {
  height: 30px;
  padding: 0 10px;
  color: #9aa8bd;
}

.creation-segment button.active {
  border-color: rgba(103, 232, 249, 0.3);
  color: #fff;
  background: rgba(103, 232, 249, 0.12);
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(260px, 1fr));
  gap: 20px;
  margin-top: 20px;
}

.metric-card {
  padding: 22px;
  min-height: 186px;
}

.metric-card--empty {
  grid-column: 1 / -1;
}

.metric-top,
.metric-card footer,
.recent-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
}

.metric-top b {
  margin-right: auto;
  font-size: 17px;
}

.metric-top em {
  font-style: normal;
  color: #b9c4d6;
  font-size: 13px;
}

.metric-top em::before {
  content: "";
  display: inline-block;
  width: 9px;
  height: 9px;
  margin-right: 8px;
  border-radius: 50%;
  background: var(--secondary);
  box-shadow: 0 0 14px rgba(139, 124, 255, 0.56);
}

.metric-value {
  margin: 18px 0 4px;
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.metric-value strong {
  font-size: 38px;
  line-height: 1;
}

.metric-value span,
.metric-card p,
.metric-card footer {
  color: #aab5c8;
}

.metric-viz {
  display: flex;
  align-items: center;
  gap: 14px;
  margin: 18px 0;
}

.metric-viz .metric-track {
  flex: 1;
  margin: 0;
}

.metric-spark {
  flex-shrink: 0;
  width: 116px;
  height: 34px;
  color: var(--primary);
}

.metric-spark.is-down {
  color: var(--color-warning);
}

.metric-spark svg {
  display: block;
  width: 100%;
  height: 100%;
  overflow: visible;
}

.spark-area {
  fill: currentColor;
  opacity: 0.12;
  stroke: none;
}

.spark-line {
  fill: none;
  stroke: currentColor;
  stroke-width: 1.6;
  stroke-linecap: round;
  stroke-linejoin: round;
  vector-effect: non-scaling-stroke;
}

.spark-dot {
  fill: currentColor;
}

.metric-spark--empty {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  color: var(--text-muted);
  font-size: 11px;
  letter-spacing: 0.02em;
}

.metric-track {
  height: 7px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.08);
  overflow: hidden;
}

.metric-track span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, var(--primary), var(--secondary));
}

.recent-panel {
  margin-top: 20px;
  padding: 22px;
}

.recent-head h2 {
  margin: 0;
  font-size: 20px;
}

.recent-head button {
  height: 36px;
  padding: 0 16px;
}

.recent-table {
  margin-top: 14px;
  border: 1px solid rgba(150, 190, 255, 0.11);
  border-radius: 8px;
  overflow: hidden;
}

.recent-row {
  display: grid;
  grid-template-columns: 1.4fr 1fr 1fr 0.7fr 0.8fr;
  gap: 16px;
  padding: 13px 16px;
  color: #dbe8ff;
  border-top: 1px solid rgba(150, 190, 255, 0.09);
}

.recent-row--head {
  border-top: 0;
  background: rgba(255, 255, 255, 0.045);
  color: #8897ae;
  font-size: 13px;
}

.recent-row--empty {
  color: #9aa9be;
}

.cyan { color: var(--primary); }
.violet { color: var(--secondary-light); }
.green { color: var(--color-success); }
.red { color: var(--color-danger); }

@media (max-width: 1180px) {
  .metric-grid {
    grid-template-columns: repeat(2, minmax(240px, 1fr));
  }
}

@media (max-width: 760px) {
  .dashboard-bg {
    inset: 0;
  }

  .dashboard-inner {
    width: 100%;
    padding: 20px 14px 32px;
  }

  .dashboard-topbar {
    align-items: flex-start;
    flex-direction: column;
  }

  .metric-grid {
    grid-template-columns: 1fr;
  }

  .recent-row {
    grid-template-columns: 1fr;
  }
}
</style>
