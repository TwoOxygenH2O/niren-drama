<template>
  <div class="auth-root notranslate" translate="no">
    <div class="auth-shell">
      <aside class="brand-panel">
        <div class="brand-content">
          <div class="logo-mark hero-logo">
            <div class="logo-icon">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none">
                <path d="M15 10l4.553-2.843A1 1 0 0121 8.117v7.766a1 1 0 01-1.447.894L15 14M3 8a2 2 0 012-2h8a2 2 0 012 2v8a2 2 0 01-2 2H5a2 2 0 01-2-2V8z" stroke="white" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </div>
            <span class="logo-name">泥人短剧</span>
          </div>

          <p class="brand-eyebrow">AI 短剧全流程生产平台</p>
          <h2 class="brand-tagline">一句话生成你的第一部短剧</h2>
          <p class="brand-sub">
            登录后直接进入剧本、分镜、素材、配音和成片的连续流程，让用户在进入系统前就知道能完成什么工作。
          </p>

          <div class="feature-list">
            <article class="feature-item" v-for="feature in features" :key="feature.title">
              <span class="feature-icon">{{ feature.icon }}</span>
              <div>
                <h3>{{ feature.title }}</h3>
                <p>{{ feature.description }}</p>
              </div>
            </article>
          </div>

          <section class="preview-board">
            <div class="preview-header">
              <div>
                <span class="preview-label">体验路径</span>
                <h3>登录后 30 秒可看到的成果</h3>
              </div>
              <span class="preview-caption">从创意到成片</span>
            </div>
            <div class="preview-cards">
              <article class="preview-card" v-for="card in previewCards" :key="card.stage">
                <span class="preview-stage">{{ card.stage }}</span>
                <strong>{{ card.title }}</strong>
                <p>{{ card.meta }}</p>
              </article>
            </div>
          </section>
        </div>
      </aside>

      <section class="form-panel">
        <div class="form-stack">
          <div class="logo-mark form-brand">
            <div class="logo-icon">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
                <path d="M15 10l4.553-2.843A1 1 0 0121 8.117v7.766a1 1 0 01-1.447.894L15 14M3 8a2 2 0 012-2h8a2 2 0 012 2v8a2 2 0 01-2 2H5a2 2 0 01-2-2V8z" stroke="white" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </div>
            <span class="logo-name">泥人短剧</span>
          </div>

          <div class="form-card">
            <div class="value-banner">
              <span>创作入口</span>
              <strong>登录后继续你的剧本、分镜与成片流程</strong>
            </div>

            <div class="form-header">
              <h1>欢迎回来</h1>
              <p>用一个账号连接灵感、素材与发布产物，不再在多个工具之间来回切换。</p>
            </div>

            <section class="demo-card">
              <div class="demo-copy">
                <div>
                  <span class="demo-tag">体验账号</span>
                  <p>点击填入或复制真实可用凭据，避免手动输入出错。</p>
                </div>
                <button type="button" class="demo-fill-btn" @click="fillDemoCredentials">一键填入</button>
              </div>

              <div class="demo-actions">
                <button
                  type="button"
                  class="credential-chip"
                  @click="copyDemoField('账号', demoAccount.username)"
                >
                  <span>账号</span>
                  <strong>{{ demoAccount.username }}</strong>
                </button>
                <button
                  type="button"
                  class="credential-chip"
                  @click="copyDemoField('密码', demoAccount.password)"
                >
                  <span>密码</span>
                  <strong>{{ demoAccount.password }}</strong>
                </button>
              </div>
            </section>

            <el-form
              ref="formRef"
              :model="form"
              :rules="rules"
              size="large"
              status-icon
              class="auth-form"
              @submit.prevent="handleLogin"
            >
              <el-form-item prop="username">
                <el-input
                  v-model="form.username"
                  placeholder="请输入用户名"
                  :prefix-icon="User"
                  autocomplete="username"
                  clearable
                />
              </el-form-item>
              <el-form-item prop="password">
                <el-input
                  v-model="form.password"
                  type="password"
                  placeholder="请输入密码"
                  :prefix-icon="Lock"
                  show-password
                  autocomplete="current-password"
                  @keyup.enter="handleLogin"
                />
              </el-form-item>

              <div class="action-row">
                <el-button type="primary" :loading="loading" class="submit-btn" @click="handleLogin">
                  登录账号
                </el-button>
                <el-button class="register-btn" @click="goToRegister">
                  立即注册
                </el-button>
              </div>
            </el-form>

            <div class="help-row">
              <button type="button" class="help-link" @click="handleForgotPassword">忘记密码？</button>
              <button type="button" class="help-link" @click="handleLoginHelp">登录遇到问题？</button>
            </div>

            <div class="quick-preview">
              <article class="quick-preview-card" v-for="card in previewCards" :key="card.stage">
                <span>{{ card.stage }}</span>
                <strong>{{ card.title }}</strong>
                <p>{{ card.meta }}</p>
              </article>
            </div>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { authApi } from '@/api/auth'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()
