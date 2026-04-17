<template>
  <div class="layout-root">
    <!-- Sidebar -->
    <aside :class="['sidebar', { collapsed: isCollapsed }]">
      <!-- Logo -->
      <div class="sidebar-logo" @click="$router.push('/dashboard')">
        <div class="logo-icon">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none">
            <path d="M15 10l4.553-2.843A1 1 0 0121 8.117v7.766a1 1 0 01-1.447.894L15 14M3 8a2 2 0 012-2h8a2 2 0 012 2v8a2 2 0 01-2 2H5a2 2 0 01-2-2V8z" stroke="white" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </div>
        <span v-if="!isCollapsed" class="logo-text">泥人短剧</span>
      </div>

      <!-- Nav -->
      <nav class="sidebar-nav">
        <div class="nav-section-label" v-if="!isCollapsed">主功能</div>
        <router-link to="/dashboard" class="nav-item" :class="{ active: activeMenu === '/dashboard' }">
          <span class="nav-icon">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none"><path d="M3 9l9-7 9 7v11a2 2 0 01-2 2H5a2 2 0 01-2-2z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/><polyline points="9 22 9 12 15 12 15 22" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </span>
          <span v-if="!isCollapsed" class="nav-label">工作台</span>
        </router-link>
        <router-link to="/projects" class="nav-item" :class="{ active: activeMenu === '/projects' }">
          <span class="nav-icon">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none"><rect x="2" y="7" width="20" height="14" rx="2" ry="2" stroke="currentColor" stroke-width="2"/><path d="M16 7V5a2 2 0 00-2-2h-4a2 2 0 00-2 2v2" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </span>
          <span v-if="!isCollapsed" class="nav-label">项目管理</span>
        </router-link>
        <div class="nav-divider" />
        <div class="nav-section-label" v-if="!isCollapsed">系统</div>
        <router-link to="/settings" class="nav-item" :class="{ active: activeMenu === '/settings' }">
          <span class="nav-icon">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none"><circle cx="12" cy="12" r="3" stroke="currentColor" stroke-width="2"/><path d="M19.4 15a1.65 1.65 0 00.33 1.82l.06.06a2 2 0 010 2.83 2 2 0 01-2.83 0l-.06-.06a1.65 1.65 0 00-1.82-.33 1.65 1.65 0 00-1 1.51V21a2 2 0 01-4 0v-.09A1.65 1.65 0 009 19.4a1.65 1.65 0 00-1.82.33l-.06.06a2 2 0 01-2.83-2.83l.06-.06A1.65 1.65 0 004.68 15a1.65 1.65 0 00-1.51-1H3a2 2 0 010-4h.09A1.65 1.65 0 004.6 9a1.65 1.65 0 00-.33-1.82l-.06-.06a2 2 0 012.83-2.83l.06.06A1.65 1.65 0 009 4.68a1.65 1.65 0 001-1.51V3a2 2 0 014 0v.09a1.65 1.65 0 001 1.51 1.65 1.65 0 001.82-.33l.06-.06a2 2 0 012.83 2.83l-.06.06A1.65 1.65 0 0019.4 9a1.65 1.65 0 001.51 1H21a2 2 0 010 4h-.09a1.65 1.65 0 00-1.51 1z" stroke="currentColor" stroke-width="2"/></svg>
          </span>
          <span v-if="!isCollapsed" class="nav-label">AI 配置</span>
        </router-link>
      </nav>

      <!-- User area -->
      <div class="sidebar-user" v-if="!isCollapsed">
        <div class="user-avatar-letter">{{ (userStore.userInfo?.nickname || 'U').charAt(0).toUpperCase() }}</div>
        <div class="user-meta">
          <div class="user-name">{{ userStore.userInfo?.nickname || userStore.userInfo?.username }}</div>
          <div class="user-role">{{ userStore.isAdmin ? '管理员' : '创作者' }}</div>
        </div>
        <button class="logout-btn" @click="handleLogout" title="退出登录">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none"><path d="M9 21H5a2 2 0 01-2-2V5a2 2 0 012-2h4M16 17l5-5-5-5M21 12H9" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </button>
      </div>
    </aside>

    <!-- Main -->
    <div class="layout-main">
      <!-- Header -->
      <header class="topbar">
        <div class="topbar-left">
          <button class="collapse-btn" @click="isCollapsed = !isCollapsed">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none"><line x1="3" y1="12" x2="21" y2="12" stroke="currentColor" stroke-width="2" stroke-linecap="round"/><line x1="3" y1="6" x2="21" y2="6" stroke="currentColor" stroke-width="2" stroke-linecap="round"/><line x1="3" y1="18" x2="21" y2="18" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>
          </button>
          <nav class="breadcrumb" aria-label="breadcrumb">
            <span class="bc-item"><router-link to="/dashboard">首页</router-link></span>
            <span v-if="currentTitle" class="bc-sep">/</span>
            <span v-if="currentTitle" class="bc-item bc-active">{{ currentTitle }}</span>
          </nav>
        </div>
        <div class="topbar-right">
          <div class="topbar-badge">
            <span class="badge-dot"></span>
            AI 就绪
          </div>
        </div>
      </header>

      <!-- Content -->
      <main class="page-main">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const isCollapsed = ref(false)
