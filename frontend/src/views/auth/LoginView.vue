<template>
  <AuthShell title="登录">
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

      <el-button native-type="submit" type="primary" :loading="loading" class="primary-btn" @click="handleLogin">
        继续
      </el-button>
    </el-form>

    <div class="auth-footer">
      <span>还没有账号？</span>
      <button type="button" class="text-link" @click="goToRegister">注册</button>
    </div>
  </AuthShell>
</template>

<script setup lang="ts">
import AuthShell from '@/components/auth/AuthShell.vue'
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

const form = ref({ username: '', password: '' })
const rules: FormRules = {
  username: [{ required: true, message: '用户名不能为空', trigger: ['blur', 'change'] }],
  password: [{ required: true, message: '密码不能为空', trigger: ['blur', 'change'] }],
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

  loading.value = true
  try {
    const res = await authApi.login(form.value)
    const { token, ...userInfo } = res.data.data
    userStore.setToken(token)
    userStore.setUserInfo(userInfo)
    ElMessage.success('登录成功')
    await router.replace('/dashboard')
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
  min-height: 52px;
  border-radius: 12px;
  padding: 0 14px;
  background: var(--bg-card);
  box-shadow: 0 0 0 1px var(--border) inset;
}

:deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px var(--border-strong) inset;
}

:deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 2px var(--primary-glow);
}

:deep(.el-form-item.is-error .el-input__wrapper) {
  box-shadow: 0 0 0 1px rgba(239, 68, 68, 0.44) inset, 0 0 0 2px rgba(239, 68, 68, 0.12);
}

:deep(.el-form-item__error) {
  margin-top: 6px;
  font-size: 12px;
}

.primary-btn {
  width: 100%;
  height: 50px;
  margin-top: 4px;
  border: none;
  border-radius: 9999px;
  background: linear-gradient(135deg, var(--primary), var(--primary-dark));
  font-size: 15px;
  font-weight: 600;
  color: #fff;
  box-shadow: 0 4px 20px rgba(99, 102, 241, 0.28);
}

.primary-btn:hover {
  box-shadow: 0 8px 28px rgba(99, 102, 241, 0.45);
  transform: translateY(-4px);
}

.auth-footer {
  margin-top: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  font-size: 14px;
  color: var(--text-muted);
}

.text-link {
  padding: 0;
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}

.text-link:hover {
  color: var(--primary);
}
</style>
