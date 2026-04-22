<template>
  <div class="settings-page">
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
            <span class="provider-name">{{ providerLabel(config.provider) }}</span>
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
          <el-input v-model="form.model" :placeholder="getModelPlaceholder()">
            <template #append>
              <el-button @click="resetModel" title="重置为默认模型">
                <el-icon><RefreshRight /></el-icon>
              </el-button>
            </template>
          </el-input>
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
  { value: 'openai', label: 'OpenAI (GPT)', tagLabel: '文本/图像/TTS', types: ['text', 'image', 'tts'] },
  { value: 'qianwen', label: '阿里通义千问', tagLabel: '文本/图像', types: ['text', 'image'] },
  { value: 'doubao', label: '火山引擎豆包', tagLabel: '文本', types: ['text'] },
  { value: 'moonshot', label: 'Moonshot (月之暗面)', tagLabel: '文本', types: ['text'] },
  { value: 'zhipu', label: '智谱 GLM', tagLabel: '文本', types: ['text'] },
  { value: 'baichuan', label: '百川智能', tagLabel: '文本', types: ['text'] },
  { value: 'wenxin', label: '百度文心一言', tagLabel: '文本', types: ['text'] },
  { value: 'minimax', label: 'MiniMax', tagLabel: '文本/TTS', types: ['text', 'tts'] },
  // ──────── 文生图 ────────
  { value: 'wanx', label: '通义万相 Wanx 2.1（推荐）', tagLabel: '推荐·图像', types: ['image'] },
  { value: 'dashscope', label: '阿里云百炼 DashScope', tagLabel: '图像/视频', types: ['image', 'video'] },
  { value: 'seedream', label: '火山引擎 Seedream 3.0', tagLabel: '图像', types: ['image'] },
  { value: 'external', label: '外部接口生图', tagLabel: '图像·中转', types: ['image'] },
  { value: 'kling', label: '可灵 AI (Kling)', tagLabel: '视频/图像', types: ['video', 'image'] },
  { value: 'jimeng', label: '即梦 AI', tagLabel: '图像/视频', types: ['image', 'video'] },
  { value: 'runway', label: 'Runway', tagLabel: '视频', types: ['video'] },
  { value: 'sd', label: 'Stable Diffusion (本地)', tagLabel: '图像', types: ['image'] },
  // ──────── 文生视频 ────────
  { value: 'seedance', label: '火山引擎 Seedance 2.0（推荐）', tagLabel: '推荐·视频', types: ['video'] },
  // ──────── TTS 语音 ────────
  { value: 'cosyvoice', label: '阿里云 CosyVoice V3（推荐）', tagLabel: '推荐·TTS', types: ['tts'] },
  { value: 'xunfei', label: '科大讯飞星火 TTS', tagLabel: 'TTS', types: ['tts'] },
  { value: 'volcengine', label: '火山引擎语音合成', tagLabel: 'TTS', types: ['tts'] },
  // ──────── 通用 ────────
  { value: 'custom', label: '自定义 (OpenAI 兼容)', tagLabel: '通用', types: ['text', 'image', 'video', 'tts'] },
]

