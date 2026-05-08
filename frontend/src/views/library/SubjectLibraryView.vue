<template>
  <div class="page-container subject-library">
    <div class="page-header">
      <span class="page-title">主体库</span>
      <span class="page-sub">选择项目，进入该项目的角色与主体管理</span>
    </div>

    <el-row v-loading="loading" :gutter="16">
      <el-col v-for="p in projects" :key="p.id" :xs="24" :sm="12" :md="8" :lg="6">
        <el-card shadow="hover" class="project-card" @click="goCharacters(p.id)">
          <div class="card-title">{{ p.name }}</div>
          <div class="card-meta">{{ p.description || '—' }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-empty v-if="!loading && projects.length === 0" description="暂无项目，请先在「我的空间」创建项目" />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { projectApi } from '@/api/project'

const router = useRouter()
const loading = ref(true)
const projects = ref<any[]>([])

onMounted(async () => {
  loading.value = true
  try {
    const res = await projectApi.list({ page: 1, size: 100 })
    const data = res.data?.data
    projects.value = data?.records || []
  } finally {
    loading.value = false
  }
})

function goCharacters(id: number) {
  router.push(`/projects/${id}/characters`)
}
</script>

<style scoped>
.page-sub {
  display: block;
  font-size: 13px;
  color: var(--text-muted);
  margin-top: 6px;
  font-weight: 400;
}
.project-card {
  cursor: pointer;
  margin-bottom: 16px;
  transition: transform 0.15s ease;
}
.project-card:hover {
  transform: translateY(-2px);
}
.card-title {
  font-weight: 600;
  font-size: 15px;
  margin-bottom: 8px;
}
.card-meta {
  font-size: 12px;
  color: var(--text-muted);
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
</style>
