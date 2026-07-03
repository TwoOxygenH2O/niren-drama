<template>
  <div class="settings-root">
    <div class="settings-inner">
      <!-- Header -->
      <div class="settings-header">
        <div>
          <h1 class="settings-title">模型配置中心</h1>
          <p class="settings-desc">管理文本、图像、视频、语音等智能服务商的接入配置</p>
        </div>
        <el-button type="primary" @click="openDialog()">
          <el-icon><Plus /></el-icon>
          添加配置
        </el-button>
      </div>

      <!-- Tabs -->
      <div class="settings-tabs">
        <button
          v-for="tab in tabs"
          :key="tab.type"
          class="settings-tab"
          :class="{ active: activeTab === tab.type }"
          @click="activeTab = tab.type"
        >
          <span class="settings-tab-icon" v-html="tab.icon"></span>
          <span class="settings-tab-label">{{ tab.label }}</span>
          <span v-if="filteredConfigs(tab.type).length" class="settings-tab-badge">{{ filteredConfigs(tab.type).length }}</span>
        </button>
      </div>

      <!-- Debug Panel (独立 tab) -->
      <section v-if="activeTab === 'debug'" class="debug-section">
        <div class="debug-card">
          <div class="debug-header">
            <div>
              <h3 class="debug-title">文生图 API 调试</h3>
              <p class="debug-desc">使用当前默认文生图配置发起一次测试生成，验证 API 连通性</p>
            </div>
          </div>

          <div class="debug-form">
            <div class="debug-field">
              <label class="debug-label">提示词</label>
              <el-input
                v-model="imageDebugPrompt"
                type="textarea"
                :rows="3"
                placeholder="输入中文或英文画面描述，例如：黄昏时分的江南水乡，青瓦白墙，电影感柔光"
                maxlength="2000"
                show-word-limit
              />
            </div>
            <div class="debug-row">
              <div class="debug-field">
                <label class="debug-label">输出尺寸</label>
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
          </div>

          <p v-if="imageDebugError" class="debug-error">{{ imageDebugError }}</p>

          <div v-if="imageDebugResultUrl" class="debug-result">
            <div class="debug-result-head">
              <span>预览</span>
              <el-button text type="primary" size="small" @click="copyImageDebugUrl">复制 URL</el-button>
            </div>
            <img :src="imageDebugResultUrl" alt="调试预览" class="debug-img" loading="lazy" decoding="async" />
            <p class="debug-url">{{ imageDebugResultUrl }}</p>
          </div>
        </div>

        <div class="debug-card">
          <div class="debug-header">
            <div>
              <h3 class="debug-title">图生视频 API 调试</h3>
              <p class="debug-desc">使用当前默认视频配置，将图片 URL 和运动提示词提交到图生视频工作流</p>
            </div>
          </div>

          <div class="debug-form">
            <div class="debug-field">
              <label class="debug-label">图片 URL</label>
              <div class="debug-inline-input">
                <el-input v-model="imageToVideoImageUrl" placeholder="输入可访问的图片 URL，或先生成一张图片后点击右侧按钮" />
                <el-button :disabled="!imageDebugResultUrl" @click="useGeneratedImageForVideo">使用上方图片</el-button>
              </div>
            </div>
            <div class="debug-field">
              <label class="debug-label">辅助参考图 URL</label>
              <el-input
                v-model="imageToVideoReferenceUrls"
                type="textarea"
                :rows="2"
                placeholder="可选。人物定妆图、场景图等，每行一个 URL；用于锁定角色、服装和场景一致性"
              />
            </div>
            <div class="debug-field">
              <label class="debug-label">视频提示词</label>
              <el-input
                v-model="imageToVideoPrompt"
                type="textarea"
                :rows="3"
                placeholder="描述镜头运动和画面变化，例如：镜头缓慢推进，水面微微荡漾，人物轻轻回头"
                maxlength="2000"
                show-word-limit
              />
            </div>
            <div class="debug-row">
              <div class="debug-field">
                <label class="debug-label">时长</label>
                <el-select v-model="imageToVideoDuration" style="width: 140px">
                  <el-option label="5 秒" :value="5" />
                  <el-option label="8 秒" :value="8" />
                  <el-option label="10 秒" :value="10" />
                </el-select>
              </div>
              <div class="debug-field">
                <label class="debug-label">分辨率</label>
                <el-select v-model="imageToVideoResolution" style="width: 160px">
                  <el-option label="720×1280（竖屏）" value="720x1280" />
                  <el-option label="1024×576（横屏）" value="1024x576" />
                  <el-option label="1024×1024（方形）" value="1024x1024" />
                </el-select>
              </div>
              <div class="debug-field">
                <label class="debug-label">质量</label>
                <el-select v-model="imageToVideoQuality" style="width: 140px">
                  <el-option label="标准" value="standard" />
                  <el-option label="高质量" value="high" />
                </el-select>
              </div>
              <div class="debug-field debug-switch-field">
                <label class="debug-label">声音</label>
                <el-switch v-model="imageToVideoWithSound" active-text="带声音" inactive-text="无声音" />
              </div>
              <el-button type="primary" :loading="imageToVideoLoading" @click="runImageToVideoDebug">
                生成视频
              </el-button>
            </div>
          </div>

          <p v-if="imageToVideoError" class="debug-error">{{ imageToVideoError }}</p>

          <div v-if="imageToVideoResultUrl" class="debug-result">
            <div class="debug-result-head">
              <span>视频预览</span>
              <el-button text type="primary" size="small" @click="copyImageToVideoUrl">复制 URL</el-button>
            </div>
            <video :src="imageToVideoResultUrl" class="debug-video" controls playsinline />
            <p class="debug-url">{{ imageToVideoResultUrl }}</p>
          </div>
        </div>
      </section>

      <section v-if="activeTab === 'video'" class="preset-section">
        <div class="preset-header">
          <div>
            <h3>视频工作流预设</h3>
            <p>普通生产使用预设，复杂调参再进入专家模式。</p>
          </div>
        </div>
        <div class="preset-grid">
          <button
            v-for="preset in videoPresets"
            :key="preset.id"
            type="button"
            class="preset-card"
            @click="openVideoPreset(preset.id)"
          >
            <span>{{ preset.title }}</span>
            <b>{{ preset.meta }}</b>
            <small>{{ preset.desc }}</small>
          </button>
        </div>
      </section>

      <!-- Config List -->
      <div v-if="activeTab !== 'debug'" class="config-list">
        <!-- Empty State -->
        <div v-if="filteredConfigs(activeTab).length === 0" class="empty-state">
          <div class="empty-icon">
            <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <circle cx="12" cy="12" r="10" />
              <line x1="12" y1="8" x2="12" y2="12" />
              <line x1="12" y1="16" x2="12.01" y2="16" />
            </svg>
          </div>
          <p class="empty-title">暂未配置{{ currentTabLabel }}服务</p>
          <p class="empty-hint">点击「添加配置」接入您的智能服务商</p>
        </div>

        <!-- Config Cards -->
        <div
          v-for="config in filteredConfigs(activeTab)"
          :key="config.id"
          class="config-card"
          :class="{ 'is-default': config.isDefault === 1 }"
        >
          <div class="config-card-left">
            <div class="config-provider-icon" :class="providerIconClass(config.provider)">
              {{ providerInitial(config.provider) }}
            </div>
            <div class="config-info">
              <div class="config-name-row">
                <span class="config-provider-name">{{ providerLabel(config.provider, config.configType) }}</span>
                <span v-if="config.isDefault === 1" class="config-default-badge">默认</span>
              </div>
              <div class="config-meta">
                <span class="config-model">{{ config.model || '-' }}</span>
                <span class="config-sep">·</span>
                <span class="config-url" :title="config.baseUrl">{{ config.baseUrl || '-' }}</span>
              </div>
              <div class="config-key">
                <span class="config-key-label">API Key</span>
                <span class="config-key-value">{{ maskKey(config.apiKey) }}</span>
              </div>
            </div>
          </div>
          <div class="config-card-right">
            <el-button
              v-if="canTrainConfig(config)"
              text
              size="small"
              type="success"
              @click="openTrainingDialog(config)"
            >
              <el-icon><Upload /></el-icon> 训练
            </el-button>
            <el-button text size="small" type="primary" @click="openDialog(config)">
              <el-icon><Edit /></el-icon> 编辑
            </el-button>
            <el-button
              v-if="config.isDefault !== 1"
              text size="small"
              @click="setDefault(config.id)"
            >
              设为默认
            </el-button>
            <el-popconfirm title="确认删除此配置？" @confirm="deleteConfig(config.id)">
              <template #reference>
                <el-button text size="small" type="danger">
                  <el-icon><Delete /></el-icon>
                </el-button>
              </template>
            </el-popconfirm>
          </div>
        </div>
      </div>

      <!-- Add/Edit Dialog -->
      <el-dialog
        v-model="showDialog"
        :title="editing ? '编辑模型配置' : '添加模型配置'"
        width="580px"
        :close-on-click-modal="false"
        class="config-dialog"
      >
        <el-form :model="form" label-width="80px" class="config-form">
          <el-form-item label="类型" required>
            <div class="type-chips">
              <button
                v-for="t in typeOptions"
                :key="t.value"
                type="button"
                class="type-chip"
                :class="{ active: form.configType === t.value }"
                @click="form.configType = t.value; onTypeChange()"
              >
                <span class="type-chip-icon" v-html="t.icon"></span>
                {{ t.label }}
              </button>
            </div>
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
            <p v-if="currentProviderHint" class="form-hint provider-hint">
              <el-icon><InfoFilled /></el-icon> {{ currentProviderHint }}
            </p>
          </el-form-item>

          <el-form-item label="Base URL">
            <el-input v-model="form.baseUrl" placeholder="选择服务商后自动填充">
              <template #append>
                <el-button @click="resetBaseUrl" title="重置默认">
                  <el-icon><RefreshRight /></el-icon>
                </el-button>
              </template>
            </el-input>
          </el-form-item>

          <el-form-item v-if="form.provider !== 'comfyui'" label="API Key" required>
            <el-input
              v-model="form.apiKey"
              type="password"
              :placeholder="editing ? '留空则保持已保存密钥' : '输入 API 密钥'"
              show-password
            />
            <p class="form-hint">
              <el-icon><Lock /></el-icon>
              密钥加密存储，不会对外暴露
            </p>
          </el-form-item>

          <el-form-item v-if="form.provider !== 'comfyui'" label="模型">
            <el-select
              v-model="form.model"
              filterable
              allow-create
              default-first-option
              clearable
              style="width: 100%"
              :placeholder="getModelPlaceholder()"
            >
              <el-option v-for="m in modelOptions" :key="m" :label="m" :value="m" />
            </el-select>
          </el-form-item>

          <el-form-item v-if="form.provider === 'comfyui'" label="工作流">
            <div class="workflow-row">
              <el-select
                v-model="selectedComfyUiWorkflow"
                placeholder="选择工作流（留空使用默认）"
                clearable
                filterable
                style="flex: 1"
                :loading="comfyuiWorkflowLoading"
              >
                <el-option v-for="wf in comfyuiWorkflowList" :key="wf" :label="wf" :value="wf" />
              </el-select>
              <el-button @click="fetchComfyUiWorkflows" :loading="comfyuiWorkflowLoading">
                <el-icon><RefreshRight /></el-icon>
              </el-button>
            </div>
            <el-collapse v-model="showAdvancedExtra" class="extra-collapse">
              <el-collapse-item title="高级：手动 JSON" name="advanced">
                <el-input v-model="form.extra" type="textarea" :rows="3" placeholder='自定义 ComfyUI 工作流 JSON' />
              </el-collapse-item>
            </el-collapse>
          </el-form-item>

          <el-form-item label="默认">
            <el-switch v-model="isDefault" />
            <span class="switch-hint">作为{{ currentTabLabel }}的默认服务</span>
          </el-form-item>
        </el-form>

        <template #footer>
          <el-button @click="showDialog = false">取消</el-button>
          <el-button type="primary" :loading="submitting" @click="handleSave">
            {{ editing ? '保存' : '添加' }}
          </el-button>
        </template>
      </el-dialog>

      <!-- Wan2.2 LoRA Training Dialog -->
      <el-dialog
        v-model="showTrainingDialog"
        title="训练 Wan2.2 LoRA"
        width="720px"
        :close-on-click-modal="false"
        class="training-dialog"
      >
        <div v-if="trainingConfig" class="training-panel">
          <div class="training-target">
            <div>
              <span class="training-eyebrow">训练目标</span>
              <h3>{{ providerLabel(trainingConfig.provider, trainingConfig.configType) }}</h3>
              <p>{{ trainingWorkflowLabel(trainingConfig) }}</p>
            </div>
            <span class="training-status" :class="{ warn: !isWanTrainingConfig(trainingConfig) }">
              {{ isWanTrainingConfig(trainingConfig) ? 'Wan2.2 I2V' : '需切换 Wan2.2' }}
            </span>
          </div>

          <el-form label-position="top" class="training-form">
            <div class="training-grid">
              <el-form-item label="训练名称">
                <el-input v-model="trainingForm.runName" placeholder="例如：女主办公室连续性 v1" />
              </el-form-item>
              <el-form-item label="LoRA Rank">
                <el-input-number v-model="trainingForm.loraRank" :min="4" :max="64" :step="4" controls-position="right" />
              </el-form-item>
              <el-form-item label="Epochs">
                <el-input-number v-model="trainingForm.epochs" :min="1" :max="50" controls-position="right" />
              </el-form-item>
              <el-form-item label="低显存模式">
                <el-switch v-model="trainingForm.lowVram" active-text="启用" inactive-text="关闭" />
              </el-form-item>
              <el-form-item label="提示词主题">
                <el-input v-model="trainingForm.promptTheme" placeholder="女频 复仇 古代" />
              </el-form-item>
              <el-form-item label="提示词类型">
                <el-input v-model="trainingForm.promptGenre" placeholder="追妻火葬场" />
              </el-form-item>
              <el-form-item label="提示词数量">
                <el-input-number v-model="trainingForm.promptCount" :min="4" :max="60" controls-position="right" />
              </el-form-item>
            </div>

            <el-form-item label="统一训练描述">
              <el-input
                v-model="trainingForm.caption"
                type="textarea"
                :rows="3"
                maxlength="1000"
                show-word-limit
                placeholder="描述这批素材共同强化的短剧能力，例如：同一演员身份、服装、办公室场景和灯光保持稳定，单镜头连续运动。"
              />
            </el-form-item>

            <el-form-item label="外站视频 Prompt 配对 JSON（可选）">
              <el-input
                v-model="trainingForm.samplePromptsJson"
                type="textarea"
                :rows="5"
                maxlength="6000"
                show-word-limit
                placeholder='[{"filename":"shot01.mp4","prompt":"Ancient revenge heroine turns back under palace lanterns, real body turn and sleeve motion.","negativePrompt":"no slideshow, no identity drift"}]'
              />
              <div class="training-prompt-actions">
                <el-button :icon="RefreshRight" :loading="trainingPromptPackLoading" @click="buildTrainingPromptPack">
                  生成外站提示词包
                </el-button>
              </div>
            </el-form-item>

            <el-form-item label="训练视频素材">
              <el-upload
                v-model:file-list="trainingFiles"
                class="training-upload"
                drag
                multiple
                :auto-upload="false"
                :limit="20"
                accept="video/*"
              >
                <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
                <div class="el-upload__text">拖入视频素材，或点击选择文件</div>
                <template #tip>
                  <div class="training-upload-tip">系统会自动抽取首帧并生成 Wan2.2 I2V manifest；建议先用 10-20 条授权样本做小跑。</div>
                </template>
              </el-upload>
            </el-form-item>

            <el-checkbox v-model="trainingForm.licenseConfirmed" class="training-license">
              我确认上传素材已获得训练授权，并且不会用于克隆未授权真人身份。
            </el-checkbox>
          </el-form>

          <div v-if="trainingTask" class="training-task">
            <div class="training-task-head">
              <span>{{ trainingTask.message || '训练任务已提交' }}</span>
              <b>{{ trainingTask.progress || 0 }}%</b>
            </div>
            <el-progress :percentage="trainingTask.progress || 0" :status="trainingProgressStatus" />
            <p v-if="trainingTask.result" class="training-task-result">{{ trainingResultSummary }}</p>
          </div>
        </div>

        <template #footer>
          <el-button @click="closeTrainingDialog">关闭</el-button>
          <el-button type="success" :loading="trainingSubmitting" :disabled="isTrainingActive" @click="submitTraining">
            提交训练
          </el-button>
        </template>
      </el-dialog>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import type { UploadUserFile } from 'element-plus'
