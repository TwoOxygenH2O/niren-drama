<template>
  <div class="page-container">
    <div class="page-header">
      <span class="page-title">项目管理</span>
      <el-button type="primary" :icon="Plus" @click="showCreate = true">新建项目</el-button>
    </div>

    <el-table :data="projects" v-loading="loading" stripe style="width: 100%">
      <el-table-column prop="name" label="项目名称" min-width="160" />
      <el-table-column prop="projectType" label="类型" width="110">
        <template #default="{ row }">{{ formatProjectTypeLabel(row.projectType) }}</template>
      </el-table-column>
      <el-table-column prop="genre" label="题材" width="100">
        <template #default="{ row }">{{ formatGenreLabel(row.genre) || '-' }}</template>
      </el-table-column>
      <el-table-column prop="episodes" label="集数" width="80" align="center" />
      <el-table-column prop="episodeDuration" label="单集时长" width="100" align="center">
        <template #default="{ row }">{{ row.episodeDuration }}s</template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="100" align="center">
        <template #default="{ row }">
          <span :class="`status-badge status-${row.status}`">{{ statusLabel(row.status) }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="创建时间" width="160" />
      <el-table-column label="操作" width="300" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="$router.push(`/projects/${row.id}`)">详情</el-button>
          <el-button link type="primary" @click="$router.push(`/projects/${row.id}/script`)">剧本</el-button>
          <el-button link type="primary" @click="$router.push(`/projects/${row.id}/storyboard`)">分镜</el-button>
          <el-button link type="primary" @click="$router.push(`/projects/${row.id}/synthesis`)">合成</el-button>
          <el-popconfirm title="确认删除？" @confirm="handleDelete(row.id)">
            <template #reference>
              <el-button link type="danger">删除</el-button>
            </template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-if="total > pageSize"
      :total="total"
      :page-size="pageSize"
      :current-page="page"
      layout="prev, pager, next"
      style="margin-top: 20px; justify-content: center"
      @current-change="(p: number) => { page = p; load() }"
    />

    <!-- Create dialog -->
    <el-dialog v-model="showCreate" title="新建项目" width="500px" @close="resetForm">
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
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { projectApi } from '@/api/project'
import { DEFAULT_PROJECT_TYPE, GENRE_OPTIONS, PROJECT_TYPE_OPTIONS, formatGenreLabel, formatProjectTypeLabel } from '@/constants/project'

const projects = ref<any[]>([])
const loading = ref(false)
const showCreate = ref(false)
const submitting = ref(false)
const formRef = ref()
const page = ref(1)
const pageSize = ref(10)
const total = ref(0)

const form = ref({ name: '', projectType: DEFAULT_PROJECT_TYPE, genre: '', episodes: 1, episodeDuration: 180, description: '' })
const rules = {
  name: [{ required: true, message: '请输入项目名称', trigger: 'blur' }],
  projectType: [{ required: true, message: '请选择项目类型', trigger: 'change' }],
  episodes: [{ required: true, message: '请填写集数', trigger: 'blur' }],
  episodeDuration: [{ required: true, message: '请填写时长', trigger: 'blur' }],
}

const statusLabel = (s: string) => ({ draft: '草稿', generating: '生成中', completed: '已完成', failed: '失败' }[s] || s)

async function load() {
  loading.value = true
  try {
    const res = await projectApi.list({ page: page.value, size: pageSize.value })
    const data = res.data.data
    projects.value = data.records || []
    total.value = data.total || 0
  } finally {
    loading.value = false
  }
}

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
  form.value = { name: '', projectType: DEFAULT_PROJECT_TYPE, genre: '', episodes: 1, episodeDuration: 180, description: '' }
  formRef.value?.clearValidate()
}

onMounted(load)
</script>
