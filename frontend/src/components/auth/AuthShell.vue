<template>
  <div class="auth-page notranslate" translate="no">
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
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background:
    radial-gradient(circle at top, rgba(15, 23, 42, 0.05), transparent 28%),
    linear-gradient(180deg, #f6f6f3 0%, #f3f3ef 100%);
}

.auth-shell {
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
  color: #111827;
}

.brand-icon {
  width: 40px;
  height: 40px;
  border-radius: 14px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: #111827;
  box-shadow: 0 10px 24px rgba(15, 23, 42, 0.12);
}

.auth-card {
  width: 100%;
  padding: 32px 28px 28px;
  border-radius: 24px;
  border: 1px solid rgba(17, 24, 39, 0.08);
  background: rgba(255, 255, 255, 0.92);
  box-shadow: 0 18px 42px rgba(15, 23, 42, 0.08);
  backdrop-filter: blur(12px);
}

.auth-value {
  margin: 0;
  font-size: 14px;
  line-height: 1.7;
  color: #52525b;
  text-align: center;
}

.auth-title {
  margin: 10px 0 28px;
  font-size: 32px;
  line-height: 1.14;
  letter-spacing: -0.05em;
  text-align: center;
  color: #111827;
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