// Provider hints (shown in dialog when selected)
const providerHints: Record<string, string> = {
  wanx: '通义万相 Wanx 2.1 — 阿里云百炼，适合短剧封面/分镜批量生成，性价比极高，需开通阿里云百炼服务',
  seedream: '火山引擎 Seedream 3.0 — 人像质感优秀，适合连续剧集封面，需在火山引擎方舟平台开通',
  seedance: '火山引擎 Seedance 2.0 — 角色一致性强，支持 1080P/60fps，适合短剧片段生成，需在火山引擎方舟平台开通',
  cosyvoice: '阿里云 CosyVoice V3 — 情感表现力强，适配短剧台词，使用阿里云百炼 API Key（与 Wanx 同一账号）',
  xunfei: '科大讯飞星火 TTS — 中文发音自然，支持方言，请在讯飞开放平台（xf-yun.com）申请 API Key',
  dashscope: '阿里云百炼（DashScope）— 通义万相文生图/视频统一入口，支持异步批量任务',
  kling: '可灵 AI — 视频生成效果佳，按时长计费，请在可灵 AI 官网开通服务',
  external: '通过外部中转接口生成图片。Base URL 请填写你的外部服务地址，系统会自动拼接 /images/generations。',
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

const currentProviderHint = computed(() => providerHints[form.value.provider] || '')

// Provider base URL defaults (client-side for instant feedback)
const providerBaseUrls: Record<string, string> = {
  openai: 'https://api.openai.com/v1',
  deepseek: 'https://api.deepseek.com',
  qianwen: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
  dashscope: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
  wanx: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
  cosyvoice: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
  doubao: 'https://ark.cn-beijing.volces.com/api/v3',
  seedream: 'https://visual.volcengineapi.com',
  seedance: 'https://open.volcengineapi.com',
  minimax: 'https://api.minimax.chat/v1',
  moonshot: 'https://api.moonshot.cn/v1',
  zhipu: 'https://open.bigmodel.cn/api/paas/v4',
  baichuan: 'https://api.baichuan-ai.com/v1',
  wenxin: 'https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop',
  kling: 'https://api.klingai.com/v1',
  jimeng: 'https://jimeng.jianying.com/v1',
  runway: 'https://api.dev.runwayml.com/v1',
  volcengine: 'https://openspeech.bytedance.com/api/v1',
  xunfei: 'https://spark-api-open.xf-yun.com/v1',
  sd: 'http://localhost:7860',
  external: 'http://localhost:9000',
}

const providerModels: Record<string, Record<string, string>> = {
  openai: { text: 'gpt-4o', image: 'dall-e-3', tts: 'tts-1' },
  deepseek: { text: 'deepseek-chat' },
  qianwen: { text: 'qwen-plus', image: 'qwen-image-2.0-pro' },
  dashscope: { image: 'qwen-image-2.0-pro', video: 'wanx-v1-video' },
  wanx: { image: 'wanx2.1-t2i-turbo' },
  cosyvoice: { tts: 'cosyvoice-v3-flash' },
  xunfei: { tts: 'x1' },
  seedream: { image: 'high_aes_general_v30l_zt2i' },
  external: { image: 'qwen-image-2.0-pro' },
  seedance: { video: 'seedance2.0-turbo' },
  doubao: { text: 'doubao-pro-32k' },
  minimax: { text: 'abab6.5s-chat', tts: 'speech-01-turbo' },
  moonshot: { text: 'moonshot-v1-8k' },
  zhipu: { text: 'glm-4' },
  baichuan: { text: 'Baichuan4' },
  wenxin: { text: 'ernie-4.0-8k' },
  kling: { video: 'kling-v1', image: 'kolors-v1' },
  jimeng: { image: 'jimeng-2.1-pro', video: 'jimeng-video-v1' },
  runway: { video: 'gen-3' },
  volcengine: { tts: 'zh_female_qingxin' },
  sd: { image: 'stable-diffusion-xl' },
}

const form = ref({ id: null as any, configType: 'text', provider: 'deepseek', baseUrl: '', apiKey: '', model: '' })

const currentTabLabel = computed(() => tabs.find(t => t.type === activeTab.value)?.label || '')

const filteredConfigs = (type: string) => configs.value.filter(c => c.configType === type)

function getProviderDefaultUrl(provider: string): string {
  return providerBaseUrls[provider] || ''
}

function getModelPlaceholder(): string {
  const models = providerModels[form.value.provider]
  const model = models?.[form.value.configType]
  return model ? `推荐: ${model}` : '输入模型名称'
}

function providerLabel(provider: string): string {
  const p = allProviders.find(p => p.value === provider)
  return p ? p.label : provider
}

function maskKey(key: string): string {
  if (!key) return '-'
  if (key.length <= 8) return '••••••••'
  return key.substring(0, 4) + '••••••••' + key.substring(key.length - 4)
}

function openDialog(row?: any) {
  editing.value = !!row
  if (row) {
    form.value = { ...row }
    isDefault.value = row.isDefault === 1
  } else {
    const defaultProvider = activeTab.value === 'text' ? 'deepseek' :
      activeTab.value === 'image' ? 'wanx' :
      activeTab.value === 'video' ? 'seedance' : 'cosyvoice'
    form.value = {
      id: null,
      configType: activeTab.value,
      provider: defaultProvider,
      baseUrl: providerBaseUrls[defaultProvider] || '',
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
    type === 'image' ? 'wanx' :
    type === 'video' ? 'seedance' : 'cosyvoice'
  form.value.provider = defaultProvider
  void onProviderChange(defaultProvider)
}

async function onProviderChange(val: string) {
  // Show local defaults immediately for better UX, then sync with backend defaults.
  form.value.baseUrl = providerBaseUrls[val] || ''
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
  form.value.baseUrl = providerBaseUrls[form.value.provider] || ''
}

function resetModel() {
  form.value.model = providerModels[form.value.provider]?.[form.value.configType] || ''
}

async function load() {
  const res = await aiConfigApi.list()
  configs.value = res.data.data || []
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

onMounted(load)
</script>

<style scoped>
.settings-page {
  padding: 28px 32px;
  max-width: 1100px;
}

.settings-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 28px;
}
.settings-title {
  font-size: 22px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0 0 6px;
}
.settings-desc {
  font-size: 13px;
  color: var(--text-muted);
  margin: 0;
}

/* Tabs */
.config-tabs {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 24px;
}
.config-tab {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 18px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 12px;
  cursor: pointer;
  transition: all 0.2s;
}
.config-tab:hover {
  border-color: var(--primary);
  box-shadow: 0 2px 8px rgba(99, 102, 241, 0.1);
}
.config-tab.active {
  border-color: var(--primary);
  background: rgba(99, 102, 241, 0.04);
  box-shadow: 0 2px 12px rgba(99, 102, 241, 0.12);
}
.tab-icon {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  background: rgba(99, 102, 241, 0.08);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--primary);
  flex-shrink: 0;
}
.config-tab.active .tab-icon {
  background: var(--primary);
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
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 18px 22px;
  transition: box-shadow 0.2s, border-color 0.2s;
}
.config-card:hover {
  box-shadow: var(--shadow-md);
}
.config-card.is-default {
  border-color: rgba(16, 185, 129, 0.3);
  background: rgba(16, 185, 129, 0.02);
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
}
.config-card-body {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
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
  background: var(--bg-card);
  border: 1px dashed var(--border-strong);
  border-radius: 12px;
}
.empty-icon {
  color: var(--text-muted);
  margin-bottom: 16px;
  opacity: 0.5;
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
  color: var(--primary) !important;
  background: rgba(99, 102, 241, 0.05);
  padding: 6px 10px;
  border-radius: 6px;
  border: 1px solid rgba(99, 102, 241, 0.15);
}
.key-tip {
  color: var(--color-success) !important;
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
</style>
