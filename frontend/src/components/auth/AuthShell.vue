<template>
  <div class="auth-page notranslate" translate="no">
    <div class="auth-bg" aria-hidden="true" />
    <div class="auth-shell">
      <div class="auth-brand">
        <div class="brand-icon">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
            <path d="M15 10l4.553-2.843A1 1 0 0121 8.117v7.766a1 1 0 01-1.447.894L15 14M3 8a2 2 0 012-2h8a2 2 0 012 2v8a2 2 0 01-2 2H5a2 2 0 01-2-2V8z" stroke="white" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </div>
        <span>{{ brandName }}</span>
      </div>

      <div class="auth-card">
        <p class="auth-value">输入一句话剧本,生成整部短剧</p>
        <h1 class="auth-title">{{ title }}</h1>
        <slot></slot>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    title: string
    brandName?: string
  }>(),
  {
    brandName: '泥人剧场',
  },
)
</script>

<style scoped>
.auth-page {
  position: relative;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: var(--bg-page);
  overflow: hidden;
}

.auth-bg {
  position: absolute;
  inset: 0;
  background:
    url("/background/background1.png") center / cover no-repeat,
    radial-gradient(ellipse 120% 80% at 50% 120%, rgba(30, 25, 45, 0.92) 0%, transparent 55%),
    radial-gradient(ellipse 90% 60% at 70% 20%, rgba(60, 80, 120, 0.38) 0%, transparent 50%),
    linear-gradient(165deg, #0a0a0f 0%, #0a0a0f 100%);
  pointer-events: none;
}
.auth-bg::after {
  content: '';
  position: absolute;
  inset: 0;
  background: rgba(10, 10, 15, 0.55);
}

.auth-shell {
  position: relative;
  z-index: 1;
  width: 100%;
  max-width: 420px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 20px;
}

.auth-brand {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  font-size: 18px;
  font-weight: 600;
  letter-spacing: -0.03em;
  color: #f8fafc;
  text-shadow: 0 1px 18px rgba(0, 0, 0, 0.55);
}

.brand-icon {
  width: 40px;
  height: 40px;
  border-radius: var(--radius-md);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--primary);
  box-shadow: var(--shadow-primary);
}

.auth-card {
  width: 100%;
  padding: 32px 28px 28px;
  border-radius: var(--radius-xl);
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: rgba(20, 20, 30, 0.75);
  backdrop-filter: blur(24px);
  -webkit-backdrop-filter: blur(24px);
  box-shadow:
    0 20px 60px rgba(0, 0, 0, 0.5),
    inset 0 1px 0 rgba(255, 255, 255, 0.06);
}

.auth-value {
  margin: 0;
  font-size: 14px;
  line-height: 1.7;
  color: var(--text-secondary);
  text-align: center;
}

.auth-title {
  margin: 10px 0 28px;
  font-size: 32px;
  line-height: 1.14;
  letter-spacing: -0.02em;
  text-align: center;
  color: var(--text-primary);
}

@media (max-width: 640px) {
  .auth-page {
    padding: 16px;
  }

  .auth-card {
    padding: 28px 22px 24px;
  }

  .auth-title {
    font-size: 28px;
  }
}
</style>