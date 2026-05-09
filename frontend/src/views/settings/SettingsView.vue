<template>
  <div class="settings-immersive-root">
    <div class="settings-inner">
    <div class="settings-header">
      <div class="settings-header-left">
        <h1 class="settings-title">AI 配置中心</h1>
        <p class="settings-desc">管理文本、图像、视频、语音等AI服务商的接入配置</p>
      </div>
      <el-button type="primary" @click="openDialog()">
        <el-icon><Plus /></el-icon>
        添加配置
      </el-button>
    </div>

    <div class="config-tabs">
      <div
        v-for="tab in tabs"
        :key="tab.type"
        class="config-tab"
        :class="{ active: activeTab === tab.type }"
        @click="activeTab = tab.type"
      >
        <div class="tab-icon" v-html="tab.icon"></div>
        <div class="tab-info">
          <div class="tab-label">{{ tab.label }}</div>
          <div class="tab-count">{{ filteredConfigs(tab.type).length }} 个配置</div>
        </div>
      </div>
    </div>

    <section v-if="activeTab === 'image'" class="image-debug-panel" aria-label="文生图 API 调试">
      <div class="image-debug-head">
        <h3 class="image-debug-title">文生图 API 调试</h3>
        <p class="image-debug-desc">
          使用当前账号解析后的文生图配置（优先「默认」配置，否则回退服务端环境变量）发起一次生成；返回的图片会经素材服务写入
          <strong>腾讯云 COS</strong>（未启用 COS 时保存到本地并通过 /api/files 访问）。
        </p>
      </div>
      <el-input
        v-model="imageDebugPrompt"
        type="textarea"
        :rows="4"
        placeholder="输入中文或英文画面描述，例如：黄昏时分的江南水乡，青瓦白墙，电影感柔光，竖屏构图"
        maxlength="2000"
        show-word-limit
      />
      <div class="image-debug-row">
        <div class="image-debug-size">
          <span class="image-debug-label">输出尺寸</span>
          <el-select v-model="imageDebugSize" style="width: 200px">
            <el-option label="1024×1024（方图）" value="1024x1024" />
            <el-option label="1024×1792（竖屏）" value="1024x1792" />
            <el-option label="1792×1024（横屏）" value="1792x1024" />
          </el-select>
        </div>
        <el-button type="primary" :loading="imageDebugLoading" @click="runImageDebug">
          生成并上传
        </el-button>
      </div>
      <p v-if="imageDebugError" class="image-debug-err">{{ imageDebugError }}</p>
      <div v-if="imageDebugResultUrl" class="image-debug-preview">
        <div class="image-debug-preview-head">
          <span>预览</span>
          <el-button text type="primary" size="small" @click="copyImageDebugUrl">复制公网 URL</el-button>
        </div>
        <img :src="imageDebugResultUrl" alt="文生图调试预览" class="image-debug-img" />
        <p class="image-debug-url mono">{{ imageDebugResultUrl }}</p>
        <p v-if="imageDebugProviderUrl" class="image-debug-meta">
          模型侧原始地址（未强制转存时可能为临时链）：
          <span class="mono">{{ imageDebugProviderUrl }}</span>
        </p>
      </div>
    </section>

    <div class="config-list">
      <div v-if="filteredConfigs(activeTab).length === 0" class="empty-config">
        <div class="empty-icon">
          <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
        </div>
        <div class="empty-text">暂未配置{{ currentTabLabel }}服务</div>
        <div class="empty-hint">点击"添加配置"接入您的AI服务商</div>
      </div>
      <div
        v-for="config in filteredConfigs(activeTab)"
        :key="config.id"
        class="config-card"
        :class="{ 'is-default': config.isDefault === 1 }"
      >
        <div class="config-card-header">
          <div class="provider-badge">
            <span class="provider-name">{{ providerLabel(config.provider, config.configType) }}</span>
            <el-tag v-if="config.isDefault === 1" type="success" size="small" effect="dark">默认</el-tag>
          </div>
          <div class="config-actions">
            <el-button text size="small" type="primary" @click="openDialog(config)">
              <el-icon><Edit /></el-icon> 编辑
            </el-button>
            <el-button text size="small" type="success" @click="setDefault(config.id)" v-if="config.isDefault !== 1">
              <el-icon><Select /></el-icon> 设为默认
            </el-button>
            <el-popconfirm title="确认删除此配置？" @confirm="deleteConfig(config.id)">
              <template #reference>
                <el-button text size="small" type="danger">
                  <el-icon><Delete /></el-icon> 删除
                </el-button>
              </template>
            </el-popconfirm>
          </div>
        </div>
        <div class="config-card-body">
          <div class="config-field">
            <span class="field-label">模型</span>
            <span class="field-value">{{ config.model || '-' }}</span>
          </div>
          <div class="config-field">
            <span class="field-label">Base URL</span>
            <span class="field-value field-url">{{ config.baseUrl || '-' }}</span>
          </div>
          <div class="config-field">
            <span class="field-label">API Key</span>
            <span class="field-value">{{ maskKey(config.apiKey) }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- Add/Edit Dialog -->
    <el-dialog
      v-model="showDialog"
      :title="editing ? '编辑 AI 配置' : '添加 AI 配置'"
      width="620px"
      :close-on-click-modal="false"
      class="ai-config-dialog"
    >
      <el-form :model="form" label-width="90px" class="config-form">
        <el-form-item label="配置类型" required>
          <el-select v-model="form.configType" style="width: 100%" @change="onTypeChange">
            <el-option label="文本大模型（剧本 / 分镜生成）" value="text" />
            <el-option label="文生图（角色 / 场景 / 分镜图）" value="image" />
            <el-option label="文生视频 / 图生视频" value="video" />
            <el-option label="TTS 语音合成" value="tts" />
          </el-select>
        </el-form-item>
        <el-form-item label="服务商" required>
          <el-select v-model="form.provider" style="width: 100%" @change="onProviderChange" filterable>
            <el-option-group v-for="group in filteredProviderGroups" :key="group.label" :label="group.label">
              <el-option
                v-for="p in group.providers"
                :key="p.value"
                :label="p.label"
                :value="p.value"
              >
                <div class="provider-option">
                  <span class="provider-option-name">{{ p.label }}</span>
                  <span class="provider-option-tag">{{ p.tagLabel }}</span>
                </div>
              </el-option>
            </el-option-group>
          </el-select>
          <div class="form-tip provider-hint" v-if="currentProviderHint">
            <el-icon><InfoFilled /></el-icon> {{ currentProviderHint }}
          </div>
        </el-form-item>
        <el-form-item label="Base URL">
          <el-input v-model="form.baseUrl" placeholder="选择服务商后自动填充，或手动输入">
            <template #append>
              <el-button @click="resetBaseUrl" title="重置为默认URL">
                <el-icon><RefreshRight /></el-icon>
              </el-button>
            </template>
          </el-input>
          <div class="form-tip" v-if="form.provider && getProviderDefaultUrl(form.provider)">
            默认端点：{{ getProviderDefaultUrl(form.provider) }}
          </div>
        </el-form-item>
        <el-form-item label="API Key" required>
          <el-input v-model="form.apiKey" type="password" placeholder="输入 API 密钥（Key / Token）" show-password />
          <div class="form-tip key-tip">
            <el-icon><Lock /></el-icon> 密钥加密存储在您的账户，不会对外暴露
          </div>
        </el-form-item>
        <el-form-item label="模型名称">
          <div class="model-input-row">
            <el-select
              v-model="form.model"
              filterable
              allow-create
              default-first-option
              clearable
              style="width: 100%"
              :placeholder="getModelPlaceholder()"
            >
              <el-option
                v-for="model in modelOptions"
                :key="model"
                :label="model"
                :value="model"
              />
            </el-select>
            <el-button @click="resetModel" title="重置为默认模型">
              <el-icon><RefreshRight /></el-icon>
            </el-button>
          </div>
          <div class="form-tip">可从推荐列表中选择，也可直接输入自定义模型名</div>
        </el-form-item>
        <el-form-item label="设为默认">
          <div class="default-row">
            <el-switch v-model="isDefault" />
            <span class="switch-hint">启用后此配置作为 {{ currentTabLabel }} 的默认服务</span>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="showDialog = false">取消</el-button>
          <el-button type="primary" :loading="submitting" @click="handleSave">
            {{ editing ? '保存修改' : '添加配置' }}
          </el-button>
        </div>
      </template>
    </el-dialog>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Edit, Delete, Select, RefreshRight, InfoFilled, Lock } from '@element-plus/icons-vue'
