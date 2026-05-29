export const DEFAULT_PROJECT_TYPE = '真人短剧'
export const DEFAULT_GENRE = '古装复仇'
export const DEFAULT_PLATFORM_PROFILE = 'douyin'
export const DEFAULT_PRODUCTION_MODE = 'preview'

export const PROJECT_TYPE_OPTIONS = [
  { label: '真人短剧', value: '真人短剧' },
  { label: '漫画短剧', value: '漫画短剧' },
]

export const GENRE_OPTIONS = [
  { label: '古装复仇', value: '古装复仇' },
  { label: '都市言情', value: '都市言情' },
  { label: '都市职场', value: '都市职场' },
  { label: '家庭伦理', value: '家庭伦理' },
  { label: '校园青春', value: '校园青春' },
  { label: '民国情仇', value: '民国情仇' },
  { label: '古装历史', value: '古装历史' },
  { label: '仙侠修真', value: '仙侠修真' },
  { label: '玄幻奇幻', value: '玄幻奇幻' },
  { label: '悬疑惊悚', value: '悬疑惊悚' },
  { label: '犯罪刑侦', value: '犯罪刑侦' },
  { label: '喜剧搞笑', value: '喜剧搞笑' },
  { label: '逆袭复仇', value: '逆袭复仇' },
  { label: '豪门恩怨', value: '豪门恩怨' },
  { label: '女性成长', value: '女性成长' },
]

const LEGACY_GENRE_LABEL_MAP: Record<string, string> = {
  romance: '都市言情',
  fantasy: '玄幻奇幻',
  thriller: '悬疑惊悚',
  urban: '都市职场',
  historical: '古装历史',
  costume_revenge: '古装复仇',
  ancient_revenge: '古装复仇',
  comedy: '喜剧搞笑',
}

export const PLATFORM_PROFILE_OPTIONS = [
  { label: '抖音短剧', value: 'douyin' },
  { label: '红果短剧', value: 'hongguo' },
] as const

export const PRODUCTION_MODE_OPTIONS = [
  { label: '快速预览', value: 'preview' },
  { label: '发布质量', value: 'publish' },
] as const

export function formatProjectTypeLabel(projectType?: string) {
  return projectType || DEFAULT_PROJECT_TYPE
}

export function formatGenreLabel(genre?: string) {
  if (!genre) {
    return DEFAULT_GENRE
  }
  return LEGACY_GENRE_LABEL_MAP[genre] || genre
}