const formRef = ref<FormInstance>()
const loading = ref(false)

const demoAccount = {
  username: 'admin',
  password: 'admin123',
}

const form = ref({ username: '', password: '' })
const rules: FormRules = {
  username: [{ required: true, message: '用户名不能为空', trigger: ['blur', 'change'] }],
  password: [{ required: true, message: '密码不能为空', trigger: ['blur', 'change'] }],
}

const features = [
  {
    icon: '剧',
    title: 'AI 剧本生成',
    description: '一句话生成角色关系、分集大纲与对白底稿。',
  },
  {
    icon: '镜',
    title: '自动拆镜出图',
    description: '把剧本拆成镜头语言，并产出可用画面素材。',
  },
  {
    icon: '片',
    title: '一键合成成片',
    description: '配音、字幕、配乐和 9:16 输出在同一流程完成。',
  },
]

const previewCards = [
  {
    stage: '创意输入',
    title: '都市反转题材',
    meta: '一句话起稿，快速锁定题材与人物关系。',
  },
  {
    stage: '脚本预览',
    title: '第 1 集冲突场',
    meta: 'AI 剧本与分镜同步展开，直接进入制作。',
  },
  {
    stage: '成片导出',
    title: '竖屏发布版',
    meta: '配音、字幕与合成完成后即可投放平台。',
  },
]

async function handleLogin() {
  if (!formRef.value) {
    return
  }

  try {
    await formRef.value.validate()
  } catch {
    ElMessage.warning('请先填写完整的登录信息')
    return
  }

  loading.value = true
  try {
    const res = await authApi.login(form.value)
    const { token, ...userInfo } = res.data.data
    userStore.setToken(token)
    userStore.setUserInfo(userInfo)
    ElMessage.success('登录成功')
    router.push('/dashboard')
  } finally {
    loading.value = false
  }
}

function goToRegister() {
  router.push('/register')
}

function fillDemoCredentials() {
  Object.assign(form.value, demoAccount)
  ElMessage.success('已填入体验账号')
}

async function copyDemoField(label: string, value: string) {
  const copied = await copyText(value)

  if (copied) {
    ElMessage.success(`${label}已复制`) 
    return
  }

  ElMessage.warning(`当前环境不支持自动复制，请手动复制${label}`)
}

function handleForgotPassword() {
  ElMessage.info('演示环境可直接使用体验账号；正式环境请联系管理员重置密码。')
}

function handleLoginHelp() {
  ElMessage.info('若登录失败，请检查输入项是否完整，或先点击“一键填入”快速体验。')
}

async function copyText(text: string) {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text)
      return true
    } catch {
      return fallbackCopyText(text)
    }
  }

  return fallbackCopyText(text)
}

function fallbackCopyText(text: string) {
  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.setAttribute('readonly', 'true')
  textarea.style.position = 'absolute'
  textarea.style.left = '-9999px'
  document.body.appendChild(textarea)
  textarea.select()
  const copied = document.execCommand('copy')
  document.body.removeChild(textarea)
  return copied
}
</script>

<style scoped>
.auth-root {
  --brand-primary: #2563eb;
  --brand-primary-strong: #1d4ed8;
  --brand-primary-soft: #dbeafe;
  --brand-ink: #0f172a;
  --brand-muted: #64748b;
  min-height: 100vh;
  padding: 24px;
  background:
    radial-gradient(circle at top left, rgba(37, 99, 235, 0.16), transparent 28%),
    linear-gradient(160deg, #eef4ff 0%, #f8fafc 48%, #eef2ff 100%);
}

.auth-shell {
  min-height: calc(100vh - 48px);
  max-width: 1360px;
  margin: 0 auto;
  display: grid;
  grid-template-columns: minmax(360px, 0.72fr) minmax(440px, 1fr);
  gap: 24px;
}

.brand-panel,
.form-card {
  border: 1px solid rgba(148, 163, 184, 0.16);
  box-shadow: 0 24px 60px rgba(15, 23, 42, 0.08);
}

.brand-panel {
  position: relative;
  overflow: hidden;
  border-radius: 32px;
  padding: 48px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.78), rgba(237, 245, 255, 0.94));
}

