function isInternalStringTypeMismatch(message: string) {
  return message.includes('!= java.lang.String')
    || message.includes(' cannot be cast to class java.lang.String')
    || message.includes('Cannot deserialize value of type `java.lang.String`')
    || message.includes('Cannot deserialize value of type java.lang.String')
}

function isTimeoutError(message: string) {
  return /timeout of \d+ms exceeded/i.test(message)
    || message.includes('ECONNABORTED')
}

export function normalizeApiErrorMessage(message: unknown, requestUrl = '') {
  const normalizedMessage = typeof message === 'string' ? message.trim() : ''
  if (!normalizedMessage) {
    return '请求失败'
  }

  if (isTimeoutError(normalizedMessage)) {
    if (requestUrl.includes('/storyboards/preview/repair')) {
      return '分镜 AI 修复超时，请稍后重试；如果内容很长，建议先删掉无关说明再修复'
    }
    if (requestUrl.includes('/scripts/preview/outline/repair')) {
      return '大纲 AI 修复超时，请稍后重试'
    }
    if (requestUrl.includes('/immersive-chat')) {
      return '导演助手请求超时，可缩短说明后重试'
    }
    return '请求超时，请稍后重试'
  }

  if (!isInternalStringTypeMismatch(normalizedMessage)) {
    return normalizedMessage
  }

  if (requestUrl.includes('/scripts/preview/outline')) {
    return '大纲预览保存失败：内容格式异常，请保留项目通用信息和每集大纲标记后重试'
  }

  if (requestUrl.includes('/scripts/preview/batch')) {
    return '批量剧本预览保存失败：内容格式异常，请保留每集开始和结束标记后重试'
  }

  if (requestUrl === '/scripts' || /\/scripts\/\d+$/.test(requestUrl)) {
    return '剧本保存失败：标题、大纲和正文必须是纯文本，请检查后重试'
  }

  if (requestUrl.includes('/storyboards')) {
    return '分镜预览保存失败：内容格式异常，请检查 JSON 结构和字段后重试'
  }

  return '系统返回了格式异常的数据，请刷新页面后重试'
}