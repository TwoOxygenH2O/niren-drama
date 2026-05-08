<template>
  <div class="layout-root">
    <aside v-if="!hideSidebar" class="sidebar-rail" aria-label="主导航">
      <div class="rail-logo" @click="onRailLogoClick" title="泥人剧场">
        <svg class="rail-logo-svg" width="28" height="28" viewBox="0 0 32 32" fill="none" aria-hidden="true">
          <path
            d="M8 6c0-1.1.9-2 2-2h6a6 6 0 016 6v4a4 4 0 01-4 4h-4a2 2 0 00-2 2v6a2 2 0 01-2 2H6a2 2 0 01-2-2V18c0-2.2 1.8-4 4-4h4a2 2 0 002-2V8a2 2 0 00-2-2H8z"
            stroke="white"
            stroke-width="1.6"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
          <path
            d="M18 14h4a6 6 0 016 6v6a2 2 0 01-2 2h-4a2 2 0 01-2-2v-4a4 4 0 00-4-4h-2"
            stroke="white"
            stroke-width="1.6"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
        </svg>
      </div>

      <div class="rail-center">
        <nav class="rail-floating-nav" aria-label="功能入口">
          <router-link
            to="/dashboard"
            class="rail-icon"
            :class="{ 'rail-icon--active': isNavHome }"
            title="首页"
            @click="onRailHomeClick"
          >
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path d="M3 9l9-7 9 7v11a2 2 0 01-2 2H5a2 2 0 01-2-2z" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
              <polyline points="9 22 9 12 15 12 15 22" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </router-link>
          <router-link
            to="/projects"
            class="rail-icon"
            :class="{ 'rail-icon--active': isNavSpace }"
            title="我的空间"
          >
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path d="M22 19a2 2 0 01-2 2H4a2 2 0 01-2-2V5a2 2 0 012-2h5l2 3h9a2 2 0 012 2z" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </router-link>
          <router-link
            to="/library/subjects"
            class="rail-icon"
            :class="{ 'rail-icon--active': isNavLibrary }"
            title="主体库"
          >
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <circle cx="12" cy="9" r="3.5" stroke="currentColor" stroke-width="1.8" />
              <path d="M5 20v-1a5 5 0 015-5h2a5 5 0 015 5v1" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
              <path d="M18.5 5.5l1.2 1.2M19.7 3.3l1.2 1.2M21 6h1.5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
            </svg>
          </router-link>
          <router-link
            to="/settings"
            class="rail-icon"
            :class="{ 'rail-icon--active': isNavSettings }"
            title="AI配置"
          >
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <circle cx="12" cy="8" r="3" stroke="currentColor" stroke-width="1.8" />
              <path d="M6 20v-1a4 4 0 014-4h0a4 4 0 014 4v1" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
              <path d="M17 11h3M18.5 9.5v3" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
            </svg>
          </router-link>
        </nav>
      </div>

      <div class="rail-bottom">
        <el-dropdown trigger="click" placement="right-start" @command="onUserCommand">
          <div class="rail-user" title="人员信息" role="button" tabindex="0">
            <div class="rail-avatar">{{ userInitial }}</div>
          </div>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item disabled>{{ userDisplayName }}</el-dropdown-item>
              <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>

        <el-popover
          v-model:visible="menuPopoverVisible"
          placement="right-start"
          :width="272"
          trigger="click"
          :show-arrow="false"
          popper-class="rail-more-popover"
          :offset="10"
        >
          <template #reference>
            <button type="button" class="rail-menu-btn" title="菜单" aria-label="菜单">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <line x1="4" y1="8" x2="20" y2="8" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
                <line x1="4" y1="12" x2="20" y2="12" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
                <line x1="4" y1="16" x2="20" y2="16" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
              </svg>
            </button>
          </template>
          <div class="rail-more-list" role="menu">
            <button type="button" class="rail-more-item" role="menuitem" @click="openMore('/help/tutorial')">
              <span class="rail-more-icon" aria-hidden="true">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
                  <path d="M4 6.5a2 2 0 012-2h5v14H6a2 2 0 01-2-2v-10zM13 4.5h5a2 2 0 012 2v10a2 2 0 01-2 2h-5v-14z" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round" />
                  <path d="M9 9h2M9 12h2M15 9h2M15 12h2" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
                </svg>
              </span>
              <span class="rail-more-label">使用教程</span>
            </button>
            <button type="button" class="rail-more-item" role="menuitem" @click="openMore('/help/changelog')">
              <span class="rail-more-icon" aria-hidden="true">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
                  <circle cx="12" cy="12" r="8" stroke="currentColor" stroke-width="1.6" />
                  <path d="M12 16V8M9 11l3-3 3 3" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
              </span>
              <span class="rail-more-label">版本更新</span>
            </button>
            <button type="button" class="rail-more-item" role="menuitem" @click="openMore('/legal/terms')">
              <span class="rail-more-icon" aria-hidden="true">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
                  <rect x="7" y="3" width="10" height="18" rx="2" stroke="currentColor" stroke-width="1.6" />
                  <path d="M10 7h4M10 11h4M10 15h3" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
                </svg>
              </span>
              <span class="rail-more-label">用户协议</span>
            </button>
            <button type="button" class="rail-more-item" role="menuitem" @click="openMore('/legal/privacy')">
              <span class="rail-more-icon" aria-hidden="true">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
                  <path d="M12 3l7 4v5c0 5-3 9-7 11-4-2-7-6-7-11V7l7-4z" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round" />
                  <circle cx="12" cy="11" r="2" stroke="currentColor" stroke-width="1.4" />
                </svg>
              </span>
              <span class="rail-more-label">隐私政策</span>
            </button>
            <button type="button" class="rail-more-item" role="menuitem" @click="openMore('/about')">
              <span class="rail-more-icon" aria-hidden="true">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
                  <circle cx="8" cy="9" r="2.5" stroke="currentColor" stroke-width="1.5" />
                  <circle cx="16" cy="9" r="2.5" stroke="currentColor" stroke-width="1.5" />
                  <path d="M4 20v-1a4 4 0 014-4h1M16 15h1a4 4 0 014 4v1M12 11c1.5 0 3 .5 4 1.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
                </svg>
              </span>
              <span class="rail-more-label">关于我们</span>
            </button>
          </div>
        </el-popover>
      </div>
    </aside>

    <div class="layout-main">
      <header v-if="!isFullBleed" class="topbar">
        <div class="topbar-left">
          <nav class="breadcrumb" aria-label="breadcrumb">
            <span class="bc-item"><router-link to="/dashboard">首页</router-link></span>
            <span v-if="currentTitle" class="bc-sep">/</span>
            <span v-if="currentTitle" class="bc-item bc-active">{{ currentTitle }}</span>
          </nav>
        </div>
        <div class="topbar-right">
          <div class="topbar-badge">
            <span class="badge-dot"></span>
            AI 配置
          </div>
        </div>
      </header>

      <main :class="['page-main', { 'page-main--bleed': isFullBleed }]">
        <router-view />
      </main>
    </div>

  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { DASHBOARD_COLLAPSE_COMPOSER } from '@/constants/dashboard'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const menuPopoverVisible = ref(false)