import { aiConfigApi } from '../../api/aiConfig'

const configs = ref<any[]>([])
const showDialog = ref(false)
const editing = ref(false)
const submitting = ref(false)
const activeTab = ref('text')
const isDefault = ref(false)

const imageDebugPrompt = ref('黄昏时分的江南水乡，青瓦白墙，电影感柔光，竖屏短剧分镜，高清细节')
const imageDebugSize = ref('1024x1024')
const imageDebugLoading = ref(false)
const imageDebugResultUrl = ref('')
const imageDebugProviderUrl = ref('')
const imageDebugError = ref('')

const tabs = [
  {
    type: 'text',
    label: '文本大模型',
    icon: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/><polyline points="10 9 9 9 8 9"/></svg>',
  },
  {
    type: 'image',
    label: '文生图',
    icon: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/></svg>',
  },
  {
    type: 'video',
    label: '文生视频',
    icon: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="23 7 16 12 23 17 23 7"/><rect x="1" y="5" width="15" height="14" rx="2" ry="2"/></svg>',
  },
  {
    type: 'tts',
    label: 'TTS 语音',
    icon: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"/><path d="M19 10v2a7 7 0 0 1-14 0v-2"/><line x1="12" y1="19" x2="12" y2="23"/><line x1="8" y1="23" x2="16" y2="23"/></svg>',
  },
]

