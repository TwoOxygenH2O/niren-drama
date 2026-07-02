<template>
  <div class="layout-root">
    <aside v-if="!hideSidebar" class="sidebar-rail" aria-label="主导航">
      <button type="button" class="rail-logo" title="泥人剧场" aria-label="泥人剧场首页" @click="onRailLogoClick">
        <img class="rail-logo-svg" src="/favicon.svg" alt="" aria-hidden="true" />
        <span class="rail-logo-text">
          <b>泥人剧场</b>
          <small>短剧生产台</small>
        </span>
      </button>

      <div class="rail-center">
        <nav class="rail-floating-nav" aria-label="功能入口">
          <router-link
            to="/dashboard"
            class="rail-icon"
            :class="{ 'rail-icon--active': isNavHome }"
            title="首页"
            aria-label="首页"
            @click="onRailHomeClick"
          >
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path d="M3 9l9-7 9 7v11a2 2 0 01-2 2H5a2 2 0 01-2-2z" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
              <polyline points="9 22 9 12 15 12 15 22" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
            <span class="rail-label">工作台</span>
          </router-link>
          <router-link
            to="/projects"
            class="rail-icon"
            :class="{ 'rail-icon--active': isNavSpace }"
            title="我的空间"
            aria-label="我的空间"
          >
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path d="M22 19a2 2 0 01-2 2H4a2 2 0 01-2-2V5a2 2 0 012-2h5l2 3h9a2 2 0 012 2z" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
            <span class="rail-label">项目中心</span>
          </router-link>
          <router-link
            to="/library/subjects"
            class="rail-icon"
            :class="{ 'rail-icon--active': isNavLibrary }"
            title="主体库"
            aria-label="主体库"
          >
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <circle cx="12" cy="9" r="3.5" stroke="currentColor" stroke-width="1.8" />
              <path d="M5 20v-1a5 5 0 015-5h2a5 5 0 015 5v1" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
              <path d="M18.5 5.5l1.2 1.2M19.7 3.3l1.2 1.2M21 6h1.5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
            </svg>
            <span class="rail-label">资产矩阵</span>
          </router-link>
          <router-link
            to="/settings"
            class="rail-icon"
            :class="{ 'rail-icon--active': isNavSettings }"
            title="模型配置"
            aria-label="模型配置"
          >
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <circle cx="12" cy="8" r="3" stroke="currentColor" stroke-width="1.8" />
              <path d="M6 20v-1a4 4 0 014-4h0a4 4 0 014 4v1" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
              <path d="M17 11h3M18.5 9.5v3" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
            </svg>
            <span class="rail-label">模型配置</span>
          </router-link>
        </nav>
      </div>

      <div class="rail-bottom">
        <el-dropdown trigger="click" placement="right-start" @command="onUserCommand">
          <div class="rail-user" title="人员信息" aria-label="人员信息" role="button" tabindex="0">
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
            模型配置
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
  background: var(--bg-card);
  border-right: 1px solid var(--border);
  flex-shrink: 0;
}

.rail-logo {
  padding: 18px 0 12px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  border: 0;
  background: transparent;
  color: inherit;
}
.rail-logo-svg {
  width: 32px;
  height: 32px;
  object-fit: contain;
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
  border-radius: var(--radius-full);
  background: var(--bg-muted);
  border: 1px solid var(--border);
  box-shadow: var(--shadow-sm);
}

.rail-icon {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-secondary);
  text-decoration: none;
  transition: background 0.18s, color 0.18s, box-shadow 0.18s;
}
.rail-icon:hover {
  background: var(--bg-card-hover);
  color: var(--text-primary);
}
.rail-icon--active {
  background: var(--primary-glow);
  color: var(--primary);
  box-shadow: inset 0 0 0 1px var(--primary-light);
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
  background: linear-gradient(145deg, var(--primary-dark), var(--secondary-dark));
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
  border-radius: var(--radius-md);
  border: 1px solid var(--border);
  background: var(--bg-muted);
  color: var(--text-secondary);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.18s, border-color 0.18s, color 0.18s;
}
.rail-menu-btn:hover {
  background: var(--bg-card-hover);
  color: var(--text-primary);
  border-color: var(--border-strong);
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
  border-radius: var(--radius-full);
  background: transparent;
  color: var(--text-primary);
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  text-align: left;
  transition: background 0.15s ease;
}
.rail-more-item:hover {
  background: var(--bg-muted);
}
.rail-more-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-secondary);
  flex-shrink: 0;
}
.rail-more-label {
  flex: 1;
}

