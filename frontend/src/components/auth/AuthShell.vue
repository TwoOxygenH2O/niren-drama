<template>
  <div class="auth-page notranslate" translate="no">
    <div class="auth-bg" aria-hidden="true" />

    <main class="auth-shell">
      <section class="auth-story" aria-label="泥人剧场">
        <div class="auth-brand">
          <div class="brand-icon" aria-hidden="true">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none">
              <path d="M15 10l4.55-2.84A1 1 0 0121 8.12v7.76a1 1 0 01-1.45.9L15 14M3 8a2 2 0 012-2h8a2 2 0 012 2v8a2 2 0 01-2 2H5a2 2 0 01-2-2V8z" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </div>
          <span>{{ brandName }}</span>
        </div>

      </section>

      <section class="auth-panel" aria-label="账号表单">
        <div class="auth-card">
          <p class="auth-value">安全访问</p>
          <h1 class="auth-title">{{ title }}</h1>
          <slot></slot>
        </div>
      </section>
    </main>
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
  padding: 32px;
  background: #080b12;
  overflow: hidden;
}

.auth-bg {
  position: absolute;
  inset: 0;
  background:
    linear-gradient(90deg, rgba(8, 11, 18, 0.96) 0%, rgba(8, 11, 18, 0.78) 46%, rgba(8, 11, 18, 0.9) 100%),
    linear-gradient(180deg, rgba(12, 22, 38, 0.1), rgba(12, 22, 38, 0.82)),
    url("/background/background1.png") center / cover no-repeat;
  pointer-events: none;
}

.auth-shell {
  position: relative;
  z-index: 1;
  width: min(1120px, 100%);
  min-height: min(720px, calc(100vh - 64px));
  display: grid;
  grid-template-columns: minmax(0, 1fr) 432px;
  gap: 40px;
  align-items: stretch;
}

.auth-story {
  min-width: 0;
  display: flex;
  flex-direction: column;
  justify-content: flex-start;
  padding: 18px 0;
  color: #f8fafc;
}

.auth-brand {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  width: fit-content;
  color: #f8fafc;
  font-size: 18px;
  font-weight: 700;
  letter-spacing: 0;
}

.brand-icon {
  width: 42px;
  height: 42px;
  border-radius: 8px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #2563eb, #0891b2);
  color: #fff;
  box-shadow: 0 16px 38px rgba(8, 145, 178, 0.28);
}

.auth-value {
  margin: 0;
  font-size: 13px;
  font-weight: 700;
  line-height: 1.4;
  letter-spacing: 0;
  text-transform: uppercase;
  color: #67e8f9;
}

.auth-panel {
  display: flex;
  align-items: center;
}

.auth-card {
  width: 100%;
  padding: 34px 32px 30px;
  border: 1px solid rgba(226, 232, 240, 0.16);
  border-radius: 8px;
  background: rgba(248, 250, 252, 0.94);
  box-shadow: 0 28px 80px rgba(2, 6, 23, 0.42);
  color: #0f172a;
}

.auth-title {
  margin: 8px 0 26px;
  font-size: 30px;
  line-height: 1.2;
  letter-spacing: 0;
  color: #0f172a;
}

:global(.auth-card .el-input__wrapper) {
  min-height: 50px;
  border-radius: 8px;
  padding: 0 14px;
  background: #ffffff !important;
  box-shadow: 0 0 0 1px #d9e1ee inset !important;
}

:global(.auth-card .el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px #b8c4d8 inset !important;
}

:global(.auth-card .el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 2px rgba(37, 99, 235, 0.18), 0 0 0 1px #2563eb inset !important;
}

:global(.auth-card .el-input__inner) {
  color: #0f172a;
}

:global(.auth-card .el-input__inner::placeholder) {
  color: #7c8aa3;
}

@media (max-width: 900px) {
  .auth-page {
    align-items: flex-start;
    padding: 22px;
    overflow-y: auto;
  }

  .auth-shell {
    min-height: auto;
    grid-template-columns: 1fr;
    gap: 22px;
  }

  .auth-story {
    padding: 0;
  }

  .auth-panel {
    align-items: flex-start;
  }
}

@media (max-width: 520px) {
  .auth-page {
    padding: 16px;
  }

  .auth-card {
    padding: 28px 20px 24px;
  }

  .auth-title {
    font-size: 26px;
  }
}
</style>