// All providers with type and tag metadata
const allProviders = [
  // ──────── 文本大模型 ────────
  { value: 'deepseek', label: 'DeepSeek', tagLabel: '推荐·文本', types: ['text'] },
  { value: 'openai', label: 'OpenAI (GPT)', tagLabel: '文本', types: ['text'] },
  { value: 'qianwen', label: '阿里通义千问', tagLabel: '文本', types: ['text'] },
  { value: 'doubao', label: '火山引擎豆包', tagLabel: '文本', types: ['text'] },
  { value: 'moonshot', label: 'Moonshot (月之暗面)', tagLabel: '文本', types: ['text'] },
  { value: 'zhipu', label: '智谱 GLM', tagLabel: '文本', types: ['text'] },
  { value: 'baichuan', label: '百川智能', tagLabel: '文本', types: ['text'] },
  { value: 'wenxin', label: '百度文心一言', tagLabel: '文本', types: ['text'] },
  { value: 'minimax', label: 'MiniMax', tagLabel: '文本', types: ['text'] },
  // ──────── 图片/视频/TTS 仅保留阿里云与自定义 ────────
  { value: 'aliyun', label: '阿里云千问生图', tagLabel: '推荐·图像', types: ['image'] },
  { value: 'aliyun', label: '阿里云万相视频', tagLabel: '推荐·视频', types: ['video'] },
  { value: 'aliyun', label: '阿里云 Qwen TTS', tagLabel: '推荐·TTS', types: ['tts'] },
  // ──────── 通用 ────────
  { value: 'custom', label: '自定义接口', tagLabel: '通用', types: ['text', 'image', 'video', 'tts'] },
]

// Provider hints (shown in dialog when selected)
const providerHints: Record<string, string> = {
  'image:aliyun': '阿里云千问生图使用 DashScope 原生 multimodal-generation 接口，模型仅支持 qwen-image-2.0 与 qwen-image-2.0-pro。',
  'video:aliyun': '阿里云万相视频使用 DashScope 原生 video-synthesis 接口，当前仅保留 wan2.6-t2v。',
  'tts:aliyun': '阿里云 Qwen TTS 使用 DashScope 原生 multimodal-generation 接口；默认推荐 qwen3-tts-instruct-flash 以支持更细致的声音描述，也可切换到 qwen3-tts-flash 或 qwen-tts 系列获取不同音色覆盖。',
  'image:custom': '自定义图片接口请填写你的真实图片生成端点，系统不会再自动按 OpenAI 方式混用阿里云接口。',
  'video:custom': '自定义视频接口请填写你的真实视频生成端点；若为异步任务，请确保返回任务查询地址或 taskId。',
  'tts:custom': '自定义 TTS 当前按 OpenAI 兼容 speech 接口调用，请填写真实兼容端点。',
}

