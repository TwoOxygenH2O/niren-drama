import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'

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
          meta: { title: '工作台' },
        },
        {
          path: 'projects',
          name: 'Projects',
          component: () => import('@/views/project/ProjectListView.vue'),
          meta: { title: '项目管理' },
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
          meta: { title: '分镜制作' },
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
          meta: { title: '素材库' },
        },
        {
          path: 'projects/:id/synthesis',
          name: 'Synthesis',
          component: () => import('@/views/synthesis/SynthesisView.vue'),
          meta: { title: '合成导出' },
        },
        {
          path: 'settings',
          name: 'Settings',
          component: () => import('@/views/settings/SettingsView.vue'),
          meta: { title: 'AI配置' },
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
  if (userStore.isLoggedIn && (to.path === '/login' || to.path === '/register')) {
    return '/dashboard'
  }
})

export default router