import { Plus, Edit, Delete, RefreshRight, InfoFilled, Lock, Upload, UploadFilled } from '@element-plus/icons-vue'
import { aiConfigApi } from '../../api/aiConfig'
import { taskApi } from '../../api/task'

const configs = ref<any[]>([])
const showDialog = ref(false)
const editing = ref(false)
const submitting = ref(false)
const activeTab = ref('text')
const isDefault = ref(false)

const showTrainingDialog = ref(false)
const trainingConfig = ref<any | null>(null)
const trainingFiles = ref<UploadUserFile[]>([])
const trainingSubmitting = ref(false)
const trainingPromptPackLoading = ref(false)
const trainingTask = ref<any | null>(null)
let trainingPollTimer: number | undefined

const trainingForm = ref({
  runName: '',
  caption: 'Keep the same actor identity, face, hairstyle, outfit, scene layout and lighting from the first frame. One continuous vertical short-drama shot with natural motion and no cuts.',
  samplePromptsJson: '',
  promptTheme: '女频 复仇 古代',
  promptGenre: '追妻火葬场',
  promptCount: 20,
  loraRank: 8,
  epochs: 1,
  lowVram: true,
  licenseConfirmed: false,
})

const imageDebugPrompt = ref('黄昏时分的江南水乡，青瓦白墙，电影感柔光，竖屏短剧分镜')
const imageDebugSize = ref('1024x1792')
const imageDebugLoading = ref(false)
const imageDebugResultUrl = ref('')
const imageDebugProviderUrl = ref('')
const imageDebugError = ref('')

