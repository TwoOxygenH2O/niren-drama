<template>
  <el-container class="layout-container">
    <!-- Sidebar -->
    <el-aside :width="sidebarWidth" class="sidebar">
      <div class="sidebar-logo">
        <el-icon size="28" color="#6366f1"><VideoCamera /></el-icon>
        <span v-if="!isCollapsed" class="logo-text">倪人短剧</span>
      </div>
      <el-menu
        :default-active="activeMenu"
        :collapse="isCollapsed"
        background-color="#1a1a2e"
        text-color="#94a3b8"
        active-text-color="#6366f1"
        router
      >
        <el-menu-item index="/dashboard">
          <el-icon><HomeFilled /></el-icon>
          <template #title>工作台</template>
        </el-menu-item>
        <el-menu-item index="/projects">
          <el-icon><Film /></el-icon>
          <template #title>项目管理</template>
        </el-menu-item>
        <el-menu-item index="/settings">
          <el-icon><Setting /></el-icon>
          <template #title>AI配置</template>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <!-- Header -->
      <el-header class="header">
        <div class="header-left">
          <el-button
            :icon="isCollapsed ? 'Expand' : 'Fold'"
            text
            @click="isCollapsed = !isCollapsed"
          />
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="{ path: '/' }">首页</el-breadcrumb-item>
            <el-breadcrumb-item v-if="currentTitle">{{ currentTitle }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="header-right">
          <el-dropdown @command="handleCommand">
            <div class="user-info">
              <el-avatar :size="32" :src="userStore.userInfo?.avatar">
                {{ userStore.userInfo?.nickname?.charAt(0) || 'U' }}
              </el-avatar>
              <span class="username">{{ userStore.userInfo?.nickname || userStore.userInfo?.username }}</span>
              <el-icon><ArrowDown /></el-icon>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <!-- Main content -->
      <el-main class="main-content">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const isCollapsed = ref(false)
const sidebarWidth = computed(() => isCollapsed.value ? '64px' : '220px')
const activeMenu = computed(() => {
  const path = route.path
  if (path.startsWith('/projects')) return '/projects'
  return path
})
const currentTitle = computed(() => route.meta?.title as string | undefined)

function handleCommand(cmd: string) {
  if (cmd === 'logout') {
    userStore.logout()
    router.push('/login')
  }
}
</script>

<style scoped>
.layout-container {
  height: 100vh;
  overflow: hidden;
}

.sidebar {
  background: #1a1a2e;
  transition: width 0.3s;
  overflow: hidden;
}

.sidebar-logo {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  border-bottom: 1px solid rgba(255,255,255,0.1);
  padding: 0 16px;
}

.logo-text {
  font-size: 18px;
  font-weight: 700;
  color: #fff;
  white-space: nowrap;
}

.header {
  background: #fff;
  border-bottom: 1px solid #e2e8f0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  height: 60px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.header-right {
  display: flex;
  align-items: center;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}

.username {
  font-size: 14px;
  color: #4a5568;
}

.main-content {
  background: #f5f7fa;
  overflow-y: auto;
  padding: 0;
}

:deep(.el-menu) {
  border-right: none;
}

:deep(.el-menu-item.is-active) {
  background-color: rgba(99, 102, 241, 0.15) !important;
}
</style>
