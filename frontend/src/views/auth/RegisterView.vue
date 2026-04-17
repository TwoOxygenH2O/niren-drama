<template>
  <div class="auth-root">
    <!-- Left branding panel -->
    <div class="brand-panel">
      <div class="brand-content">
        <div class="brand-logo">
          <div class="brand-icon">
            <svg width="28" height="28" viewBox="0 0 24 24" fill="none">
              <path d="M15 10l4.553-2.843A1 1 0 0121 8.117v7.766a1 1 0 01-1.447.894L15 14M3 8a2 2 0 012-2h8a2 2 0 012 2v8a2 2 0 01-2 2H5a2 2 0 01-2-2V8z" stroke="white" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </div>
          <span class="brand-name">泥人短剧</span>
        </div>
        <h2 class="brand-tagline">加入创作者<br>社区</h2>
        <p class="brand-sub">注册账号，立即体验 AI 全自动短剧制作，从创意到成片最快几分钟完成。</p>
        <div class="feature-list">
          <div class="feature-item" v-for="f in features" :key="f">
            <span class="feature-dot"></span>
            <span>{{ f }}</span>
          </div>
        </div>
      </div>
      <div class="brand-bg"></div>
    </div>

    <!-- Right form panel -->
    <div class="form-panel">
      <div class="form-card">
        <div class="form-header">
          <h1>创建账号</h1>
          <p>填写信息，开启您的 AI 短剧创作之旅</p>
        </div>

        <el-form ref="formRef" :model="form" :rules="rules" size="large">
          <el-form-item prop="username">
            <el-input v-model="form.username" placeholder="用户名（3-30位）" :prefix-icon="User" autocomplete="username" />
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
              autocomplete="new-password"
            />
          </el-form-item>
          <el-button
            type="primary"
            :loading="loading"
            class="submit-btn"
            @click="handleRegister"
          >
            注册账号
          </el-button>
        </el-form>

        <div class="form-footer">
          <span>已有账号？</span>
          <router-link to="/login">立即登录</router-link>
        </div>
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

const features = [
  'AI 自动生成剧本，支持多种题材',
  '分镜智能拆解与画面 AI 生图',
  '角色音色 TTS 配音，一键合成',
  '竖屏 9:16 格式，适配短视频平台',
]

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
.auth-root { display: flex; height: 100vh; overflow: hidden; }
.brand-panel {
  width: 52%; background: #0a0e1a; position: relative;
  overflow: hidden; display: flex; align-items: center; justify-content: center;
}
.brand-bg {
  position: absolute; inset: 0;
  background:
    radial-gradient(ellipse 60% 50% at 30% 40%, rgba(99,102,241,0.28) 0%, transparent 70%),
    radial-gradient(ellipse 40% 50% at 75% 70%, rgba(124,58,237,0.2) 0%, transparent 70%),
    linear-gradient(160deg, #0a0e1a 0%, #0f1425 100%);
  z-index: 0;
}
.brand-bg::after {
  content: ''; position: absolute; inset: 0;
  background-image:
    linear-gradient(rgba(99,102,241,0.04) 1px, transparent 1px),
    linear-gradient(90deg, rgba(99,102,241,0.04) 1px, transparent 1px);
  background-size: 40px 40px;
}
.brand-content { position: relative; z-index: 1; padding: 48px; max-width: 480px; }
.brand-logo { display: flex; align-items: center; gap: 12px; margin-bottom: 40px; }
.brand-icon {
  width: 48px; height: 48px; border-radius: 14px;
  background: linear-gradient(135deg, #6366f1, #7c3aed);
  display: flex; align-items: center; justify-content: center;
  box-shadow: 0 8px 24px rgba(99,102,241,0.4);
}
.brand-name { font-size: 22px; font-weight: 800; color: #fff; letter-spacing: -0.5px; }
.brand-tagline { font-size: 40px; font-weight: 800; color: #fff; line-height: 1.2; margin-bottom: 18px; letter-spacing: -1px; }
.brand-sub { font-size: 15px; color: rgba(148,163,184,0.85); line-height: 1.7; margin-bottom: 36px; }
.feature-list { display: flex; flex-direction: column; gap: 13px; }
.feature-item { display: flex; align-items: center; gap: 12px; font-size: 14px; color: rgba(226,232,240,0.8); }
.feature-dot { width: 6px; height: 6px; border-radius: 50%; background: #818cf8; flex-shrink: 0; box-shadow: 0 0 8px rgba(129,140,248,0.6); }

.form-panel {
  flex: 1; display: flex; align-items: center; justify-content: center;
  background: #f4f6fb; padding: 32px;
}
.form-card {
  width: 100%; max-width: 400px; background: #fff;
  border-radius: 20px; padding: 44px 40px;
  box-shadow: 0 8px 40px rgba(17,24,39,0.10);
}
.form-header { margin-bottom: 32px; }
.form-header h1 { font-size: 26px; font-weight: 800; color: #111827; letter-spacing: -0.5px; margin-bottom: 6px; }
.form-header p { font-size: 14px; color: #6b7280; }
.submit-btn {
  width: 100%; margin-top: 10px; height: 44px; font-size: 15px; font-weight: 600;
  background: linear-gradient(135deg, #6366f1, #4f46e5); border: none; border-radius: 10px;
  box-shadow: 0 4px 16px rgba(99,102,241,0.3); transition: box-shadow 0.2s, transform 0.15s;
}
.submit-btn:hover { box-shadow: 0 8px 24px rgba(99,102,241,0.4); transform: translateY(-1px); }
.form-footer { text-align: center; margin-top: 22px; font-size: 14px; color: #9ca3af; }
.form-footer a { color: #6366f1; text-decoration: none; font-weight: 600; margin-left: 4px; }
.form-footer a:hover { text-decoration: underline; }
@media (max-width: 768px) {
  .brand-panel { display: none; }
  .form-panel { background: #fff; }
  .form-card { box-shadow: none; padding: 32px 24px; }
}
</style>
