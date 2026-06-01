<template>
  <div class="page-container static-doc">
    <h1 class="page-title">{{ doc.title }}</h1>
    <div class="static-doc-body">
      <p v-for="(p, i) in doc.paragraphs" :key="i">{{ p }}</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const DOCS: Record<string, { title: string; paragraphs: string[] }> = {
  tutorial: {
    title: '使用教程',
    paragraphs: [
      '欢迎使用泥人剧场。你可以从首页输入创作灵感，进入「我的空间」创建与管理项目。',
      '在项目内依次完成剧本、分镜、角色与场景管理，最后在成片预览中生成、合成和检查成片。',
      '更详细的图文与视频教程将陆续上线，敬请期待。',
    ],
  },
  changelog: {
    title: '版本更新',
    paragraphs: [
      '当前版本会持续迭代剧本生成、分镜与合成能力。',
      '若你发现异常或功能建议，欢迎通过产品内反馈渠道告诉我们。',
    ],
  },
  terms: {
    title: '用户协议',
    paragraphs: [
      '在使用本服务前，请仔细阅读并同意相关条款。你应保证所上传与生成内容不侵犯第三方合法权益。',
      '服务按「现状」提供，我们会尽力保障稳定性与数据安全，但因不可抗力或第三方原因导致的中断，将在法律允许范围内免责。',
      '完整协议文本以正式公示版本为准；继续使用即视为知悉并接受更新后的条款。',
    ],
  },
  privacy: {
    title: '隐私政策',
    paragraphs: [
      '我们重视你的隐私，仅在提供服务所必需的范围内处理账号与业务数据。',
      '除非法律法规要求或获得你的授权，我们不会向无关第三方出售你的个人信息。',
      '你可随时通过账号设置与安全策略了解数据存储与删除方式；详细条款以正式公示为准。',
    ],
  },
  about: {
    title: '关于我们',
    paragraphs: [
      '泥人剧场面向短剧与影像创作，提供从剧本到成片的一站式 AI 辅助能力。',
      '团队持续优化模型与流程，帮助创作者更高效地完成故事呈现。',
    ],
  },
}

const route = useRoute()
const doc = computed(() => {
  const key = (route.meta.docKey as string) || 'about'
  return DOCS[key] ?? DOCS.about
})
</script>

<style scoped>
.static-doc-body {
  max-width: 720px;
  color: var(--text-secondary);
  font-size: 14px;
  line-height: 1.75;
}
.static-doc-body p {
  margin: 0 0 14px;
}
</style>
