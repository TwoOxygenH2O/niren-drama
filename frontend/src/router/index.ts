import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'

function isJwtExpired(token?: string): boolean {
  if (!token) return true
  const parts = token.split('.')
  if (parts.length !== 3) return true
  try {
    const base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    const normalized = base64.padEnd(Math.ceil(base64.length / 4) * 4, '=')
    const payload = JSON.parse(atob(normalized))
    const exp = Number(payload?.exp)
    if (!Number.isFinite(exp) || exp <= 0) return true
    return Date.now() >= exp * 1000
  } catch {
    return true
  }
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'Login',
      component: () => import('@/views/auth/LoginView.vue'),
      meta: { requiresAuth: false },
    },
    {
      path: '/register',
      name: 'Register',
      component: () => import('@/views/auth/RegisterView.vue'),
      meta: { requiresAuth: false },
    },
    {
      path: '/',
      component: () => import('@/components/layout/MainLayout.vue'),
      meta: { requiresAuth: true },
      children: [
        {
          path: '',
          redirect: '/dashboard',
        },
        {
          path: 'dashboard',
          name: 'Dashboard',
          component: () => import('@/views/DashboardView.vue'),
          meta: { title: '工作台', fullBleed: true },
        },
        {
          path: 'projects',
          name: 'Projects',
          component: () => import('@/views/project/ProjectListView.vue'),
          meta: { title: '项目管理', fullBleed: true },
        },
        {
          path: 'projects/:id/episodes',
          name: 'ProjectEpisodes',
          component: () => import('@/views/project/ProjectEpisodesView.vue'),
          meta: { title: '剧集管理', fullBleed: true },
        },
        {
          path: 'projects/:id/immersive',
          name: 'ImmersiveCreate',
          component: () => import('@/views/create/ImmersiveCreateView.vue'),
          meta: { title: '沉浸式创作', fullBleed: true, hideSidebar: true },
        },
        {
          path: 'projects/:id/immersive/workbench',
          name: 'EpisodeWorkbench',
          component: () => import('@/views/create/EpisodeWorkbenchView.vue'),
          meta: { title: '镜头工作台', fullBleed: true, hideSidebar: true },
        },
        {
          path: 'projects/:id',
          name: 'ProjectDetail',
          component: () => import('@/views/project/ProjectDetailView.vue'),
          meta: { title: '项目详情' },
        },
        {
          path: 'projects/:id/script',
          name: 'Script',
          component: () => import('@/views/script/ScriptView.vue'),
          meta: { title: '剧本生成' },
        },
        {
          path: 'projects/:id/storyboard',
          name: 'Storyboard',
          component: () => import('@/views/storyboard/StoryboardView.vue'),
          meta: { title: '分镜制作', fullBleed: true },
        },
        {
          path: 'projects/:id/characters',
          name: 'Characters',
          component: () => import('@/views/character/CharacterView.vue'),
          meta: { title: '角色管理' },
        },
        {
          path: 'projects/:id/scenes',
          name: 'Scenes',
          component: () => import('@/views/scene/SceneView.vue'),
          meta: { title: '场景管理' },
        },
        {
          path: 'projects/:id/assets',
          name: 'Assets',
          component: () => import('@/views/asset/AssetView.vue'),
          meta: { title: '素材库', fullBleed: true },
        },
        {
          path: 'projects/:id/synthesis',
          name: 'Synthesis',
          redirect: (to) => ({
            path: `/projects/${to.params.id}/immersive/workbench`,
            query: { episode: String(to.query.episode || '1'), tab: 'video' },
          }),
        },
        {
          path: 'settings',
          name: 'Settings',
          component: () => import('@/views/settings/SettingsView.vue'),
          meta: { title: '模型配置', fullBleed: true },
        },
        {
          path: 'library/subjects',
          name: 'SubjectLibrary',
          component: () => import('@/views/library/SubjectLibraryView.vue'),
          meta: { title: '主体库', fullBleed: true },
        },
        {
          path: 'help/tutorial',
          name: 'HelpTutorial',
          component: () => import('@/views/help/StaticDocView.vue'),
          meta: { title: '使用教程', docKey: 'tutorial' },
        },
        {
          path: 'help/changelog',
          name: 'HelpChangelog',
          component: () => import('@/views/help/StaticDocView.vue'),
          meta: { title: '版本更新', docKey: 'changelog' },
        },
        {
          path: 'legal/terms',
          name: 'LegalTerms',
          component: () => import('@/views/help/StaticDocView.vue'),
          meta: { title: '用户协议', docKey: 'terms' },
        },
        {
          path: 'legal/privacy',
          name: 'LegalPrivacy',
          component: () => import('@/views/help/StaticDocView.vue'),
          meta: { title: '隐私政策', docKey: 'privacy' },
        },
        {
          path: 'about',
          name: 'About',
          component: () => import('@/views/help/StaticDocView.vue'),
          meta: { title: '关于我们', docKey: 'about' },
        },
      ],
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/',
    },
  ],
})

// Auth guard
router.beforeEach((to) => {
  const userStore = useUserStore()
  if (to.meta.requiresAuth !== false && !userStore.isLoggedIn) {
    return '/login'
  }
  if (to.meta.requiresAuth !== false && isJwtExpired(userStore.token)) {
    userStore.logout()
    return '/login'
  }
  if (userStore.isLoggedIn && (to.path === '/login' || to.path === '/register')) {
    return '/dashboard'
  }
})

export default router
