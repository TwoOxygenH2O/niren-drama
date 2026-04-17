<template>
  <div class="page-container">
    <div class="page-header">
      <span class="page-title">⚙️ AI 配置管理</span>
      <el-button type="primary" :icon="Plus" @click="openDialog()">添加配置</el-button>
    </div>

    <el-tabs v-model="activeTab" type="card">
      <el-tab-pane v-for="tab in tabs" :key="tab.type" :label="tab.label" :name="tab.type">
        <el-table :data="filteredConfigs(tab.type)" style="width: 100%">
          <el-table-column prop="provider" label="服务商" width="140" />
          <el-table-column prop="model" label="模型" width="160" />
          <el-table-column prop="baseUrl" label="Base URL" min-width="200" show-overflow-tooltip />
          <el-table-column prop="isDefault" label="默认" width="80" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.isDefault === 1" type="success" size="small">默认</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <el-button text type="primary" @click="openDialog(row)">编辑</el-button>
              <el-button text type="success" @click="setDefault(row.id)" v-if="row.isDefault !== 1">设为默认</el-button>
              <el-popconfirm title="确认删除？" @confirm="deleteConfig(row.id)">
                <template #reference>
                  <el-button text type="danger">删除</el-button>
                </template>
              </el-popconfirm>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="showDialog" :title="editing ? '编辑配置' : '添加AI配置'" width="560px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="配置类型" required>
          <el-select v-model="form.configType" style="width: 100%">
            <el-option label="文本大模型（剧本/分镜生成）" value="text" />
            <el-option label="文生图（角色/场景/分镜图）" value="image" />
            <el-option label="文生视频" value="video" />
            <el-option label="TTS语音合成" value="tts" />
          </el-select>
        </el-form-item>
        <el-form-item label="服务商" required>
          <el-select v-model="form.provider" style="width: 100%" @change="onProviderChange">
            <el-option label="OpenAI (GPT-4o/DALL-E)" value="openai" />
            <el-option label="火山引擎豆包" value="doubao" />
            <el-option label="阿里通义千问" value="qianwen" />
            <el-option label="百度文心一言" value="wenxin" />
            <el-option label="MiniMax" value="minimax" />
            <el-option label="可灵AI (Kling)" value="kling" />
            <el-option label="即梦AI" value="jimeng" />
            <el-option label="Runway" value="runway" />
            <el-option label="Stable Diffusion" value="sd" />
            <el-option label="自定义" value="custom" />
          </el-select>
        </el-form-item>
        <el-form-item label="Base URL">
          <el-input v-model="form.baseUrl" placeholder="如：https://api.openai.com/v1" />
        </el-form-item>
        <el-form-item label="API Key" required>
          <el-input v-model="form.apiKey" type="password" placeholder="输入API密钥" show-password />
        </el-form-item>
        <el-form-item label="模型名称">
          <el-input v-model="form.model" placeholder="如：gpt-4o / dall-e-3" />
        </el-form-item>
        <el-form-item label="设为默认">
          <el-switch v-model="isDefault" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showDialog = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { aiConfigApi } from '@/api/aiConfig'

const configs = ref<any[]>([])
const showDialog = ref(false)
const editing = ref(false)
const submitting = ref(false)
const activeTab = ref('text')
const isDefault = ref(false)

const tabs = [
  { type: 'text', label: '文本大模型' },
  { type: 'image', label: '文生图' },
  { type: 'video', label: '文生视频' },
  { type: 'tts', label: 'TTS语音' },
]

const providerDefaults: Record<string, { baseUrl: string; model: string }> = {
  openai: { baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o' },
  doubao: { baseUrl: 'https://ark.cn-beijing.volces.com/api/v3', model: 'ep-xxxxxxxxxx' },
  qianwen: { baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen-max' },
  sd: { baseUrl: 'http://localhost:7860', model: 'stable-diffusion-xl' },
}

const form = ref({ id: null as any, configType: 'text', provider: 'openai', baseUrl: '', apiKey: '', model: '' })

const filteredConfigs = (type: string) => configs.value.filter(c => c.configType === type)

function openDialog(row?: any) {
  editing.value = !!row
  if (row) {
    form.value = { ...row }
    isDefault.value = row.isDefault === 1
  } else {
    form.value = { id: null, configType: activeTab.value, provider: 'openai', baseUrl: 'https://api.openai.com/v1', apiKey: '', model: 'gpt-4o' }
    isDefault.value = false
  }
  showDialog.value = true
}

function onProviderChange(val: string) {
  const defaults = providerDefaults[val]
  if (defaults) {
    form.value.baseUrl = defaults.baseUrl
    form.value.model = defaults.model
  }
}

async function load() {
  const res = await aiConfigApi.list()
  configs.value = res.data.data || []
}

async function handleSave() {
  if (!form.value.apiKey) return ElMessage.warning('请输入API Key')
  submitting.value = true
  try {
    await aiConfigApi.save({ ...form.value, isDefault: isDefault.value ? 1 : 0 })
    ElMessage.success('保存成功')
    showDialog.value = false
    load()
  } finally {
    submitting.value = false
  }
}

async function setDefault(id: number) {
  await aiConfigApi.setDefault(id)
  ElMessage.success('设置成功')
  load()
}

async function deleteConfig(id: number) {
  await aiConfigApi.delete(id)
  ElMessage.success('删除成功')
  load()
}

onMounted(load)
</script>

<style scoped>
.page-container { padding: 24px; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 24px; }
.page-title { font-size: 20px; font-weight: 600; }
</style>
