import DOMPurify from 'dompurify'
import { marked } from 'marked'

marked.setOptions({
  async: false,
  breaks: true,
  gfm: true,
})

/** 行首行尾的结构化标记 → 仅用于 Markdown 渲染的 HTML 分隔块（由 outline-md 样式表美化） */
export function preprocessAiOutlineMarkdown(source: string): string {
  if (!source) return ''
  let t = source
  t = t.replace(
    /^###PROJECT_COMMON_INFO_START###\s*$/gim,
    '\n\n<div class="ai-outline-sep ai-outline-sep--pci-start" role="separator"><span class="ai-outline-sep__arm" aria-hidden="true"></span><span class="ai-outline-sep__chip">项目设定</span><span class="ai-outline-sep__arm" aria-hidden="true"></span></div>\n\n',
  )
  t = t.replace(
    /^###PROJECT_COMMON_INFO_END###\s*$/gim,
    '\n\n<div class="ai-outline-sep ai-outline-sep--pci-end" role="separator"><span class="ai-outline-sep__arm ai-outline-sep__arm--muted" aria-hidden="true"></span><span class="ai-outline-sep__chip ai-outline-sep__chip--muted">本节完</span><span class="ai-outline-sep__arm ai-outline-sep__arm--muted" aria-hidden="true"></span></div>\n\n',
  )
  t = t.replace(
    /^###EPISODE_OUTLINE_START:(\d+)###\s*$/gim,
    (_m, ep: string) =>
      `\n\n<div class="ai-outline-sep ai-outline-sep--ep-start" role="separator" data-episode="${ep.replace(/[^\d]/g, '')}"><span class="ai-outline-sep__arm ai-outline-sep__arm--accent" aria-hidden="true"></span><span class="ai-outline-sep__pill">第 ${ep.replace(/[^\d]/g, '')} 集</span><span class="ai-outline-sep__arm ai-outline-sep__arm--accent" aria-hidden="true"></span></div>\n\n`,
  )
  t = t.replace(
    /^###EPISODE_OUTLINE_END###\s*$/gim,
    '\n\n<div class="ai-outline-sep ai-outline-sep--ep-end" role="separator"><span class="ai-outline-sep__arm ai-outline-sep__arm--fine" aria-hidden="true"></span><span class="ai-outline-sep__glyph" aria-hidden="true">◇</span><span class="ai-outline-sep__arm ai-outline-sep__arm--fine" aria-hidden="true"></span></div>\n\n',
  )
  // 若模型未单独成行输出，仍去掉裸标记，避免污染正文
  t = t.replace(/###PROJECT_COMMON_INFO_START###/g, '')
  t = t.replace(/###PROJECT_COMMON_INFO_END###/g, '')
  t = t.replace(/###EPISODE_OUTLINE_START:\d+###/g, '')
  t = t.replace(/###EPISODE_OUTLINE_END###/g, '')
  return t
}

/**
 * 流式阶段纯文本：把标记行换成轻量分隔文案，不展示 ###…###，风格与终稿分隔语义对齐。
 */
export function formatOutlinePlainDividers(source: string): string {
  if (!source) return ''
  let t = source
  t = t.replace(/^###PROJECT_COMMON_INFO_START###\s*$/gim, '\n        ·   项目设定   ·\n')
  t = t.replace(/^###PROJECT_COMMON_INFO_END###\s*$/gim, '\n        ·   本节完   ·\n')
  t = t.replace(/^###EPISODE_OUTLINE_START:(\d+)###\s*$/gim, (_m, ep: string) => `\n        ·   第 ${ep} 集   ·\n`)
  t = t.replace(/^###EPISODE_OUTLINE_END###\s*$/gim, '\n        ·   ─   ·\n')
  t = t.replace(/###PROJECT_COMMON_INFO_START###/g, '')
  t = t.replace(/###PROJECT_COMMON_INFO_END###/g, '')
  t = t.replace(/###EPISODE_OUTLINE_START:\d+###/g, '')
  t = t.replace(/###EPISODE_OUTLINE_END###/g, '')
  return t
}

/** 沉浸式大纲区等：Markdown → 安全 HTML（支持 **加粗**、`代码`、列表等） */
export function renderAiOutlineMarkdown(source: string): string {
  const md = preprocessAiOutlineMarkdown(source)
  if (!md.trim()) return ''
  const rawHtml = marked.parse(md, { async: false }) as string
  return DOMPurify.sanitize(rawHtml, {
    ALLOWED_TAGS: [
      'p',
      'br',
      'strong',
      'em',
      'b',
      'i',
      'del',
      's',
      'code',
      'pre',
      'h1',
      'h2',
      'h3',
      'h4',
      'h5',
      'h6',
      'ul',
      'ol',
      'li',
      'blockquote',
      'hr',
      'a',
      'table',
      'thead',
      'tbody',
      'tr',
      'th',
      'td',
      'div',
      'span',
    ],
    ALLOWED_ATTR: ['href', 'title', 'target', 'rel', 'class', 'role', 'aria-label', 'aria-hidden', 'data-episode'],
    ADD_ATTR: ['target'],
  })
}