// Build filtered provider groups based on selected configType
const filteredProviderGroups = computed(() => {
  const type = form.value.configType
  const byType = allProviders.filter(p => p.types.includes(type))

  const groups: { label: string; providers: typeof byType }[] = []

  // 首选推荐
  const recommended = byType.filter(p => p.tagLabel.includes('推荐'))
  if (recommended.length) groups.push({ label: '⭐ 推荐', providers: recommended })

  // 其余按 type 分组
  const rest = byType.filter(p => !p.tagLabel.includes('推荐') && p.value !== 'custom')
  if (rest.length) groups.push({ label: '全部服务商', providers: rest })

  // 自定义
  const custom = byType.filter(p => p.value === 'custom')
  if (custom.length) groups.push({ label: '其他', providers: custom })

  return groups
})

const currentProviderHint = computed(() => providerHints[`${form.value.configType}:${form.value.provider}`] || '')

// Provider base URL defaults (client-side for instant feedback)
type ProviderBaseUrlValue = string | Partial<Record<string, string>>

const providerBaseUrls: Record<string, ProviderBaseUrlValue> = {
  openai: 'https://api.openai.com/v1',
  deepseek: 'https://api.deepseek.com',
  qianwen: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
  aliyun: {
    image: 'https://dashscope.aliyuncs.com/api/v1',
    video: 'https://dashscope.aliyuncs.com/api/v1',
    tts: 'https://dashscope.aliyuncs.com/api/v1',
  },
  doubao: 'https://ark.cn-beijing.volces.com/api/v3',
  minimax: 'https://api.minimax.chat/v1',
  moonshot: 'https://api.moonshot.cn/v1',
  zhipu: 'https://open.bigmodel.cn/api/paas/v4',
  baichuan: 'https://api.baichuan-ai.com/v1',
  wenxin: 'https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop',
  custom: {
    image: '',
    video: '',
    tts: '',
    text: 'https://api.openai.com/v1',
  },
}

const providerModels: Record<string, Record<string, string>> = {
  openai: { text: 'gpt-4o', image: 'dall-e-3', tts: 'tts-1' },
  deepseek: { text: 'deepseek-chat' },
  qianwen: { text: 'qwen-plus', image: 'qwen-image-2.0-pro' },
  aliyun: { image: 'qwen-image-2.0-pro', video: 'wan2.6-t2v', tts: 'qwen3-tts-instruct-flash' },
  custom: { image: '', video: '', tts: '', text: 'gpt-4o' },
  doubao: { text: 'doubao-pro-32k' },
  minimax: { text: 'abab6.5s-chat', tts: 'speech-01-turbo' },
  moonshot: { text: 'moonshot-v1-8k' },
  zhipu: { text: 'glm-4' },
  baichuan: { text: 'Baichuan4' },
  wenxin: { text: 'ernie-4.0-8k' },
}

const form = ref({ id: null as any, configType: 'text', provider: 'deepseek', baseUrl: '', apiKey: '', model: '' })

const commonModelOptions: Record<string, string[]> = {
  text: ['gpt-4o', 'gpt-4.1', 'deepseek-chat', 'qwen-plus', 'glm-4'],
  image: ['qwen-image-2.0', 'qwen-image-2.0-pro'],
  video: ['wan2.6-t2v'],
  tts: ['qwen3-tts-instruct-flash', 'qwen3-tts-flash', 'qwen-tts-latest', 'qwen-tts'],
}

const currentTabLabel = computed(() => tabs.find(t => t.type === activeTab.value)?.label || '')

const modelOptions = computed(() => {
  const providerDefault = providerModels[form.value.provider]?.[form.value.configType]
  const currentValue = form.value.model
  return Array.from(new Set([
    providerDefault,
    ...(commonModelOptions[form.value.configType] || []),
    currentValue,
  ].filter(Boolean)))
})

const filteredConfigs = (type: string) => configs.value.filter(c => c.configType === type)

function resolveProviderDefaultUrl(provider: string, configType: string): string {
  const defaults = providerBaseUrls[provider]
  if (!defaults) return ''
  if (typeof defaults === 'string') return defaults
  return defaults[configType] || ''
}

function getProviderDefaultUrl(provider: string): string {
  return resolveProviderDefaultUrl(provider, form.value.configType)
}

function getModelPlaceholder(): string {
  const models = providerModels[form.value.provider]
  const model = models?.[form.value.configType]
  return model ? `推荐: ${model}` : '输入模型名称'
}