const currentTitle = computed(() => route.meta?.title as string | undefined)
const isFullBleed = computed(() => route.meta.fullBleed === true)

const userDisplayName = computed(
  () => userStore.userInfo?.nickname || userStore.userInfo?.username || '用户',
)
const userInitial = computed(() => userDisplayName.value.charAt(0).toUpperCase())

const path = computed(() => route.path)

const isNavHome = computed(() => path.value === '/dashboard' || path.value === '/')

const isNavSpace = computed(() => {
  if (!path.value.startsWith('/projects')) return false
  if (/^\/projects\/\d+\/characters/.test(path.value)) return false
  return true
})

const isNavLibrary = computed(() => {
  if (path.value.startsWith('/library')) return true
  if (/^\/projects\/\d+\/characters/.test(path.value)) return true
  return false
})

const isNavSettings = computed(() => path.value.startsWith('/settings'))

const hideSidebar = computed(() => route.meta.hideSidebar === true)

function handleLogout() {
  userStore.logout()
  router.push('/login')
}

function openMore(path: string) {
  menuPopoverVisible.value = false
  router.push(path)
}

function onUserCommand(cmd: string) {
  if (cmd === 'logout') handleLogout()
}

function isOnDashboard() {
  return path.value === '/dashboard' || path.value === '/'
}

function onRailLogoClick() {
  if (isOnDashboard()) {
    window.dispatchEvent(new CustomEvent(DASHBOARD_COLLAPSE_COMPOSER))
    return
  }
  router.push('/dashboard')
}

function onRailHomeClick(e: MouseEvent) {
  if (isOnDashboard()) {
    e.preventDefault()
    window.dispatchEvent(new CustomEvent(DASHBOARD_COLLAPSE_COMPOSER))
  }
}
</script>

