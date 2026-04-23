export const DEFAULT_PROJECT_TYPE = '真人短剧'

export const PROJECT_TYPE_OPTIONS = [
  { label: '真人短剧', value: '真人短剧' },
  { label: '漫画短剧', value: '漫画短剧' },
]

export const GENRE_OPTIONS = [
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
  comedy: '喜剧搞笑',
}

export function formatProjectTypeLabel(projectType?: string) {
  return projectType || DEFAULT_PROJECT_TYPE
}

export function formatGenreLabel(genre?: string) {
  if (!genre) {
    return ''
  }
  return LEGACY_GENRE_LABEL_MAP[genre] || genre
}