<template>
  <AuthShell title="泥人剧场" :show-heading="false" variant="register">
    <div class="register-head">
      <p>泥人剧场</p>
      <h1>创建账号</h1>
    </div>

    <el-form ref="formRef" :model="form" :rules="rules" size="large" class="auth-form" @submit.prevent="handleRegister">
      <el-form-item prop="username">
        <label class="field-label">账号</label>
        <el-input
          v-model="form.username"
          placeholder="3-30 位用户名"
          :prefix-icon="User"
          autocomplete="username"
          clearable
        />
      </el-form-item>

      <el-form-item prop="nickname">
        <label class="field-label">昵称</label>
        <el-input
          v-model="form.nickname"
          placeholder="创作空间显示名，可选"
          :prefix-icon="Star"
          clearable
        />
      </el-form-item>

      <el-form-item prop="email">
        <label class="field-label">邮箱</label>
        <el-input
          v-model="form.email"
          placeholder="用于找回账号，可选"
          :prefix-icon="Message"
          autocomplete="email"
          clearable
        />
      </el-form-item>

      <el-form-item prop="password">
        <label class="field-label">密码</label>
        <el-input
          v-model="form.password"
          type="password"
          placeholder="6-30 位登录密码"
          :prefix-icon="Lock"
          show-password
          autocomplete="new-password"
        />
      </el-form-item>

      <el-button native-type="submit" type="primary" :loading="loading" class="primary-btn">
        创建账号
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
.register-head {
  margin-bottom: 26px;
}

.register-head p {
  margin: 0 0 12px;
  color: rgba(232, 246, 255, 0.76);
  font-size: 15px;
  font-weight: 800;
}

.register-head h1 {
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
  margin-bottom: 17px;
}

:deep(.el-form-item.is-error .el-input__wrapper) {
  box-shadow:
    0 0 0 1px rgba(255, 126, 151, 0.78) inset,
    0 0 0 5px rgba(255, 126, 151, 0.08) !important;
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

.field-label {
  display: block;
  margin-bottom: 9px;
  color: rgba(232, 246, 255, 0.82);
  font-size: 14px;
  font-weight: 760;
}

.primary-btn {
  width: 100%;
  height: 56px;
  margin-top: 8px;
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

.auth-footer {
  margin-top: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  font-size: 14px;
  color: rgba(226, 236, 248, 0.72);
}

.text-link {
  padding: 0;
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: 14px;
  font-weight: 700;
  color: #eaf6ff;
}

.text-link:hover {
  color: #67e8f9;
}

@media (max-width: 520px) {
  .register-head {
    margin-bottom: 22px;
  }

  .register-head h1 {
    font-size: 28px;
  }
}
</style>