<style scoped>
.layout-root {
  display: flex;
  height: 100vh;
  overflow: hidden;
  background: var(--bg-page);
}

/* ——— Narrow rail sidebar ——— */
.sidebar-rail {
  width: 76px;
  min-width: 76px;
  display: flex;
  flex-direction: column;
  align-items: center;
  z-index: 20;
  background: linear-gradient(180deg, #0e0e12 0%, #070708 100%);
  border-right: 1px solid rgba(255, 255, 255, 0.06);
  flex-shrink: 0;
}

.rail-logo {
  padding: 18px 0 12px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.rail-logo-svg {
  opacity: 0.95;
  filter: drop-shadow(0 1px 2px rgba(0, 0, 0, 0.4));
}

.rail-center {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 0;
  width: 100%;
  padding: 8px 0;
}

.rail-floating-nav {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 12px 8px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.08);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.35);
}

.rail-icon {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: rgba(226, 232, 240, 0.85);
  text-decoration: none;
  transition: background 0.18s, color 0.18s, box-shadow 0.18s;
}
.rail-icon:hover {
  background: rgba(255, 255, 255, 0.08);
  color: #fff;
}
.rail-icon--active {
  background: rgba(99, 102, 241, 0.28);
  color: #c7d2fe;
  box-shadow: inset 0 0 0 1px rgba(129, 140, 248, 0.35);
}

.rail-bottom {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 12px 0 20px;
  width: 100%;
}

.rail-user {
  cursor: pointer;
  border-radius: 50%;
  outline: none;
}
.rail-user:focus-visible {
  box-shadow: 0 0 0 2px rgba(129, 140, 248, 0.6);
}

.rail-avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: linear-gradient(145deg, #4f46e5, #7c3aed);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 15px;
  font-weight: 700;
  border: 1px solid rgba(255, 255, 255, 0.12);
}

.rail-menu-btn {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: rgba(255, 255, 255, 0.04);
  color: rgba(226, 232, 240, 0.9);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.18s, border-color 0.18s, color 0.18s;
}
.rail-menu-btn:hover {
  background: rgba(255, 255, 255, 0.1);
  color: #fff;
  border-color: rgba(255, 255, 255, 0.18);
}

/* ——— Main ——— */
.layout-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  overflow: hidden;
}

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
.topbar-left {
  display: flex;
  align-items: center;
  gap: 14px;
}

.breadcrumb {
  display: flex;
  align-items: center;
  gap: 6px;
}
.bc-item {
  font-size: 13px;
  color: var(--text-muted);
}
.bc-item a {
  color: var(--text-muted);
  text-decoration: none;
}
.bc-item a:hover {
  color: var(--primary);
}
.bc-sep {
  color: var(--border-strong);
  font-size: 13px;
}
.bc-active {
  color: var(--text-secondary);
  font-weight: 500;
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: 12px;
}
.topbar-badge {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--text-secondary);
  background: var(--bg-muted);
  border: 1px solid var(--border);
  padding: 4px 10px;
  border-radius: 20px;
  font-weight: 500;
}
.badge-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--text-muted);
}

.page-main {
  flex: 1;
  overflow-y: auto;
}
.page-main--bleed {
  padding: 0;
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  overflow: hidden;
  background: transparent;
}

.rail-more-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.rail-more-item {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 10px 12px;
  border: none;
  border-radius: 999px;
  background: transparent;
  color: rgba(248, 250, 252, 0.96);
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  text-align: left;
  transition: background 0.15s ease;
}
.rail-more-item:hover {
  background: rgba(255, 255, 255, 0.12);
}
.rail-more-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  color: rgba(248, 250, 252, 0.92);
  flex-shrink: 0;
}
.rail-more-label {
  flex: 1;
}
</style>

<style>
/* Popover: frosted panel to the right of the rail menu button */
.rail-more-popover.el-popper.is-light,
.rail-more-popover.el-popper {
  background: rgba(36, 36, 42, 0.94) !important;
  border: 1px solid rgba(255, 255, 255, 0.1) !important;
  border-radius: 18px !important;
  backdrop-filter: blur(18px);
  -webkit-backdrop-filter: blur(18px);
  box-shadow: 0 16px 48px rgba(0, 0, 0, 0.5);
  padding: 10px !important;
}
.rail-more-popover.el-popper .el-popper__arrow::before {
  display: none;
}
</style>