.brand-panel::before {
  content: '';
  position: absolute;
  inset: 0;
  background:
    radial-gradient(circle at 18% 18%, rgba(37, 99, 235, 0.18), transparent 28%),
    radial-gradient(circle at 82% 78%, rgba(14, 165, 233, 0.12), transparent 24%);
  pointer-events: none;
}

.brand-content {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  height: 100%;
}

.logo-mark {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  width: fit-content;
  padding: 10px 16px 10px 10px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.86);
  border: 1px solid rgba(191, 219, 254, 0.9);
  box-shadow: 0 12px 28px rgba(37, 99, 235, 0.1);
}

.hero-logo {
  margin-bottom: 28px;
}

.logo-icon {
  width: 40px;
  height: 40px;
  border-radius: 14px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, var(--brand-primary), #0ea5e9);
  box-shadow: 0 10px 24px rgba(37, 99, 235, 0.28);
}

.logo-name {
  font-size: 17px;
  font-weight: 800;
  letter-spacing: 0.02em;
  color: var(--brand-ink);
}

.brand-eyebrow {
  margin: 0 0 18px;
  font-size: 13px;
  letter-spacing: 0.2em;
  text-transform: uppercase;
  color: var(--brand-primary-strong);
  font-weight: 700;
}

.brand-tagline {
  margin: 0;
  font-size: clamp(38px, 4vw, 56px);
  line-height: 1.08;
  letter-spacing: -0.05em;
  color: var(--brand-ink);
}

.brand-sub {
  max-width: 560px;
  margin: 20px 0 0;
  font-size: 16px;
  line-height: 1.8;
  color: var(--brand-muted);
}

.feature-list {
  display: grid;
  gap: 16px;
  margin-top: 32px;
}

.feature-item {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  padding: 18px 20px;
  border-radius: 24px;
  background: rgba(255, 255, 255, 0.82);
  border: 1px solid rgba(191, 219, 254, 0.92);
}

.feature-icon {
  width: 44px;
  height: 44px;
  border-radius: 14px;
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, rgba(37, 99, 235, 0.16), rgba(14, 165, 233, 0.18));
  color: var(--brand-primary-strong);
  font-size: 18px;
  font-weight: 800;
}

.feature-item h3 {
  margin: 0 0 6px;
  font-size: 18px;
  color: var(--brand-ink);
}

.feature-item p {
  margin: 0;
  font-size: 14px;
  line-height: 1.7;
  color: var(--brand-muted);
}

.preview-board {
  margin-top: 28px;
  padding: 24px;
  border-radius: 28px;
  color: #fff;
  background: linear-gradient(135deg, rgba(37, 99, 235, 0.94), rgba(14, 165, 233, 0.9));
  box-shadow: 0 18px 34px rgba(37, 99, 235, 0.22);
}

.preview-header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}

.preview-label {
  display: inline-block;
  font-size: 12px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: rgba(255, 255, 255, 0.76);
}

.preview-header h3 {
  margin: 8px 0 0;
  font-size: 22px;
  line-height: 1.35;
}

.preview-caption {
  align-self: flex-start;
  padding: 10px 14px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.14);
  font-size: 13px;
  font-weight: 700;
  white-space: nowrap;
}

.preview-cards {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}

.preview-card {
  min-height: 150px;
  padding: 16px;
  border-radius: 22px;
  background: rgba(255, 255, 255, 0.14);
  border: 1px solid rgba(255, 255, 255, 0.18);
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.preview-card::after {
  content: '';
  margin-top: auto;
  height: 52px;
  border-radius: 16px;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.28), rgba(255, 255, 255, 0.08)),
    repeating-linear-gradient(90deg, rgba(255, 255, 255, 0.18), rgba(255, 255, 255, 0.18) 18px, transparent 18px, transparent 28px);
}

