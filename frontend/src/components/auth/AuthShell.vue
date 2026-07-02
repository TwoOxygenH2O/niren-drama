<template>
  <div :class="['auth-page', `auth-page--${variant}`, 'notranslate']" translate="no">
    <div class="auth-bg" aria-hidden="true" />

    <main class="auth-shell" aria-label="账号表单">
      <section :class="['auth-card', { 'auth-card--login': !showHeading }]">
        <div v-if="showHeading" class="brand-logo" aria-hidden="true">
          <img src="/logo.svg" alt="" aria-hidden="true" />
        </div>
        <h1 v-if="showHeading" class="auth-title">{{ title }}</h1>
        <p v-if="showHeading" class="auth-subtitle">登录后继续创作竖屏短剧</p>
        <slot></slot>
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    title: string
    brandName?: string
    showHeading?: boolean
    variant?: 'login' | 'register'
  }>(),
  {
    brandName: '泥人剧场',
    showHeading: true,
    variant: 'login',
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
  padding: clamp(20px, 5vw, 72px);
  background: #03060b;
  overflow: hidden;
}

.auth-bg {
  position: absolute;
  inset: 0;
  background:
    radial-gradient(circle at 50% 48%, rgba(141, 238, 255, 0.14), transparent 24%),
    radial-gradient(circle at 56% 22%, rgba(185, 167, 255, 0.12), transparent 30%),
    linear-gradient(180deg, rgba(3, 8, 14, 0.02), rgba(3, 8, 14, 0.42)),
    url("/background/auth-login-bg.png") center / cover no-repeat,
    url("/background/background1.png") center / cover no-repeat;
  pointer-events: none;
}

.auth-page--register .auth-bg {
  background:
    radial-gradient(circle at 50% 46%, rgba(103, 232, 249, 0.08), transparent 24%),
    radial-gradient(circle at 82% 72%, rgba(255, 208, 138, 0.065), transparent 32%),
    linear-gradient(180deg, rgba(3, 8, 14, 0.02), rgba(3, 8, 14, 0.42)),
    url("/background/auth-register-bg.png") center / cover no-repeat,
    url("/background/background1.png") center / cover no-repeat;
}

.auth-bg::after {
  content: "";
  position: absolute;
  inset: 0;
  background:
    radial-gradient(circle at 50% 52%, transparent 0%, rgba(3, 8, 14, 0.08) 42%, rgba(3, 8, 14, 0.5) 100%),
    linear-gradient(rgba(255, 255, 255, 0.018) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.016) 1px, transparent 1px);
  background-size: auto, 72px 72px, 72px 72px;
}

.auth-shell {
  position: relative;
  z-index: 1;
  width: min(430px, 100%);
  display: flex;
  align-items: center;
  justify-content: center;
}

.auth-shell::before {
  content: none;
}

.brand-logo {
  width: min(312px, 74vw);
  height: 88px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0 auto 18px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.18), rgba(255, 255, 255, 0.05)),
    rgba(11, 16, 31, 0.42);
  color: #fff;
  border: 1px solid rgba(151, 178, 255, 0.2);
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.35), 0 0 34px rgba(139, 92, 246, 0.18);
}

.brand-logo img {
  width: min(248px, 82%);
  max-height: 62px;
  height: auto;
  object-fit: contain;
  filter: drop-shadow(0 0 22px rgba(139, 92, 246, 0.55));
}

.auth-card {
  position: relative;
  z-index: 1;
  width: 100%;
  padding: 34px 58px 30px;
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: 20px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.072), rgba(255, 255, 255, 0.018)),
    rgba(5, 12, 20, 0.09);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.2),
    inset 0 -1px 0 rgba(255, 255, 255, 0.035);
  color: #f7fbff;
  text-shadow: 0 1px 18px rgba(0, 0, 0, 0.42);
}

.auth-card--login {
  padding: 40px 40px 34px;
  border-radius: 20px;
  border-color: rgba(255, 255, 255, 0.2);
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.072), rgba(255, 255, 255, 0.018)),
    rgba(5, 12, 20, 0.09);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.2),
    inset 0 -1px 0 rgba(255, 255, 255, 0.035);
}

.auth-title {
  margin: 0;
  text-align: center;
  font-size: 38px;
  line-height: 1.12;
  letter-spacing: 0;
  color: #f7fbff;
}

.auth-subtitle {
  margin: 10px 0 24px;
  text-align: center;
  color: #cbd6e8;
  font-size: 17px;
}

:global(.auth-card .el-input__wrapper) {
  min-height: 56px;
  border-radius: 18px;
  padding: 0 18px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.12), rgba(255, 255, 255, 0.035)),
    rgba(7, 17, 27, 0.38) !important;
  box-shadow:
    0 0 0 1px rgba(255, 255, 255, 0.34) inset,
    inset 0 1px 0 rgba(255, 255, 255, 0.22),
    inset 0 -1px 0 rgba(4, 11, 19, 0.28) !important;
  backdrop-filter: blur(30px) saturate(150%);
}

:global(.auth-card .el-input__wrapper:hover) {
  box-shadow:
    0 0 0 1px rgba(103, 232, 249, 0.46) inset,
    0 0 24px rgba(103, 232, 249, 0.08) !important;
}

:global(.auth-card .el-input__wrapper.is-focus) {
  box-shadow:
    0 0 0 1px rgba(103, 232, 249, 0.88) inset,
    0 0 0 5px rgba(103, 232, 249, 0.1),
    0 0 32px rgba(103, 232, 249, 0.14) !important;
}

:global(.auth-card .el-input__inner) {
  color: #f7fbff;
  font-size: 16px;
}

:global(.auth-card .el-input__inner::placeholder) {
  color: #d0d9e8;
}

:global(.auth-card .el-input__prefix),
:global(.auth-card .el-input__suffix) {
  color: #d3deef;
}

@media (max-width: 900px) {
  .auth-page {
    padding: 24px;
    justify-content: center;
    overflow-y: auto;
  }

  .auth-shell {
    min-height: auto;
  }

  .auth-shell::before {
    inset: -34px -24px;
  }
}

@media (max-width: 520px) {
  .auth-page {
    padding: 16px;
  }

  .auth-card {
    padding: 34px 22px 28px;
  }

  .auth-card--login {
    padding: 34px 22px 28px;
    border-radius: 18px;
  }

  .auth-title {
    font-size: 30px;
  }

  .auth-subtitle {
    font-size: 14px;
  }

  .brand-logo {
    height: 74px;
  }

  .brand-logo img {
    width: min(218px, 84%);
    max-height: 50px;
  }

}
</style>
