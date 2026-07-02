<template>
  <AuthShell title="泥人剧场" :show-heading="false" variant="login">
    <div class="login-head">
      <p>泥人剧场</p>
      <h1>欢迎回来</h1>
    </div>

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
        <label class="field-label">账号</label>
        <el-input
          v-model="form.username"
          placeholder="手机号或邮箱"
          :prefix-icon="User"
          autocomplete="username"
          clearable
        />
      </el-form-item>

      <el-form-item prop="password">
        <label class="field-label">密码</label>
        <el-input
          v-model="form.password"
          type="password"
          placeholder="输入密码"
          :prefix-icon="Lock"
          show-password
          autocomplete="current-password"
        />
      </el-form-item>

      <el-button native-type="submit" type="primary" :loading="loading || verificationLoading" class="primary-btn">
        登录
      </el-button>
    </el-form>
  </AuthShell>
</template>

<script setup lang="ts">
import AuthShell from '@/components/auth/AuthShell.vue'
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { Lock, User } from '@element-plus/icons-vue'
import { authApi } from '@/api/auth'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()
const formRef = ref<FormInstance>()
const loading = ref(false)
const verificationLoading = ref(false)
const behaviorStartedAt = ref(Date.now())
const pointerMoves = ref(0)
const keyStrokes = ref(0)
const focusEvents = ref(0)

const form = ref({
  username: '',
  password: '',
  captchaId: '',
  captchaCode: '',
})

const rules: FormRules = {
  username: [{ required: true, message: '账号不能为空', trigger: ['blur', 'change'] }],
  password: [{ required: true, message: '密码不能为空', trigger: ['blur', 'change'] }],
}

onMounted(() => {
  refreshCaptcha()
  window.addEventListener('pointermove', recordPointerMove, { passive: true })
  window.addEventListener('keydown', recordKeyStroke)
  window.addEventListener('focusin', recordFocusEvent)
})

onBeforeUnmount(() => {
  window.removeEventListener('pointermove', recordPointerMove)
  window.removeEventListener('keydown', recordKeyStroke)
  window.removeEventListener('focusin', recordFocusEvent)
})

async function refreshCaptcha() {
  verificationLoading.value = true
  try {
    const res = await authApi.getCaptcha()
    form.value.captchaId = res.data.data.captchaId
    resetBehavior()
  } catch {
    form.value.captchaId = ''
  } finally {
    verificationLoading.value = false
  }
}

async function handleLogin() {
  if (!formRef.value) {
    return
  }

  try {
    await formRef.value.validate()
  } catch {
    ElMessage.warning('请先填写账号和密码')
    return
  }

  if (!form.value.captchaId) {
    await refreshCaptcha()
  }

  if (!form.value.captchaId) {
    ElMessage.warning('登录校验未就绪，请稍后重试')
    return
  }

  await waitForPassiveWindow()
  form.value.captchaCode = buildVerificationProof()

  loading.value = true
  try {
    const res = await authApi.login(form.value)
    const { token, ...userInfo } = res.data.data
    userStore.setToken(token)
    userStore.setUserInfo(userInfo)
    ElMessage.success('登录成功')
    await router.replace('/dashboard')
  } catch {
    form.value.captchaCode = ''
    await refreshCaptcha()
  } finally {
    loading.value = false
  }
}

function waitForPassiveWindow() {
  const remaining = 700 - getBehaviorElapsed()
  if (remaining <= 0) {
    return Promise.resolve()
  }
  return new Promise((resolve) => window.setTimeout(resolve, remaining))
}

function buildVerificationProof() {
  const elapsed = Math.max(getBehaviorElapsed(), 700)
  const eventCount = Math.max(getBehaviorEventCount(), 3)
  const score = Math.max(getBehaviorScore(), 66)
  return `PASSIVE:${score}:${elapsed}:${eventCount}`
}

function getBehaviorElapsed() {
  return Date.now() - behaviorStartedAt.value
}

function getBehaviorEventCount() {
  return pointerMoves.value + keyStrokes.value + focusEvents.value
}

function getBehaviorScore() {
  const elapsed = getBehaviorElapsed()
  const pointerScore = Math.min(pointerMoves.value * 2, 26)
  const keyScore = Math.min(keyStrokes.value * 8, 24)
  const focusScore = Math.min(focusEvents.value * 4, 10)
  const timeScore = elapsed > 1000 ? 20 : elapsed > 650 ? 12 : 0
  return Math.min(99, 28 + pointerScore + keyScore + focusScore + timeScore)
}

function recordPointerMove() {
  pointerMoves.value = Math.min(pointerMoves.value + 1, 40)
}

function recordKeyStroke() {
  keyStrokes.value = Math.min(keyStrokes.value + 1, 12)
}

function recordFocusEvent() {
  focusEvents.value = Math.min(focusEvents.value + 1, 10)
}

function resetBehavior() {
  behaviorStartedAt.value = Date.now()
  pointerMoves.value = 0
  keyStrokes.value = 0
  focusEvents.value = 0
}
</script>

<style scoped>
.login-head {
  margin-bottom: 28px;
}

.login-head p {
  margin: 0 0 12px;
  color: rgba(232, 246, 255, 0.76);
  font-size: 15px;
  font-weight: 800;
}

.login-head h1 {
  margin: 0;
  color: #f8fbff;
  font-size: 30px;
  font-weight: 850;
  line-height: 1.2;
  letter-spacing: 0;
}

.auth-form {
  margin: 0;
}

:deep(.el-form-item) {
  display: block;
  margin-bottom: 18px;
}

.field-label {
  display: block;
  margin-bottom: 9px;
  color: rgba(232, 246, 255, 0.82);
  font-size: 14px;
  font-weight: 760;
}

:deep(.el-form-item__error) {
  position: static;
  inset: auto;
  display: block;
  margin-top: 7px;
  color: #ffd1dc;
  font-size: 14px;
  line-height: 1.35;
}

:deep(.el-form-item.is-error .el-input__wrapper) {
  box-shadow:
    0 0 0 1px rgba(255, 126, 151, 0.78) inset,
    0 0 0 5px rgba(255, 126, 151, 0.08) !important;
}

.primary-btn {
  width: 100%;
  height: 56px;
  margin-top: 10px;
  border: 1px solid rgba(255, 255, 255, 0.5);
  border-radius: 19px;
  background:
    linear-gradient(100deg, rgba(247, 251, 255, 0.96) 0%, rgba(80, 224, 255, 0.94) 48%, rgba(123, 92, 255, 0.96) 100%);
  color: #03101d;
  font-size: 16px;
  font-weight: 850;
  box-shadow:
    0 12px 30px rgba(0, 0, 0, 0.2),
    0 0 38px rgba(79, 224, 255, 0.18);
}

.primary-btn:hover {
  transform: translateY(-1px);
  box-shadow:
    0 14px 34px rgba(0, 0, 0, 0.22),
    0 0 46px rgba(103, 232, 249, 0.26);
}

@media (max-width: 520px) {
  .login-head {
    margin-bottom: 24px;
  }

  .login-head h1 {
    font-size: 28px;
  }
}
</style>