function providerLabel(provider: string, configType = activeTab.value): string {
  const p = allProviders.find(p => p.value === provider && p.types.includes(configType))
  return p ? p.label : provider
}

function normalizeProvider(provider: string, configType: string): string {
  if (configType === 'text') {
    return provider
  }
  if (['aliyun', 'qianwen', 'dashscope', 'wanx', 'cosyvoice'].includes(provider)) {
    return 'aliyun'
  }
  return 'custom'
}

function normalizeConfig(row: any) {
  return {
    ...row,
    provider: normalizeProvider(row.provider, row.configType),
  }
}

function maskKey(key: string): string {
  if (!key) return '-'
  if (key.length <= 8) return '••••••••'
  return key.substring(0, 4) + '••••••••' + key.substring(key.length - 4)
}

function openDialog(row?: any) {
  editing.value = !!row
  if (row) {
    form.value = { ...normalizeConfig(row) }
    isDefault.value = row.isDefault === 1
  } else {
    const defaultProvider = activeTab.value === 'text' ? 'deepseek' :
      activeTab.value === 'image' ? 'aliyun' :
      activeTab.value === 'video' ? 'aliyun' : 'aliyun'
    form.value = {
      id: null,
      configType: activeTab.value,
      provider: defaultProvider,
      baseUrl: resolveProviderDefaultUrl(defaultProvider, activeTab.value),
      apiKey: '',
      model: providerModels[defaultProvider]?.[activeTab.value] || '',
    }
    isDefault.value = filteredConfigs(activeTab.value).length === 0
  }
  showDialog.value = true
}

function onTypeChange() {
  const type = form.value.configType
  const defaultProvider = type === 'text' ? 'deepseek' :
    type === 'image' ? 'aliyun' :
    type === 'video' ? 'aliyun' : 'aliyun'
  form.value.provider = defaultProvider
  void onProviderChange(defaultProvider)
}

async function onProviderChange(val: string) {
  // Show local defaults immediately for better UX, then sync with backend defaults.
  form.value.baseUrl = resolveProviderDefaultUrl(val, form.value.configType)
  form.value.model = providerModels[val]?.[form.value.configType] || ''

  try {
    const res = await aiConfigApi.getProviderDefaults(val, form.value.configType)
    const defaults = res?.data?.data || {}
    if (defaults.baseUrl) {
      form.value.baseUrl = defaults.baseUrl
    }
    if (defaults.model) {
      form.value.model = defaults.model
    }
  } catch {
    // Keep local fallback values when backend defaults are temporarily unavailable.
  }
}

function resetBaseUrl() {
  form.value.baseUrl = resolveProviderDefaultUrl(form.value.provider, form.value.configType)
}

function resetModel() {
  form.value.model = providerModels[form.value.provider]?.[form.value.configType] || ''
}

async function load() {
  const res = await aiConfigApi.list()
  configs.value = (res.data.data || []).map((row: any) => normalizeConfig(row))
}

async function handleSave() {
  if (!form.value.apiKey) return ElMessage.warning('请输入 API Key')
  if (!form.value.provider) return ElMessage.warning('请选择服务商')
  submitting.value = true
  try {
    await aiConfigApi.save({ ...form.value, isDefault: isDefault.value ? 1 : 0 })
    ElMessage.success('配置保存成功')
    showDialog.value = false
    load()
  } finally {
    submitting.value = false
  }
}

async function setDefault(id: number) {
  await aiConfigApi.setDefault(id)
  ElMessage.success('已设为默认')
  load()
}

async function deleteConfig(id: number) {
  await aiConfigApi.delete(id)
  ElMessage.success('已删除')
  load()
}

async function runImageDebug() {
  if (!imageDebugPrompt.value.trim()) {
    ElMessage.warning('请输入提示词')
    return
  }
  imageDebugLoading.value = true
  imageDebugError.value = ''
  imageDebugResultUrl.value = ''
  imageDebugProviderUrl.value = ''
  try {
    const res = await aiConfigApi.debugGenerateImage({
      prompt: imageDebugPrompt.value.trim(),
      size: imageDebugSize.value,
    })
    const d = (res.data as any)?.data || {}
    imageDebugResultUrl.value = d.imageUrl || ''
    imageDebugProviderUrl.value = d.providerUrl || ''
    if (!imageDebugResultUrl.value) {
      imageDebugError.value = '接口未返回图片地址'
      return
    }
    ElMessage.success('生成完成')
  } catch (e: any) {
    imageDebugError.value = e?.message || '生成失败'
  } finally {
    imageDebugLoading.value = false
  }
}

