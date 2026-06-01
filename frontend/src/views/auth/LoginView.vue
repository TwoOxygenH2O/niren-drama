<template>
  <AuthShell title="登录工作台">
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
          placeholder="用户名"
          :prefix-icon="User"
          autocomplete="username"
          clearable
        />
      </el-form-item>

      <el-form-item prop="password">
        <el-input
          v-model="form.password"
          type="password"
          placeholder="密码"
          :prefix-icon="Lock"
          show-password
          autocomplete="current-password"
        />
      </el-form-item>

      <el-form-item prop="captchaCode">
        <div class="captcha-row">
          <el-input
            v-model="form.captchaCode"
            placeholder="验证码"
            :prefix-icon="CircleCheck"
            autocomplete="off"
            maxlength="4"
            clearable
          />
          <button
            type="button"
            class="captcha-image"
            :disabled="captchaLoading"
            title="刷新验证码"
            @click="refreshCaptcha"
          >
            <img v-if="captchaImage" :src="captchaImage" alt="验证码" />
            <span v-else>加载中</span>
          </button>
          <el-button
            type="default"
            class="captcha-refresh"
            :icon="RefreshRight"
            :loading="captchaLoading"
            circle
            title="刷新验证码"
            @click="refreshCaptcha"
          />
        </div>
      </el-form-item>

      <el-button native-type="submit" type="primary" :loading="loading" class="primary-btn">
        进入系统
      </el-button>
    </el-form>

    <div class="auth-footer">
      <span>还没有账号？</span>
      <button type="button" class="text-link" @click="goToRegister">创建账号</button>
    </div>
  </AuthShell>
</template>

<script setup lang="ts">
import AuthShell from '@/components/auth/AuthShell.vue'
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { CircleCheck, Lock, RefreshRight, User } from '@element-plus/icons-vue'
import { authApi } from '@/api/auth'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()
const formRef = ref<FormInstance>()
const loading = ref(false)
const captchaLoading = ref(false)
const captchaImage = ref('')

const form = ref({
  username: '',
  password: '',
  captchaId: '',
  captchaCode: '',
})

const rules: FormRules = {
  username: [{ required: true, message: '用户名不能为空', trigger: ['blur', 'change'] }],
  password: [{ required: true, message: '密码不能为空', trigger: ['blur', 'change'] }],
  captchaCode: [{ required: true, message: '验证码不能为空', trigger: ['blur', 'change'] }],
}

onMounted(() => {
  refreshCaptcha()
})

async function refreshCaptcha() {
  captchaLoading.value = true
  try {
    const res = await authApi.getCaptcha()
    const data = res.data.data
    form.value.captchaId = data.captchaId
    captchaImage.value = data.image
  } catch {
    form.value.captchaId = ''
    captchaImage.value = ''
  } finally {
    captchaLoading.value = false
  }
}

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

  if (!form.value.captchaId) {
    ElMessage.warning('验证码加载失败，请刷新后重试')
    await refreshCaptcha()
    return
  }

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

function goToRegister() {
  router.push('/register')
}
</script>

<style scoped>
.auth-form {
  margin: 0;
}

:deep(.el-form-item) {
  margin-bottom: 16px;
}

:deep(.el-input__wrapper) {
  min-height: 50px;
  border-radius: 8px;
  padding: 0 14px;
  background: #ffffff;
  box-shadow: 0 0 0 1px #d9e1ee inset;
}

:deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px #b8c4d8 inset;
}

:deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 2px rgba(37, 99, 235, 0.18), 0 0 0 1px #2563eb inset;
}

:deep(.el-form-item.is-error .el-input__wrapper) {
  box-shadow: 0 0 0 1px rgba(239, 68, 68, 0.72) inset, 0 0 0 2px rgba(239, 68, 68, 0.12);
}

:deep(.el-form-item__error) {
  margin-top: 6px;
  font-size: 12px;
}

.captcha-row {
  width: 100%;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 112px 46px;
  gap: 10px;
  align-items: start;
}

.captcha-image {
  width: 112px;
  height: 50px;
  padding: 0;
  border: 1px solid #d9e1ee;
  border-radius: 8px;
  background: #f8fafc;
  cursor: pointer;
  overflow: hidden;
}

.captcha-image:disabled {
  cursor: wait;
  opacity: 0.72;
}

.captcha-image img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.captcha-image span {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  color: #64748b;
  font-size: 13px;
}

.captcha-refresh {
  width: 46px;
  height: 50px;
  border-radius: 8px;
  border-color: #d9e1ee;
}

.primary-btn {
  width: 100%;
  height: 50px;
  margin-top: 4px;
  border: none;
  border-radius: 8px;
  background: linear-gradient(135deg, #2563eb, #0891b2);
  font-size: 15px;
  font-weight: 700;
  color: #fff;
  box-shadow: 0 14px 32px rgba(37, 99, 235, 0.24);
}

.primary-btn:hover {
  box-shadow: 0 18px 38px rgba(8, 145, 178, 0.3);
  transform: translateY(-1px);
}

.auth-footer {
  margin-top: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  font-size: 14px;
  color: #64748b;
}

.text-link {
  padding: 0;
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: 14px;
  font-weight: 700;
  color: #0f172a;
}

.text-link:hover {
  color: #2563eb;
}

@media (max-width: 520px) {
  .captcha-row {
    grid-template-columns: minmax(0, 1fr) 96px 44px;
    gap: 8px;
  }

  .captcha-image {
    width: 96px;
  }

  .captcha-refresh {
    width: 44px;
  }
}
</style>
