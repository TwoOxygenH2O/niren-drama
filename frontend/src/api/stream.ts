import { normalizeApiErrorMessage } from './error'

export interface StreamHandlers {
  onChunk: (content: string) => void
  onDone?: (message?: string) => void
  onError?: (message: string) => void
}

export async function streamPreview(url: string, data: unknown, handlers: StreamHandlers) {
  const token = localStorage.getItem('token')
  const response = await fetch(`/api${url}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(data),
  })

  if (!response.ok || !response.body) {
    const message = `请求失败: ${response.status}`
    handlers.onError?.(message)
    throw new Error(message)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) {
      break
    }

    buffer += decoder.decode(value, { stream: true })
    const events = buffer.split('\n\n')
    buffer = events.pop() || ''

    for (const rawEvent of events) {
      const lines = rawEvent.split('\n')
      const eventName = lines.find((line) => line.startsWith('event:'))?.slice(6).trim() || 'message'
      const dataLine = lines
        .filter((line) => line.startsWith('data:'))
        .map((line) => line.slice(5).trim())
        .join('')

      if (!dataLine) {
        continue
      }

      const payload = JSON.parse(dataLine)
      if (eventName === 'chunk' && payload.content) {
        handlers.onChunk(payload.content)
      }
      if (eventName === 'done') {
        handlers.onDone?.(payload.message)
      }
      if (eventName === 'error') {
        const message = normalizeApiErrorMessage(payload.message || '生成失败', url)
        handlers.onError?.(message)
        throw new Error(message)
      }
    }
  }
}