async function copyImageDebugUrl() {
  if (!imageDebugResultUrl.value) return
  try {
    await navigator.clipboard.writeText(imageDebugResultUrl.value)
    ElMessage.success('已复制 URL')
  } catch {
    ElMessage.warning('复制失败，请手动选择文本')
  }
}

onMounted(load)
</script>

<style scoped>
.settings-immersive-root {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  width: 100%;
  box-sizing: border-box;
  padding: 28px 40px 32px 112px;
  background: #0a0a0c;
  color: #e8eaef;
  --bg-card: rgba(22, 24, 30, 0.98);
  --bg-card-hover: rgba(30, 32, 40, 0.98);
  --bg-muted: rgba(255, 255, 255, 0.06);
  --border: rgba(255, 255, 255, 0.08);
  --border-strong: rgba(255, 255, 255, 0.14);
  --text-primary: #f8fafc;
  --text-secondary: rgba(226, 232, 240, 0.92);
  --text-muted: rgba(148, 163, 184, 0.92);
  --shadow-md: 0 8px 28px rgba(0, 0, 0, 0.4);
}

.settings-inner {
  max-width: 1100px;
  margin: 0 auto;
  width: 100%;
}

.settings-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 28px;
  flex-wrap: wrap;
}
.settings-title {
  font-size: 22px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0 0 6px;
  letter-spacing: -0.02em;
}
.settings-desc {
  font-size: 13px;
  color: var(--text-muted);
  margin: 0;
  line-height: 1.55;
}

.settings-header :deep(.el-button--primary) {
  border-radius: 12px;
  border: none;
  padding: 10px 18px;
  font-weight: 600;
  background: linear-gradient(135deg, #6366f1, #8b5cf6);
  box-shadow: 0 8px 24px rgba(99, 102, 241, 0.35);
}
.settings-header :deep(.el-button--primary:hover) {
  filter: brightness(1.06);
}

.model-input-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}