.preview-stage {
  font-size: 12px;
  font-weight: 700;
  color: rgba(255, 255, 255, 0.76);
}

.preview-card strong {
  font-size: 18px;
  line-height: 1.4;
}

.preview-card p {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: rgba(255, 255, 255, 0.78);
}

.form-panel {
  display: flex;
  align-items: center;
  justify-content: center;
}

.form-stack {
  width: 100%;
  max-width: 520px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 20px;
}

.form-brand {
  align-self: center;
}

.form-card {
  width: 100%;
  padding: 32px;
  border-radius: 32px;
  background: rgba(255, 255, 255, 0.96);
  backdrop-filter: blur(16px);
}

.value-banner {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 24px;
  padding: 16px 18px;
  border-radius: 24px;
  background: linear-gradient(135deg, rgba(37, 99, 235, 0.12), rgba(14, 165, 233, 0.14));
  border: 1px solid rgba(96, 165, 250, 0.32);
}

.value-banner span {
  font-size: 12px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--brand-primary-strong);
  font-weight: 700;
}

.value-banner strong {
  font-size: 20px;
  line-height: 1.5;
  color: var(--brand-ink);
}

.form-header {
  margin-bottom: 24px;
}

.form-header h1 {
  margin: 0;
  font-size: 36px;
  line-height: 1.15;
  color: var(--brand-ink);
  letter-spacing: -0.04em;
}

.form-header p {
  margin: 10px 0 0;
  font-size: 15px;
  line-height: 1.75;
  color: var(--brand-muted);
}

.demo-card {
  margin-bottom: 24px;
  padding: 18px;
  border-radius: 24px;
  background: #f8fbff;
  border: 1px solid rgba(191, 219, 254, 0.9);
}

.demo-copy {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
  margin-bottom: 14px;
}

.demo-tag {
  display: inline-flex;
  align-items: center;
  padding: 6px 10px;
  margin-bottom: 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
  color: var(--brand-primary-strong);
  background: var(--brand-primary-soft);
}

.demo-copy p {
  margin: 0;
  max-width: 280px;
  font-size: 14px;
  line-height: 1.7;
  color: var(--brand-muted);
}

.demo-fill-btn,
.credential-chip,
.help-link {
  transition: transform 0.16s ease, box-shadow 0.16s ease, border-color 0.16s ease, background 0.16s ease, color 0.16s ease;
}

.demo-fill-btn {
  border: none;
  padding: 12px 16px;
  border-radius: 14px;
  cursor: pointer;
  font-size: 14px;
  font-weight: 700;
  color: #fff;
  background: var(--brand-primary);
  box-shadow: 0 10px 22px rgba(37, 99, 235, 0.18);
}

.demo-fill-btn:hover {
  transform: translateY(-1px);
  background: var(--brand-primary-strong);
}

.demo-fill-btn:active,
.credential-chip:active,
.submit-btn:active,
.register-btn:active {
  transform: scale(0.985);
}

.demo-actions {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.credential-chip {
  padding: 14px 16px;
  border-radius: 18px;
  border: 1px solid rgba(191, 219, 254, 0.92);
  background: #fff;
  text-align: left;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.credential-chip:hover {
  transform: translateY(-1px);
  border-color: rgba(37, 99, 235, 0.38);
  box-shadow: 0 12px 24px rgba(37, 99, 235, 0.08);
}

.credential-chip span {
  font-size: 12px;
  color: var(--brand-muted);
}

.credential-chip strong {
  font-size: 16px;
  color: var(--brand-ink);
}

.auth-form {
  margin-bottom: 18px;
}

:deep(.el-form-item) {
  margin-bottom: 18px;
}

:deep(.el-input__wrapper) {
  min-height: 52px;
  border-radius: 16px;
  padding: 0 14px;
  background: #fff;
  box-shadow: 0 0 0 1px rgba(148, 163, 184, 0.28) inset, 0 2px 6px rgba(15, 23, 42, 0.03);
}

:deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px rgba(96, 165, 250, 0.54) inset, 0 12px 24px rgba(37, 99, 235, 0.08);
}

:deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 2px rgba(37, 99, 235, 0.24), 0 12px 24px rgba(37, 99, 235, 0.12);
}

:deep(.el-input__inner) {
  font-size: 15px;
  color: var(--brand-ink);
}