const imageToVideoImageUrl = ref('')
const imageToVideoReferenceUrls = ref('')
const imageToVideoPrompt = ref('镜头缓慢推进，画面有轻微动态，电影感光影')
const imageToVideoDuration = ref(5)
const imageToVideoResolution = ref('720x1280')
const imageToVideoQuality = ref('standard')
const imageToVideoWithSound = ref(false)
const imageToVideoLoading = ref(false)
const imageToVideoResultUrl = ref('')
const imageToVideoProviderUrl = ref('')
const imageToVideoError = ref('')

const comfyuiWorkflowList = ref<string[]>([])
const comfyuiWorkflowLoading = ref(false)
const selectedComfyUiWorkflow = ref('')
const showAdvancedExtra = ref<string[]>([])

const tabs = [
  { type: 'text', label: '文本模型 LLM', icon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/></svg>' },
  { type: 'image', label: '图像工作流 ComfyUI', icon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/></svg>' },
  { type: 'video', label: '视频引擎 Wan2.2', icon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="23 7 16 12 23 17 23 7"/><rect x="1" y="5" width="15" height="14" rx="2"/></svg>' },
  { type: 'tts', label: '语音合成 TTS', icon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 1a3 3 0 00-3 3v8a3 3 0 006 0V4a3 3 0 00-3-3z"/><path d="M19 10v2a7 7 0 01-14 0v-2"/><line x1="12" y1="19" x2="12" y2="23"/></svg>' },
  { type: 'debug', label: '诊断调试', icon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 22c5.523 0 10-4.477 10-10S17.523 2 12 2 2 6.477 2 12s4.477 10 10 10z"/><path d="M12 6v6l4 2"/></svg>' },
]

const typeOptions = [
  { value: 'text', label: '文本', icon: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>' },
  { value: 'image', label: '图像', icon: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/></svg>' },
  { value: 'video', label: '视频', icon: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="23 7 16 12 23 17 23 7"/><rect x="1" y="5" width="15" height="14" rx="2"/></svg>' },
  { value: 'tts', label: '语音', icon: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 1a3 3 0 00-3 3v8a3 3 0 006 0V4a3 3 0 00-3-3z"/></svg>' },
]

const videoPresets = [
  {
    id: 'ltx',
    title: '快测 LTX',
    meta: '720P · 5-8 秒 · 预览',
    desc: '用于快速验证镜头节奏和对白衔接。',
  },
  {
    id: 'wan',
    title: '高质 Wan2.2',
    meta: '主首帧 · 发布质量',
    desc: '用于需要更强一致性的备用发布生成。',
  },
  {
    id: 'hunyuan',
    title: '高质 Hunyuan',
    meta: '720P · 6 秒 · 表演',
    desc: '用于正式短剧镜头，优先避免动图化静帧。',
  },
  {
    id: 'expert',
    title: '专家模式',
    meta: '自定义 workflow',
    desc: '保留完整 JSON 和工作流选择能力。',
  },
]

const allProviders = [
  { value: 'deepseek', label: 'DeepSeek', tagLabel: '推荐', types: ['text'] },
  { value: 'openai', label: 'OpenAI (GPT)', tagLabel: '文本', types: ['text'] },
  { value: 'qianwen', label: '阿里通义千问', tagLabel: '文本', types: ['text'] },
  { value: 'doubao', label: '火山引擎豆包', tagLabel: '文本', types: ['text'] },
  { value: 'moonshot', label: 'Moonshot', tagLabel: '文本', types: ['text'] },
  { value: 'zhipu', label: '智谱 GLM', tagLabel: '文本', types: ['text'] },
  { value: 'baichuan', label: '百川智能', tagLabel: '文本', types: ['text'] },
  { value: 'wenxin', label: '百度文心', tagLabel: '文本', types: ['text'] },
  { value: 'minimax', label: 'MiniMax', tagLabel: '文本', types: ['text'] },
  { value: 'aliyun', label: '阿里云千问生图', tagLabel: '推荐', types: ['image'] },
  { value: 'comfyui', label: 'ComfyUI (本地)', tagLabel: '推荐', types: ['image'] },
  { value: 'aliyun', label: '阿里云万相视频', tagLabel: '推荐', types: ['video'] },
  { value: 'comfyui', label: 'ComfyUI (本地)', tagLabel: '推荐', types: ['video'] },
  { value: 'aliyun', label: '阿里云 Qwen TTS', tagLabel: '推荐', types: ['tts'] },
  { value: 'custom', label: '自定义接口', tagLabel: '通用', types: ['text', 'image', 'video', 'tts'] },
]

const providerHints: Record<string, string> = {
  'image:aliyun': 'DashScope 原生接口，支持 qwen-image-2.0 / qwen-image-2.0-pro。',
  'video:aliyun': 'DashScope 视频合成接口，当前支持 wan2.6-t2v。',
  'tts:aliyun': 'DashScope TTS 接口，推荐 qwen3-tts-instruct-flash。',
  'image:comfyui': '本地 ComfyUI，Base URL 默认 http://localhost:8188，无需 API Key。',
  'video:comfyui': '本地 ComfyUI 视频生成，需安装 AnimateDiff 等节点。',
  'image:custom': '填写真实的图片生成端点。',
  'video:custom': '填写真实的视频生成端点。',
  'tts:custom': '按 OpenAI 兼容 speech 接口调用。',
}

const filteredProviderGroups = computed(() => {
  const type = form.value.configType
  const byType = allProviders.filter(p => p.types.includes(type))
  const groups: { label: string; providers: typeof byType }[] = []
  const recommended = byType.filter(p => p.tagLabel === '推荐')
  if (recommended.length) groups.push({ label: '推荐', providers: recommended })
  const rest = byType.filter(p => p.tagLabel !== '推荐' && p.value !== 'custom')
  if (rest.length) groups.push({ label: '全部', providers: rest })
  const custom = byType.filter(p => p.value === 'custom')
  if (custom.length) groups.push({ label: '其他', providers: custom })
  return groups
})

const currentProviderHint = computed(() => providerHints[`${form.value.configType}:${form.value.provider}`] || '')

type ProviderBaseUrlValue = string | Partial<Record<string, string>>

const providerBaseUrls: Record<string, ProviderBaseUrlValue> = {
  openai: 'https://api.openai.com/v1',
  deepseek: 'https://api.deepseek.com',
  qianwen: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
  aliyun: { image: 'https://dashscope.aliyuncs.com/api/v1', video: 'https://dashscope.aliyuncs.com/api/v1', tts: 'https://dashscope.aliyuncs.com/api/v1' },
  doubao: 'https://ark.cn-beijing.volces.com/api/v3',
  minimax: 'https://api.minimax.chat/v1',
  moonshot: 'https://api.moonshot.cn/v1',
  zhipu: 'https://open.bigmodel.cn/api/paas/v4',
  baichuan: 'https://api.baichuan-ai.com/v1',
  wenxin: 'https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop',
  custom: { image: '', video: '', tts: '', text: 'https://api.openai.com/v1' },
  comfyui: { image: 'http://localhost:8188', video: 'http://localhost:8188' },
}

const providerModels: Record<string, Record<string, string>> = {
  openai: { text: 'gpt-4o', image: 'dall-e-3', tts: 'tts-1' },
  deepseek: { text: 'deepseek-chat' },
  qianwen: { text: 'qwen-plus', image: 'qwen-image-2.0-pro' },
  aliyun: { image: 'qwen-image-2.0-pro', video: 'wan2.6-t2v', tts: 'qwen3-tts-instruct-flash' },
  custom: { image: '', video: '', tts: '', text: 'gpt-4o' },
  comfyui: { image: '', video: '' },
  doubao: { text: 'doubao-pro-32k' },
  minimax: { text: 'abab6.5s-chat', tts: 'speech-01-turbo' },
  moonshot: { text: 'moonshot-v1-8k' },
  zhipu: { text: 'glm-4' },
  baichuan: { text: 'Baichuan4' },
  wenxin: { text: 'ernie-4.0-8k' },
}

const form = ref({ id: null as any, configType: 'text', provider: 'deepseek', baseUrl: '', apiKey: '', model: '', extra: '' })

const commonModelOptions: Record<string, string[]> = {
  text: ['gpt-4o', 'gpt-4.1', 'deepseek-chat', 'qwen-plus', 'glm-4'],
  image: ['qwen-image-2.0', 'qwen-image-2.0-pro'],
  video: ['wan2.6-t2v', 'hunyuanvideo1.5_720p_i2v_fp16.safetensors', 'wan2.2_i2v_high_noise_14B_fp8_scaled.safetensors', 'ltx-2-19b-distilled.safetensors'],
  tts: ['qwen3-tts-instruct-flash', 'qwen3-tts-flash', 'qwen-tts-latest'],
}

const currentTabLabel = computed(() => tabs.find(t => t.type === activeTab.value)?.label || '')

const isTrainingActive = computed(() => trainingTask.value?.status === 'PENDING' || trainingTask.value?.status === 'RUNNING')

const trainingProgressStatus = computed(() => {
  if (trainingTask.value?.status === 'SUCCESS') return 'success'
  if (trainingTask.value?.status === 'FAILED') return 'exception'
  return undefined
})

const trainingResultSummary = computed(() => {
  const raw = trainingTask.value?.result
  if (!raw) return ''
  try {
    const result = typeof raw === 'string' ? JSON.parse(raw) : raw
    if (result.errorMessage) return result.errorMessage
    if (result.outputPath) return `LoRA: ${result.outputPath}`
    if (result.manifestPath) return `Manifest: ${result.manifestPath}`
  } catch {
    return String(raw)
  }
  return ''
})

const modelOptions = computed(() => {
  const providerDefault = providerModels[form.value.provider]?.[form.value.configType]
  const currentValue = form.value.model
  return Array.from(new Set([providerDefault, ...(commonModelOptions[form.value.configType] || []), currentValue].filter(Boolean)))
})

const filteredConfigs = (type: string) => configs.value.filter(c => c.configType === type)

function resolveProviderDefaultUrl(provider: string, configType: string): string {
  const defaults = providerBaseUrls[provider]
  if (!defaults) return ''
  if (typeof defaults === 'string') return defaults
  return defaults[configType] || ''
}

function getModelPlaceholder(): string {
  const model = providerModels[form.value.provider]?.[form.value.configType]
  return model ? `推荐: ${model}` : '输入模型名称'
}

function providerLabel(provider: string, configType = activeTab.value): string {
  const p = allProviders.find(p => p.value === provider && p.types.includes(configType))
  return p ? p.label : provider
}

function providerInitial(provider: string): string {
  const map: Record<string, string> = {
    deepseek: 'D', openai: 'O', qianwen: 'Q', doubao: 'B', moonshot: 'M',
    zhipu: 'Z', baichuan: 'C', wenxin: 'W', minimax: 'X', aliyun: 'A',
    comfyui: 'F', custom: '?',
  }
  return map[provider] || '?'
}

function providerIconClass(provider: string): string {
  return `pi-${provider}`
}

function normalizeProvider(provider: string, configType: string): string {
  if (configType === 'text') return provider
  if (['aliyun', 'qianwen', 'dashscope', 'wanx', 'cosyvoice'].includes(provider)) return 'aliyun'
  if (provider === 'comfyui') return 'comfyui'
  return 'custom'
}

function normalizeConfig(row: any) {
  return { ...row, provider: normalizeProvider(row.provider, row.configType) }
}

function maskKey(key: string): string {
  if (!key) return '-'
  if (key.length <= 8) return '••••••••'
  return key.substring(0, 4) + '••••••••' + key.substring(key.length - 4)
}

function openDialog(row?: any) {
  editing.value = !!row
  showAdvancedExtra.value = []
  if (row) {
    form.value = { ...normalizeConfig(row), apiKey: '', extra: row.extra || '' }
    isDefault.value = row.isDefault === 1
    selectedComfyUiWorkflow.value = parseWorkflowFromExtra(row.extra || '')
  } else {
    const defaultProvider = activeTab.value === 'text' ? 'deepseek' :
      activeTab.value === 'image' ? 'aliyun' :
      activeTab.value === 'video' ? 'comfyui' : 'aliyun'
    form.value = {
      id: null, configType: activeTab.value, provider: defaultProvider,
      baseUrl: resolveProviderDefaultUrl(defaultProvider, activeTab.value),
      apiKey: '', model: providerModels[defaultProvider]?.[activeTab.value] || '', extra: '',
    }
    isDefault.value = filteredConfigs(activeTab.value).length === 0
    selectedComfyUiWorkflow.value = ''
  }
  if (form.value.provider === 'comfyui') fetchComfyUiWorkflows()
  showDialog.value = true
}

function openVideoPreset(preset: string) {
  activeTab.value = 'video'
  if (preset === 'expert') {
    openDialog()
    showAdvancedExtra.value = ['advanced']
    return
  }

  const isWan = preset === 'wan'
  const isHunyuan = preset === 'hunyuan'
  editing.value = false
  showAdvancedExtra.value = []
  const workflowExtra = isHunyuan
    ? { workflowFile: 'video_hunyuan_video_1.5_720p_i2v.json', qualityMode: 'hunyuan15-i2v-720p', maxFrames: 49, maxSteps: 12, maxReferenceImages: 1 }
    : isWan
      ? { workflowFile: 'video_wan2_2_14B_i2v_series_balanced.json', qualityMode: 'wan22-series-balanced', maxFrames: 33, maxSteps: 12, maxReferenceImages: 1, patchWanControlnetStrength: true, bypassWanReferenceEmbeds: false }
      : { workflowFile: 'video_ltx2_i2v_short_drama_consistency.json', maxReferenceImages: 1 }
  form.value = {
    id: null,
    configType: 'video',
    provider: 'comfyui',
    baseUrl: resolveProviderDefaultUrl('comfyui', 'video'),
    apiKey: '',
    model: isHunyuan ? 'hunyuanvideo1.5_720p_i2v_fp16.safetensors'
      : isWan ? 'wan2.2_i2v_high_noise_14B_fp8_scaled.safetensors'
        : 'ltx-2-19b-distilled.safetensors',
    extra: JSON.stringify(workflowExtra),
  }
  selectedComfyUiWorkflow.value = String(workflowExtra.workflowFile || '')
  isDefault.value = true
  fetchComfyUiWorkflows()
  showDialog.value = true
}

function onTypeChange() {
  const type = form.value.configType
  const defaultProvider = type === 'text' ? 'deepseek' : type === 'video' ? 'comfyui' : 'aliyun'
  form.value.provider = defaultProvider
  void onProviderChange(defaultProvider)
}

async function onProviderChange(val: string) {
  form.value.baseUrl = resolveProviderDefaultUrl(val, form.value.configType)
  form.value.model = providerModels[val]?.[form.value.configType] || ''
  selectedComfyUiWorkflow.value = ''
  if (val === 'comfyui') fetchComfyUiWorkflows()
  try {
    const res = await aiConfigApi.getProviderDefaults(val, form.value.configType)
    const defaults = res?.data?.data || {}
    if (defaults.baseUrl) form.value.baseUrl = defaults.baseUrl
    if (defaults.model) form.value.model = defaults.model
  } catch { /* keep local fallback */ }
}

function resetBaseUrl() {
  form.value.baseUrl = resolveProviderDefaultUrl(form.value.provider, form.value.configType)
}

async function load() {
  const res = await aiConfigApi.list()
  configs.value = (res.data.data || []).map((row: any) => normalizeConfig(row))
}

async function handleSave() {
  if (form.value.provider !== 'comfyui' && !editing.value && !form.value.apiKey) return ElMessage.warning('请输入 API Key')
  if (!form.value.provider) return ElMessage.warning('请选择服务商')
  submitting.value = true
  try {
    let extraToSave = form.value.extra
    if (form.value.provider === 'comfyui') {
      extraToSave = buildExtraWithWorkflow(selectedComfyUiWorkflow.value, form.value.extra)
    }
    await aiConfigApi.save({ ...form.value, extra: extraToSave, isDefault: isDefault.value ? 1 : 0 })
    ElMessage.success('配置已保存')
    showDialog.value = false
    load()
  } finally { submitting.value = false }
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

function canTrainConfig(config: any): boolean {
  return activeTab.value === 'video' && config?.configType === 'video' && config?.provider === 'comfyui'
}

function isWanTrainingConfig(config: any): boolean {
  const text = `${config?.model || ''} ${parseWorkflowFromExtra(config?.extra || '')}`.toLowerCase()
  return text.includes('wan2.2') || text.includes('wan2_2')
}

function trainingWorkflowLabel(config: any): string {
  const workflow = parseWorkflowFromExtra(config?.extra || '')
  return workflow || config?.model || '默认 ComfyUI 工作流'
}

function openTrainingDialog(config: any) {
  trainingConfig.value = config
  trainingFiles.value = []
  trainingTask.value = null
  trainingForm.value = {
    runName: `${trainingWorkflowLabel(config).replace(/\.[^.]+$/, '')} LoRA`,
    caption: 'Keep the same actor identity, face, hairstyle, outfit, scene layout and lighting from the first frame. One continuous vertical short-drama shot with natural motion and no cuts.',
    samplePromptsJson: '',
    promptTheme: '女频 复仇 古代',
    promptGenre: '追妻火葬场',
    promptCount: 20,
    loraRank: 8,
    epochs: 1,
    lowVram: true,
    licenseConfirmed: false,
  }
  showTrainingDialog.value = true
}

function closeTrainingDialog() {
  showTrainingDialog.value = false
}

async function buildTrainingPromptPack() {
  const config = trainingConfig.value
  if (!config?.id || trainingPromptPackLoading.value) return
  trainingPromptPackLoading.value = true
  try {
    const res = await aiConfigApi.buildWan22PromptPack(config.id, {
      theme: trainingForm.value.promptTheme,
      genre: trainingForm.value.promptGenre,
      count: trainingForm.value.promptCount,
    })
    const data = res.data?.data
    trainingForm.value.samplePromptsJson = data?.samplePromptsJson || ''
    ElMessage.success('提示词包已生成')
  } catch (error: any) {
    ElMessage.error(error?.message || '提示词包生成失败')
  } finally {
    trainingPromptPackLoading.value = false
  }
}

async function submitTraining() {
  const config = trainingConfig.value
  if (!config?.id) return
  if (!isWanTrainingConfig(config)) return ElMessage.warning('请先选择 Wan2.2 视频工作流后再训练')
  if (!trainingForm.value.licenseConfirmed) return ElMessage.warning('请先确认素材训练授权')
  const rawFiles = trainingFiles.value.map(file => file.raw).filter(Boolean) as File[]
  if (!rawFiles.length) return ElMessage.warning('请上传至少一个视频素材')

  const formData = new FormData()
  rawFiles.forEach(file => formData.append('files', file))
  formData.append('caption', trainingForm.value.caption.trim())
  formData.append('licenseConfirmed', String(trainingForm.value.licenseConfirmed))
  formData.append('runName', trainingForm.value.runName.trim())
  formData.append('loraRank', String(trainingForm.value.loraRank))
  formData.append('epochs', String(trainingForm.value.epochs))
  formData.append('lowVram', String(trainingForm.value.lowVram))
  if (trainingForm.value.samplePromptsJson.trim()) {
    formData.append('samplePromptsJson', trainingForm.value.samplePromptsJson.trim())
  }

  trainingSubmitting.value = true
  try {
    const res = await aiConfigApi.trainWan22Lora(config.id, formData)
    trainingTask.value = res.data?.data || null
    ElMessage.success('训练任务已提交')
    if (trainingTask.value?.id) startTrainingPolling(trainingTask.value.id)
  } finally {
    trainingSubmitting.value = false
  }
}

function startTrainingPolling(taskId: number | string) {
  stopTrainingPolling()
  const poll = async () => {
    try {
      const res = await taskApi.get(taskId)
      trainingTask.value = res.data?.data || trainingTask.value
      if (trainingTask.value?.status === 'SUCCESS') {
        ElMessage.success('Wan2.2 LoRA 训练完成')
        stopTrainingPolling()
        return
      }
      if (trainingTask.value?.status === 'FAILED') {
        stopTrainingPolling()
        return
      }
    } catch {
      // Keep polling; transient backend reloads are common during local development.
    }
    trainingPollTimer = window.setTimeout(poll, 3000)
  }
  trainingPollTimer = window.setTimeout(poll, 1000)
}

function stopTrainingPolling() {
  if (trainingPollTimer) {
    window.clearTimeout(trainingPollTimer)
    trainingPollTimer = undefined
  }
}

async function fetchComfyUiWorkflows() {
  comfyuiWorkflowLoading.value = true
  try {
    const res = await aiConfigApi.listComfyUiWorkflows()
    comfyuiWorkflowList.value = res.data?.data || []
  } catch { comfyuiWorkflowList.value = [] }
  finally { comfyuiWorkflowLoading.value = false }
}

function parseWorkflowFromExtra(extra: string): string {
  if (!extra) return ''
  try { return JSON.parse(extra).workflowFile || '' } catch { return '' }
}

function buildExtraWithWorkflow(workflowFile: string, existingExtra: string): string {
  let obj: any = {}
  if (existingExtra) try { obj = JSON.parse(existingExtra) } catch { /* */ }
  if (workflowFile) obj.workflowFile = workflowFile; else delete obj.workflowFile
  return Object.keys(obj).length > 0 ? JSON.stringify(obj) : ''
}

async function runImageDebug() {
  if (!imageDebugPrompt.value.trim()) return ElMessage.warning('请输入提示词')
  imageDebugLoading.value = true
  imageDebugError.value = ''
  imageDebugResultUrl.value = ''
  imageDebugProviderUrl.value = ''
  try {
    const res = await aiConfigApi.debugGenerateImage({ prompt: imageDebugPrompt.value.trim(), size: imageDebugSize.value })
    const d = (res.data as any)?.data || {}
    imageDebugResultUrl.value = d.imageUrl || ''
    imageDebugProviderUrl.value = d.providerUrl || ''
    if (!imageDebugResultUrl.value) { imageDebugError.value = '接口未返回图片地址'; return }
    ElMessage.success('生成完成')
  } catch (e: any) { imageDebugError.value = e?.message || '生成失败' }
  finally { imageDebugLoading.value = false }
}

async function copyImageDebugUrl() {
  if (!imageDebugResultUrl.value) return
  try { await navigator.clipboard.writeText(imageDebugResultUrl.value); ElMessage.success('已复制') }
  catch { ElMessage.warning('复制失败') }
}

function useGeneratedImageForVideo() {
  if (!imageDebugResultUrl.value) return
  imageToVideoImageUrl.value = imageDebugResultUrl.value
}

async function runImageToVideoDebug() {
  if (!imageToVideoImageUrl.value.trim()) return ElMessage.warning('请输入图片 URL')
  imageToVideoLoading.value = true
  imageToVideoError.value = ''
  imageToVideoResultUrl.value = ''
  imageToVideoProviderUrl.value = ''
  try {
    const res = await aiConfigApi.debugGenerateImageToVideo({
      imageUrl: imageToVideoImageUrl.value.trim(),
      referenceImageUrls: imageToVideoReferenceUrls.value
        .split(/\r?\n|,/)
        .map((url) => url.trim())
        .filter(Boolean),
      prompt: imageToVideoPrompt.value.trim(),
      duration: imageToVideoDuration.value,
      resolution: imageToVideoResolution.value,
      quality: imageToVideoQuality.value,
      withSound: imageToVideoWithSound.value,
    })
    const d = (res.data as any)?.data || {}
    imageToVideoResultUrl.value = d.videoUrl || ''
    imageToVideoProviderUrl.value = d.providerUrl || ''
    if (!imageToVideoResultUrl.value) { imageToVideoError.value = '接口未返回视频地址'; return }
    ElMessage.success('视频生成完成')
  } catch (e: any) { imageToVideoError.value = e?.message || '生成失败' }
  finally { imageToVideoLoading.value = false }
}

async function copyImageToVideoUrl() {
  if (!imageToVideoResultUrl.value) return
  try { await navigator.clipboard.writeText(imageToVideoResultUrl.value); ElMessage.success('已复制') }
  catch { ElMessage.warning('复制失败') }
}

onMounted(load)
onBeforeUnmount(stopTrainingPolling)
</script>

<style scoped>
.settings-root {
  display: flex;
  flex: 1;
  min-height: 0;
  width: 100%;
  box-sizing: border-box;
  padding: 32px 40px 40px 112px;
  overflow-x: hidden;
  overflow-y: auto;
  background: var(--page-environment);
  color: var(--text-primary);
}
.settings-inner {
  max-width: 960px;
  margin: 0 auto;
  width: 100%;
}

/* Header */
.settings-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 28px;
}
.settings-title {
  font-size: 32px;
  font-weight: 700;
  margin: 0 0 4px;
  letter-spacing: -0.02em;
  line-height: 1.25;
}
.settings-desc {
  font-size: 13px;
  color: var(--text-muted);
  margin: 0;
}
.settings-header :deep(.el-button--primary) {
  border-radius: var(--radius-full);
  border: none;
  padding: 10px 20px;
  font-weight: 600;
}

/* Tabs */
.settings-tabs {
  display: flex;
  gap: 2px;
  margin-bottom: 24px;
  border-bottom: 1px solid var(--border);
  padding-bottom: 0;
}
.settings-tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 10px 16px;
  border: none;
  background: transparent;
  color: var(--text-secondary);
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
  transition: color 0.15s, border-color 0.15s;
}
.settings-tab:hover {
  color: var(--text-primary);
}
.settings-tab.active {
  color: var(--primary-light);
  border-bottom-color: var(--primary);
}
.settings-tab-icon {
  display: flex;
  align-items: center;
  opacity: 0.7;
}
.settings-tab.active .settings-tab-icon {
  opacity: 1;
}
.settings-tab-badge {
  font-size: 11px;
  font-weight: 600;
  padding: 1px 6px;
  border-radius: 999px;
  background: var(--bg-muted);
  color: var(--text-muted);
}
.settings-tab.active .settings-tab-badge {
  background: var(--primary-glow);
  color: var(--primary-light);
}

.preset-section {
  margin: 0 0 18px;
  padding: 18px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg-card);
}

.preset-header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.preset-header h3 {
  margin: 0 0 4px;
  font-size: 16px;
}

.preset-header p {
  margin: 0;
  color: var(--text-muted);
  font-size: 12px;
}

.preset-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.preset-card {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  min-height: 104px;
  padding: 14px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg-muted);
  color: var(--text-primary);
  cursor: pointer;
  text-align: left;
}

.preset-card:hover {
  border-color: var(--primary);
}

.preset-card span {
  font-size: 14px;
  font-weight: 700;
}

.preset-card b {
  margin-top: 8px;
  font-size: 12px;
  color: var(--primary-light);
}

.preset-card small {
  margin-top: 8px;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.45;
}

/* Config List */
.config-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

/* Config Card */
.config-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 20px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  transition: border-color 0.15s, box-shadow 0.15s;
}
.config-card:hover {
  border-color: var(--primary-light);
  box-shadow: var(--shadow-md);
}
.config-card.is-default {
  border-color: rgba(16, 185, 129, 0.4);
  background: rgba(16, 185, 129, 0.06);
}

.config-card-left {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
  flex: 1;
}

.config-provider-icon {
  width: 40px;
  height: 40px;
  border-radius: var(--radius-md);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  font-weight: 700;
  flex-shrink: 0;
  background: var(--primary-glow);
  color: var(--primary-light);
}
.pi-deepseek { background: rgba(59, 130, 246, 0.15); color: #60a5fa; }
.pi-openai { background: rgba(16, 163, 127, 0.15); color: #34d399; }
.pi-qianwen, .pi-aliyun { background: rgba(251, 146, 60, 0.15); color: #fb923c; }
.pi-doubao { background: rgba(99, 102, 241, 0.15); color: #818cf8; }
.pi-comfyui { background: rgba(139, 124, 255, 0.14); color: var(--secondary-light); }
.pi-custom { background: var(--bg-muted); color: var(--text-muted); }

.config-info {
  min-width: 0;
}
.config-name-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}
.config-provider-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}
.config-default-badge {
  font-size: 10px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 999px;
  background: rgba(16, 185, 129, 0.15);
  color: #4ade80;
  letter-spacing: 0.02em;
}
.config-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--text-muted);
  margin-bottom: 2px;
}
.config-model {
  font-family: 'SF Mono', 'Fira Code', monospace;
  color: var(--text-secondary);
}
.config-sep {
  opacity: 0.4;
}
.config-url {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 280px;
}
.config-key {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
}
.config-key-label {
  color: var(--text-muted);
}
.config-key-value {
  font-family: 'SF Mono', 'Fira Code', monospace;
  color: var(--text-secondary);
  font-size: 11px;
}

.config-card-right {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}

/* Empty State */
.empty-state {
  text-align: center;
  padding: 60px 24px;
  background: var(--bg-muted);
  border: 1px dashed var(--border-strong);
  border-radius: var(--radius-lg);
}
.empty-icon { color: var(--text-muted); margin-bottom: 16px; }
.empty-title { font-size: 15px; font-weight: 500; color: var(--text-secondary); margin: 0 0 6px; }
.empty-hint { font-size: 13px; color: var(--text-muted); margin: 0; }

/* Debug Section */
.debug-section {
  display: flex;
  flex-direction: column;
  gap: 18px;
  margin-top: 4px;
  padding-bottom: 32px;
}
.debug-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 24px;
}
.debug-header { margin-bottom: 20px; }
.debug-title { font-size: 16px; font-weight: 700; margin: 0 0 4px; }
.debug-desc { font-size: 13px; color: var(--text-muted); margin: 0; }

.debug-form { display: flex; flex-direction: column; gap: 16px; }
.debug-field { display: flex; flex-direction: column; gap: 6px; }
.debug-label { font-size: 12px; font-weight: 600; color: var(--text-secondary); }
.debug-row { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.debug-inline-input { display: flex; gap: 8px; align-items: center; }
.debug-inline-input :deep(.el-input) { flex: 1; }
.debug-switch-field { min-height: 54px; justify-content: space-between; }

.debug-error { margin: 12px 0 0; font-size: 13px; color: var(--color-danger); }

.debug-result { margin-top: 20px; padding-top: 16px; border-top: 1px solid var(--border); }
.debug-result-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; font-size: 13px; font-weight: 600; color: var(--text-secondary); }
.debug-img { display: block; max-width: min(100%, 400px); max-height: 70vh; object-fit: contain; border-radius: var(--radius-md); border: 1px solid var(--border); }
.debug-video { display: block; width: min(100%, 520px); max-height: 70vh; border-radius: var(--radius-md); border: 1px solid var(--border); background: #000; }
.debug-url { margin: 10px 0 0; font-size: 11px; color: var(--text-muted); word-break: break-all; font-family: 'SF Mono', 'Fira Code', monospace; }

/* Dialog */
.config-form .form-hint {
  font-size: 11px;
  color: var(--text-muted);
  margin: 4px 0 0;
  display: flex;
  align-items: center;
  gap: 4px;
  line-height: 1.5;
}
.config-form .form-hint .el-icon { font-size: 12px; }
.provider-hint {
  color: var(--primary-light) !important;
  background: var(--primary-glow);
  padding: 6px 10px;
  border-radius: var(--radius-sm);
}

.type-chips {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.type-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  border-radius: var(--radius-full);
  border: 1px solid var(--border);
  background: transparent;
  color: var(--text-secondary);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s;
}
.type-chip:hover {
  border-color: var(--primary-light);
  color: var(--text-primary);
}
.type-chip.active {
  background: var(--primary-glow);
  border-color: var(--primary);
  color: var(--primary-light);
}
.type-chip-icon {
  display: flex;
  align-items: center;
}

.switch-hint {
  font-size: 12px;
  color: var(--text-muted);
  margin-left: 8px;
}

.provider-option {
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;
}
.provider-option-name { font-size: 13px; }
.provider-option-tag {
  font-size: 10px;
  color: var(--text-muted);
  background: var(--bg-muted);
  padding: 1px 6px;
  border-radius: 4px;
  border: 1px solid var(--border);
}

.workflow-row {
  display: flex;
  gap: 8px;
  width: 100%;
}
.extra-collapse {
  margin-top: 8px;
  border: none;
}
.extra-collapse :deep(.el-collapse-item__header) {
  background: transparent;
  border: none;
  color: var(--text-muted);
  font-size: 12px;
  height: 28px;
  line-height: 28px;
}
.extra-collapse :deep(.el-collapse-item__wrap) {
  background: transparent;
  border: none;
}

.training-panel {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

:global(.el-dialog.training-dialog),
:global(.training-dialog .el-dialog) {
  margin: 32px auto !important;
  max-height: calc(100vh - 64px);
  display: flex;
  flex-direction: column;
}

:global(.el-dialog.training-dialog .el-dialog__body),
:global(.training-dialog .el-dialog__body) {
  overflow-y: auto;
}

:global(.el-dialog.training-dialog .el-dialog__footer),
:global(.training-dialog .el-dialog__footer) {
  flex-shrink: 0;
  border-top: 1px solid var(--border);
  padding-top: 14px;
}

.training-target {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  padding: 16px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg-muted);
}

.training-target h3 {
  margin: 4px 0 4px;
  font-size: 16px;
  line-height: 1.3;
}

.training-target p {
  margin: 0;
  color: var(--text-muted);
  font-size: 12px;
  word-break: break-all;
}

.training-eyebrow {
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 700;
}

.training-status {
  flex-shrink: 0;
  padding: 4px 10px;
  border-radius: 999px;
  background: rgba(16, 185, 129, 0.14);
  color: #4ade80;
  font-size: 11px;
  font-weight: 700;
}

.training-status.warn {
  background: rgba(245, 158, 11, 0.16);
  color: #fbbf24;
}

.training-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) 120px 120px 150px;
  gap: 12px;
  align-items: start;
}

.training-form :deep(.el-form-item) {
  margin-bottom: 14px;
}

.training-upload {
  width: 100%;
}

.training-upload :deep(.el-upload) {
  width: 100%;
}

.training-upload :deep(.el-upload-dragger) {
  width: 100%;
  border-radius: 8px;
  background: var(--bg-muted);
  border-color: var(--border);
}

.training-upload-tip {
  margin-top: 6px;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.5;
}

.training-prompt-actions {
  display: flex;
  justify-content: flex-end;
  width: 100%;
  margin-top: 10px;
}

.training-license {
  margin-top: 2px;
  white-space: normal;
}

.training-task {
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg-muted);
}

.training-task-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
  color: var(--text-secondary);
  font-size: 13px;
}

.training-task-head b {
  color: var(--text-primary);
}

.training-task-result {
  margin: 10px 0 0;
  color: var(--text-muted);
  font-size: 11px;
  word-break: break-all;
  font-family: 'SF Mono', 'Fira Code', monospace;
}

@media (max-width: 760px) {
  .training-grid {
    grid-template-columns: 1fr 1fr;
  }

  .training-target {
    flex-direction: column;
  }
}
.settings-root {
  min-height: 100%;
  overflow: auto;
  background: var(--page-environment);
  color: #f7fbff;
}

.settings-inner {
  width: min(1500px, calc(100vw - var(--sidebar-width) - 52px));
  margin: 0 auto;
  padding: 26px 28px 46px;
}

.settings-header {
  align-items: flex-start;
  padding-bottom: 20px;
  border-bottom: 1px solid rgba(150, 190, 255, 0.13);
}

.settings-title {
  color: #f7fbff;
  font-size: 30px;
  letter-spacing: 0;
}

.settings-desc {
  color: #b9c4d6;
}

.settings-header::after {
  content: "● 全系统运行正常";
  margin-left: auto;
  align-self: center;
  color: var(--primary);
  text-shadow: 0 0 18px rgba(103, 232, 249, 0.46);
  font-size: 14px;
}

.settings-tabs {
  margin-top: 22px;
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 0;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--glass-fill);
  backdrop-filter: blur(var(--glass-blur)) saturate(145%);
}