/* Tabs */
.config-tabs {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 24px;
}
@media (max-width: 900px) {
  .config-tabs {
    grid-template-columns: repeat(2, 1fr);
  }
}
@media (max-width: 520px) {
  .config-tabs {
    grid-template-columns: 1fr;
  }
}
.config-tab {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 18px;
  background: var(--bg-card);
  border: 1px solid rgba(255, 255, 255, 0.06);
  border-radius: 14px;
  cursor: pointer;
  transition: border-color 0.2s, box-shadow 0.2s, transform 0.18s;
  box-shadow: 0 6px 22px rgba(0, 0, 0, 0.28);
}
.config-tab:hover {
  border-color: rgba(129, 140, 248, 0.4);
  box-shadow: 0 10px 32px rgba(0, 0, 0, 0.38);
  transform: translateY(-1px);
}
.config-tab.active {
  border-color: rgba(129, 140, 248, 0.55);
  background: rgba(99, 102, 241, 0.14);
  box-shadow: 0 8px 32px rgba(99, 102, 241, 0.2);
}
.tab-icon {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  background: rgba(99, 102, 241, 0.12);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #a5b4fc;
  flex-shrink: 0;
}
.config-tab.active .tab-icon {
  background: linear-gradient(135deg, #6366f1, #7c3aed);
  color: #fff;
}
.tab-label {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}
.tab-count {
  font-size: 12px;
  color: var(--text-muted);
  margin-top: 2px;
}

/* Config cards */
.config-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.config-card {
  background: var(--bg-card);
  border: 1px solid rgba(255, 255, 255, 0.06);
  border-radius: 14px;
  padding: 18px 22px;
  transition: box-shadow 0.2s, border-color 0.2s, transform 0.18s;
  box-shadow: 0 6px 24px rgba(0, 0, 0, 0.32);
}
.config-card:hover {
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.42);
  border-color: rgba(129, 140, 248, 0.25);
  transform: translateY(-1px);
}
.config-card.is-default {
  border-color: rgba(52, 211, 153, 0.4);
  background: rgba(16, 185, 129, 0.08);
  box-shadow: 0 6px 24px rgba(16, 185, 129, 0.12);
}
.config-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 14px;
}
.provider-badge {
  display: flex;
  align-items: center;
  gap: 10px;
}
.provider-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}
.config-actions {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}
.config-actions :deep(.el-button) {
  color: rgba(226, 232, 240, 0.88);
}
.config-actions :deep(.el-button--primary) {
  color: var(--el-color-primary);
}
.config-card-body {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}
@media (max-width: 720px) {
  .config-card-body {
    grid-template-columns: 1fr;
  }
}
.config-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.field-label {
  font-size: 11px;
  color: var(--text-muted);
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}
.field-value {
  font-size: 13px;
  color: var(--text-secondary);
  font-family: 'SF Mono', 'Fira Code', monospace;
}
.field-url {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* Empty state */
.empty-config {
  text-align: center;
  padding: 60px 24px;
  background: rgba(22, 24, 30, 0.55);
  border: 1px dashed rgba(255, 255, 255, 0.12);
  border-radius: 16px;
}
.empty-icon {
  color: rgba(148, 163, 184, 0.55);
  margin-bottom: 16px;
}
.empty-text {
  font-size: 15px;
  font-weight: 500;
  color: var(--text-secondary);
  margin-bottom: 6px;
}
.empty-hint {
  font-size: 13px;
  color: var(--text-muted);
}

/* Form */
.config-form .form-tip {
  font-size: 11px;
  color: var(--text-muted);
  margin-top: 4px;
  line-height: 1.6;
  display: flex;
  align-items: flex-start;
  gap: 4px;
}
.config-form .form-tip .el-icon {
  flex-shrink: 0;
  margin-top: 1px;
  font-size: 12px;
}
.provider-hint {
  color: #c7d2fe !important;
  background: rgba(99, 102, 241, 0.1);
  padding: 6px 10px;
  border-radius: 8px;
  border: 1px solid rgba(99, 102, 241, 0.25);
}
.key-tip {
  color: #6ee7b7 !important;
}
.default-row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.switch-hint {
  font-size: 12px;
  color: var(--text-muted);
}
.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

/* Provider option in select dropdown */
.provider-option {
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;
}
.provider-option-name {
  font-size: 13px;
  color: var(--text-primary);
}
.provider-option-tag {
  font-size: 10px;
  color: var(--text-muted);
  background: var(--bg-muted);
  padding: 1px 6px;
  border-radius: 4px;
  border: 1px solid var(--border);
  white-space: nowrap;
  margin-left: 8px;
}

.image-debug-panel {
  margin-bottom: 24px;
  padding: 20px 22px;
  background: var(--bg-card);
  border: 1px solid rgba(129, 140, 248, 0.22);
  border-radius: 14px;
  box-shadow: 0 8px 28px rgba(0, 0, 0, 0.35);
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.image-debug-head {
  margin-bottom: 2px;
}
.image-debug-title {
  margin: 0 0 8px;
  font-size: 15px;
  font-weight: 700;
  color: var(--text-primary);
}
.image-debug-desc {
  margin: 0;
  font-size: 12px;
  line-height: 1.65;
  color: var(--text-muted);
}
.image-debug-desc strong {
  color: #c7d2fe;
  font-weight: 600;
}
.image-debug-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.image-debug-size {
  display: flex;
  align-items: center;
  gap: 10px;
}
.image-debug-label {
  font-size: 12px;
  color: var(--text-muted);
}
.image-debug-err {
  margin: 0;
  font-size: 13px;
  color: #fca5a5;
  line-height: 1.5;
}
.image-debug-preview {
  padding-top: 4px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
}
.image-debug-preview-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-secondary);
}
.image-debug-img {
  display: block;
  max-width: min(100%, 420px);
  max-height: 420px;
  width: auto;
  height: auto;
  border-radius: 12px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: rgba(0, 0, 0, 0.35);
  object-fit: contain;
}
.image-debug-url {
  margin: 10px 0 0;
  font-size: 11px;
  color: rgba(148, 163, 184, 0.95);
  word-break: break-all;
  line-height: 1.5;
}
.image-debug-meta {
  margin: 8px 0 0;
  font-size: 11px;
  color: var(--text-muted);
  line-height: 1.5;
}
.mono {
  font-family: 'SF Mono', 'Fira Code', ui-monospace, monospace;
}
</style>
