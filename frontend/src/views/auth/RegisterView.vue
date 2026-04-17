<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-header">
        <el-icon size="48" color="#6366f1"><VideoCamera /></el-icon>
        <h1>创建账号</h1>
        <p>加入倪人短剧，开始 AI 创作</p>
      </div>
      <el-form ref="formRef" :model="form" :rules="rules" size="large">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="用户名（3-30位）" :prefix-icon="User" />
        </el-form-item>
        <el-form-item prop="nickname">
          <el-input v-model="form.nickname" placeholder="昵称（可选）" :prefix-icon="Star" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码（6-30位）"
            :prefix-icon="Lock"
            show-password
          />
        </el-form-item>
        <el-button
          type="primary"
          :loading="loading"
          style="width: 100%; margin-top: 8px"
          @click="handleRegister"
        >
          注册
        </el-button>
      </el-form>
      <div class="login-footer">
        <span>已有账号？</span>
        <router-link to="/login">立即登录</router-link>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock, Star } from '@element-plus/icons-vue'
import { authApi } from '@/api/auth'

const router = useRouter()
const formRef = ref()
const loading = ref(false)
const form = ref({ username: '', nickname: '', password: '' })
const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 30, message: '用户名长度3-30位', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 30, message: '密码长度6-30位', trigger: 'blur' },
  ],
}

async function handleRegister() {
  await formRef.value.validate()
  loading.value = true
  try {
    await authApi.register(form.value)
    ElMessage.success('注册成功，请登录')
    router.push('/login')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}
.login-card {
  background: #fff;
  border-radius: 16px;
  padding: 48px 40px;
  width: 420px;
  box-shadow: 0 20px 60px rgba(0,0,0,0.2);
}
.login-header {
  text-align: center;
  margin-bottom: 36px;
}
.login-header h1 {
  font-size: 28px;
  font-weight: 700;
  color: #1a202c;
  margin: 12px 0 8px;
}
.login-header p {
  color: #718096;
  font-size: 14px;
}
.login-footer {
  text-align: center;
  margin-top: 20px;
  color: #718096;
  font-size: 14px;
}
.login-footer a {
  color: #6366f1;
  text-decoration: none;
  margin-left: 4px;
}
</style>