.sidebar-rail {
  width: var(--sidebar-width);
  min-width: var(--sidebar-width);
  align-items: center;
  background:
    linear-gradient(180deg, rgba(7, 16, 21, 0.92), rgba(5, 9, 13, 0.94)),
    radial-gradient(circle at 18% 8%, rgba(91, 208, 255, 0.12), transparent 26%);
  border-right: 1px solid var(--sidebar-border);
  box-shadow: 18px 0 48px rgba(0, 0, 0, 0.32);
}

.rail-logo {
  width: 100%;
  justify-content: center;
  gap: 0;
  padding: 24px 0 20px;
  color: var(--text-primary);
}

.rail-logo-svg {
  width: 42px;
  height: 42px;
  filter: drop-shadow(0 0 16px rgba(24, 216, 255, 0.28));
}

.rail-logo-text {
  display: none;
}

.rail-logo-text b {
  font-size: 20px;
  letter-spacing: 0;
}

.rail-logo-text small {
  margin-top: 5px;
  color: var(--text-muted);
  font-size: 11px;
  letter-spacing: 0.08em;
}

.rail-center {
  align-items: center;
  justify-content: center;
  padding: 10px 0;
}

.rail-floating-nav {
  width: 56px;
  gap: 8px;
  padding: 0;
  border: 0;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
}

.rail-icon {
  position: relative;
  width: 56px;
  height: 56px;
  justify-content: center;
  gap: 0;
  padding: 0;
  border-radius: 8px;
  color: var(--sidebar-text);
  border: 1px solid transparent;
}

.rail-icon svg {
  width: 22px;
  height: 22px;
  flex: 0 0 auto;
}

.rail-label {
  position: absolute;
  left: calc(100% + 12px);
  top: 50%;
  z-index: 40;
  display: inline-flex;
  align-items: center;
  min-height: 32px;
  padding: 0 11px;
  border: 1px solid rgba(132, 180, 255, 0.22);
  border-radius: 8px;
  background: rgba(5, 9, 19, 0.92);
  box-shadow: 0 14px 34px rgba(0, 0, 0, 0.28);
  color: var(--sidebar-text-hover);
  font-size: 15px;
  font-weight: 650;
  letter-spacing: 0;
  line-height: 1;
  opacity: 0;
  pointer-events: none;
  transform: translate(-2px, -50%);
  transition: opacity 0.15s ease, transform 0.15s ease, visibility 0.15s ease;
  visibility: hidden;
  white-space: nowrap;
}

.rail-icon:hover .rail-label,
.rail-icon:focus .rail-label,
.rail-icon:focus-visible .rail-label {
  opacity: 1;
  transform: translate(0, -50%);
  visibility: visible;
}

.rail-icon:hover {
  background: rgba(255, 255, 255, 0.055);
  color: var(--sidebar-text-hover);
  border-color: rgba(132, 180, 255, 0.12);
}

.rail-icon--active {
  position: relative;
  background: linear-gradient(90deg, rgba(24, 216, 255, 0.18), rgba(139, 92, 246, 0.14));
  color: #ffffff;
  border-color: rgba(24, 216, 255, 0.24);
  box-shadow: 0 14px 34px rgba(0, 0, 0, 0.22);
}

.rail-icon--active::before {
  content: "";
  position: absolute;
  left: 0;
  top: 10px;
  bottom: 10px;
  width: 3px;
  border-radius: 0 999px 999px 0;
  background: var(--primary);
  box-shadow: 0 0 18px rgba(103, 232, 249, 0.72);
}

.rail-bottom {
  padding: 18px 0 22px;
  gap: 10px;
}

.rail-user,
.rail-menu-btn {
  width: 56px;
}

.rail-user {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 10px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.045);
}

.rail-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
}

.rail-menu-btn {
  width: 56px;
  height: 48px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.045);
}

.layout-root {
  background: var(--page-environment);
}

.topbar {
  height: 68px;
  background: rgba(11, 20, 27, 0.68);
  border-bottom: 1px solid var(--border);
  backdrop-filter: blur(28px) saturate(145%);
}

.page-main {
  background: transparent;
}
</style>

<style>
/* Popover: frosted panel to the right of the rail menu button */
.rail-more-popover.el-popper.is-light,
.rail-more-popover.el-popper {
  background: var(--bg-card) !important;
  border: 1px solid var(--border) !important;
  border-radius: var(--radius-lg) !important;
  box-shadow: var(--shadow-lg);
  padding: 10px !important;
}
.rail-more-popover.el-popper .el-popper__arrow::before {
  display: none;
}
</style>