.settings-tab {
  min-height: 76px;
  justify-content: center;
  gap: 12px;
  border: 0;
  border-right: 1px solid rgba(150, 190, 255, 0.12);
  border-radius: 0;
  background: transparent;
  color: #aab5c8;
}

.settings-tab:last-child {
  border-right: 0;
}

.settings-tab.active {
  color: var(--primary);
  background:
    linear-gradient(180deg, rgba(103, 232, 249, 0.13), rgba(103, 232, 249, 0.035)),
    rgba(255, 255, 255, 0.035);
  box-shadow: inset 0 2px 0 var(--primary);
}

.settings-tab-icon {
  color: currentColor;
}

.settings-tab-label {
  font-size: 16px;
  font-weight: 800;
}

.settings-tab-badge {
  background: rgba(139, 92, 246, 0.22);
  color: #d8ccff;
}

.config-list,
.debug-section,
.preset-section {
  margin-top: 14px;
}

.config-card,
.debug-card,
.preset-card,
.empty-state {
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--surface-panel);
  box-shadow: var(--shadow-md), inset 0 1px 0 rgba(255, 255, 255, 0.055);
  backdrop-filter: blur(var(--glass-blur)) saturate(145%);
}

.config-card.is-default {
  border-color: rgba(103, 232, 249, 0.28);
  box-shadow: var(--shadow-lg), 0 0 0 1px rgba(103, 232, 249, 0.1) inset;
}

