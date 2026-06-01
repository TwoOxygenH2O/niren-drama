<template>
  <AuthShell title="创建账号">
    <el-form ref="formRef" :model="form" :rules="rules" size="large" class="auth-form" @submit.prevent="handleRegister">
      <el-form-item prop="username">
        <el-input
          v-model="form.username"
          placeholder="用户名"
          :prefix-icon="User"
          autocomplete="username"
          clearable
        />
      </el-form-item>

      <el-form-item prop="nickname">
        <el-input
          v-model="form.nickname"
          placeholder="昵称（选填）"
          :prefix-icon="Star"
          clearable
        />
      </el-form-item>

      <el-form-item prop="email">
        <el-input
          v-model="form.email"
          placeholder="邮箱（选填）"
          :prefix-icon="Message"
          autocomplete="email"
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
          autocomplete="new-password"
        />
      </el-form-item>

      <el-button native-type="submit" type="primary" :loading="loading" class="primary-btn">
        创建并登录
      </el-button>
    </el-form>

    <div class="auth-footer">
      <span>已经有账号？</span>
      <button type="button" class="text-link" @click="goToLogin">返回登录</button>
    </div>
  </AuthShell>
</template>

<script setup lang="ts">
import AuthShell from '@/components/auth/AuthShell.vue'
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { Lock, Message, Star, User } from '@element-plus/icons-vue'
import { authApi } from '@/api/auth'

const router = useRouter()
const formRef = ref<FormInstance>()
const loading = ref(false)

const form = ref({
  username: '',
  nickname: '',
  email: '',
  password: '',
})

const rules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: ['blur', 'change'] },
    { min: 3, max: 30, message: '用户名长度需为 3-30 位', trigger: ['blur', 'change'] },
  ],
  email: [
    { type: 'email', message: '请输入有效邮箱', trigger: ['blur', 'change'] },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: ['blur', 'change'] },
    { min: 6, max: 30, message: '密码长度需为 6-30 位', trigger: ['blur', 'change'] },
  ],
}

async function handleRegister() {
  if (!formRef.value) {
    return
  }

  try {
    await formRef.value.validate()
  } catch {
    ElMessage.warning('请先完整填写注册信息')
    return
  }

  loading.value = true
  try {
    await authApi.register(form.value)
    ElMessage.success('注册成功，请登录')
    router.push('/login')
  } finally {
    loading.value = false
  }
}

function goToLogin() {
  router.push('/login')
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
</style>