const activeMenu = computed(() => {
  const path = route.path
  if (path.startsWith('/projects')) return '/projects'
  return path
})
const currentTitle = computed(() => route.meta?.title as string | undefined)

function handleLogout() {
  userStore.logout()
  router.push('/login')
}
</script>

<style scoped>
/* ——— Root layout ——— */
.layout-root {
  display: flex;
  height: 100vh;
  overflow: hidden;
  background: var(--bg-page);
}

/* ——— Sidebar ——— */
.sidebar {
  width: 224px;
  min-width: 224px;
  background: var(--sidebar-bg);
  display: flex;
  flex-direction: column;
  transition: width 0.28s cubic-bezier(.4,0,.2,1), min-width 0.28s;
  border-right: 1px solid var(--sidebar-border);
  position: relative;
  z-index: 20;
}
.sidebar.collapsed {
  width: 64px;
  min-width: 64px;
}

/* Logo */
.sidebar-logo {
  height: 64px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 18px;
  cursor: pointer;
  border-bottom: 1px solid var(--sidebar-border);
  flex-shrink: 0;
}
.logo-icon {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: linear-gradient(135deg, #6366f1, #7c3aed);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  box-shadow: 0 4px 12px rgba(99,102,241,0.4);
}
.logo-text {
  font-size: 17px;
  font-weight: 700;
  color: #fff;
  white-space: nowrap;
  letter-spacing: -0.3px;
}

/* Nav */
.sidebar-nav {
  flex: 1;
  padding: 16px 10px;
  overflow-y: auto;
  overflow-x: hidden;
}
.nav-section-label {
  font-size: 10px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.8px;
  color: rgba(148,163,184,0.5);
  padding: 0 10px 8px;
  margin-top: 4px;
}
.nav-divider {
  height: 1px;
  background: var(--sidebar-border);
  margin: 12px 10px;
}
.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 10px;
  border-radius: 8px;
  color: var(--sidebar-text);
  text-decoration: none;
  font-size: 13.5px;
  font-weight: 500;
  margin-bottom: 2px;
  transition: background 0.18s, color 0.18s;
  white-space: nowrap;
  overflow: hidden;
}
.nav-item:hover {
  background: rgba(255,255,255,0.06);
  color: var(--sidebar-text-hover);
}
.nav-item.active {
  background: rgba(99,102,241,0.18);
  color: #818cf8;
}
.nav-icon {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  border-radius: 6px;
  transition: background 0.18s;
}
.nav-item.active .nav-icon {
  background: rgba(99,102,241,0.25);
}
.nav-label { flex: 1; }

/* User area */
.sidebar-user {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  border-top: 1px solid var(--sidebar-border);
  flex-shrink: 0;
}
.user-avatar-letter {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: linear-gradient(135deg, #6366f1, #8b5cf6);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  font-weight: 700;
  flex-shrink: 0;
}
.user-meta { flex: 1; min-width: 0; }
.user-name { font-size: 13px; font-weight: 600; color: #e2e8f0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.user-role { font-size: 11px; color: rgba(148,163,184,0.7); margin-top: 1px; }
.logout-btn {
  background: none;
  border: none;
  cursor: pointer;
  color: rgba(148,163,184,0.6);
  padding: 4px;
  border-radius: 6px;
  display: flex;
  align-items: center;
  transition: color 0.18s, background 0.18s;
}
.logout-btn:hover { color: #f87171; background: rgba(248,113,113,0.1); }

/* ——— Main area ——— */
.layout-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  overflow: hidden;
}

/* Topbar */
.topbar {
  height: 60px;
  background: var(--bg-card);
  border-bottom: 1px solid var(--border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  flex-shrink: 0;
  box-shadow: var(--shadow-sm);
}
.topbar-left { display: flex; align-items: center; gap: 14px; }
.collapse-btn {
  background: none;
  border: none;
  cursor: pointer;
  color: var(--text-secondary);
  padding: 6px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  transition: background 0.18s, color 0.18s;
}
.collapse-btn:hover { background: var(--bg-muted); color: var(--text-primary); }

.breadcrumb { display: flex; align-items: center; gap: 6px; }
.bc-item { font-size: 13px; color: var(--text-muted); }
.bc-item a { color: var(--text-muted); text-decoration: none; }
.bc-item a:hover { color: var(--primary); }
.bc-sep { color: var(--border-strong); font-size: 13px; }
.bc-active { color: var(--text-secondary); font-weight: 500; }

.topbar-right { display: flex; align-items: center; gap: 12px; }
.topbar-badge {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--color-success);
  background: rgba(16,185,129,0.08);
  border: 1px solid rgba(16,185,129,0.2);
  padding: 4px 10px;
  border-radius: 20px;
  font-weight: 500;
}
.badge-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-success);
  box-shadow: 0 0 6px rgba(16,185,129,0.6);
  animation: pulse 2s infinite;
}
@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}

/* Page content */
.page-main {
  flex: 1;
  overflow-y: auto;
}
</style>