.config-provider-icon,
.empty-icon {
  background: rgba(103, 232, 249, 0.1);
  color: var(--primary);
  border: 1px solid rgba(103, 232, 249, 0.22);
  box-shadow: 0 0 24px rgba(103, 232, 249, 0.12);
}

.config-provider-name,
.debug-title,
.preset-card span,
.empty-title {
  color: #f7fbff;
}

.config-meta,
.debug-desc,
.preset-card small,
.empty-hint {
  color: #9aa8bd;
}

.config-default-badge {
  background: rgba(103, 232, 249, 0.11);
  color: var(--primary);
  border-color: rgba(103, 232, 249, 0.22);
}

.debug-label,
.dialog-label {
  color: #dbe8ff;
}

.debug-row,
.debug-form,
.dialog-grid {
  color: #dbe8ff;
}

.settings-root :deep(.el-input__wrapper),
.settings-root :deep(.el-textarea__inner),
.settings-root :deep(.el-select__wrapper) {
  border-radius: 8px;
  background: rgba(3, 7, 15, 0.54) !important;
  box-shadow: 0 0 0 1px rgba(150, 190, 255, 0.18) inset !important;
}

.settings-root :deep(.el-input__inner),
.settings-root :deep(.el-textarea__inner) {
  color: #f7fbff;
}

.settings-root :deep(.el-button--primary) {
  border: 0;
  background: linear-gradient(100deg, #f7fbff, var(--primary), var(--secondary));
  color: #03101d;
  box-shadow: var(--shadow-primary);
}

@media (max-width: 1100px) {
  .settings-inner {
    width: 100%;
  }

  .settings-tabs {
    grid-template-columns: 1fr;
  }

  .settings-tab {
    border-right: 0;
    border-bottom: 1px solid rgba(150, 190, 255, 0.12);
  }
}
</style>