:deep(.el-form-item.is-error .el-input__wrapper) {
  box-shadow: 0 0 0 1px rgba(239, 68, 68, 0.46) inset, 0 0 0 2px rgba(239, 68, 68, 0.12);
}

:deep(.el-form-item__error) {
  margin-top: 6px;
  font-size: 12px;
}

.action-row {
  display: grid;
  grid-template-columns: 1.35fr 1fr;
  gap: 12px;
  margin-top: 8px;
}

.submit-btn,
.register-btn {
  height: 50px;
  margin: 0;
  border-radius: 16px;
  font-size: 15px;
  font-weight: 700;
}

.submit-btn {
  border: none;
  background: linear-gradient(135deg, var(--brand-primary), #0f6ff2);
  box-shadow: 0 16px 28px rgba(37, 99, 235, 0.2);
}

.submit-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 18px 32px rgba(37, 99, 235, 0.24);
}

.register-btn {
  color: var(--brand-primary-strong);
  background: #eff6ff;
  border: 1px solid rgba(96, 165, 250, 0.34);
}

.register-btn:hover {
  transform: translateY(-1px);
  color: var(--brand-primary-strong);
  background: #e7f0ff;
  border-color: rgba(37, 99, 235, 0.42);
}

.help-row {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 24px;
}

.help-link {
  padding: 0;
  border: none;
  background: transparent;
  color: var(--brand-primary);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
}

.help-link:hover {
  color: var(--brand-primary-strong);
  text-decoration: underline;
}

.quick-preview {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.quick-preview-card {
  min-height: 132px;
  padding: 14px;
  border-radius: 20px;
  border: 1px solid rgba(191, 219, 254, 0.84);
  background: linear-gradient(180deg, #f8fbff 0%, #eef6ff 100%);
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.quick-preview-card::after {
  content: '';
  height: 34px;
  border-radius: 12px;
  margin-top: auto;
  background:
    linear-gradient(135deg, rgba(37, 99, 235, 0.14), rgba(14, 165, 233, 0.08)),
    repeating-linear-gradient(90deg, rgba(37, 99, 235, 0.08), rgba(37, 99, 235, 0.08) 16px, transparent 16px, transparent 24px);
}

.quick-preview-card span {
  font-size: 12px;
  font-weight: 700;
  color: var(--brand-primary-strong);
}

.quick-preview-card strong {
  font-size: 16px;
  line-height: 1.45;
  color: var(--brand-ink);
}

.quick-preview-card p {
  margin: 0;
  font-size: 13px;
  line-height: 1.65;
  color: var(--brand-muted);
}

@media (max-width: 1180px) {
  .auth-shell {
    grid-template-columns: 1fr;
  }

  .brand-content {
    justify-content: flex-start;
  }

  .preview-cards,
  .quick-preview {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 900px) {
  .auth-root {
    padding: 16px;
  }

  .auth-shell {
    min-height: auto;
    gap: 16px;
  }

  .brand-panel,
  .form-card {
    border-radius: 28px;
  }

  .brand-panel {
    padding: 32px 24px;
  }

  .preview-cards,
  .quick-preview {
    grid-template-columns: 1fr;
  }

  .demo-copy,
  .preview-header {
    flex-direction: column;
  }

  .demo-fill-btn,
  .preview-caption {
    width: 100%;
    justify-content: center;
    text-align: center;
  }

  .action-row,
  .demo-actions {
    grid-template-columns: 1fr;
  }

  .help-row {
    flex-direction: column;
    align-items: flex-start;
  }
}

@media (max-width: 640px) {
  .auth-root {
    padding: 0;
    background: linear-gradient(180deg, #edf4ff 0%, #f8fafc 100%);
  }

  .auth-shell {
    gap: 0;
  }

  .brand-panel,
  .form-card {
    border-radius: 0;
    border-left: none;
    border-right: none;
    box-shadow: none;
  }

  .brand-panel {
    padding: 28px 20px;
  }

  .brand-tagline {
    font-size: 34px;
  }

  .form-panel {
    padding: 16px;
  }

  .form-stack {
    gap: 16px;
  }

  .form-card {
    padding: 24px 20px 28px;
    border-radius: 28px 28px 0 0;
    border: 1px solid rgba(148, 163, 184, 0.12);
    box-shadow: 0 -10px 30px rgba(15, 23, 42, 0.05);
  }
}
</style>
