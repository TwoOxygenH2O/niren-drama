import { normalizeApiErrorMessage } from './error'

export interface StreamHandlers {
  onChunk: (content: string) => void
  onDone?: (message?: string) => void
  onError?: (message: string) => void
}

export interface StreamPreviewOptions {
  signal?: AbortSignal
}

export async function streamPreview(
  url: string,
  data: unknown,
  handlers: StreamHandlers,
  options: StreamPreviewOptions = {},
) {
  const token = localStorage.getItem('token')
  const response = await fetch(`/api${url}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(data),
    signal: options.signal,
  })

  if (!response.ok || !response.body) {
    const message = `请求失败: ${response.status}`
    handlers.onError?.(message)
    throw new Error(message)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let doneEventReceived = false

  const processEventBlock = (rawEvent: string) => {
    if (!rawEvent.trim()) {
      return
    }

    const lines = rawEvent.split('\n')
    const eventName = lines.find((line) => line.startsWith('event:'))?.slice(6).trim() || 'message'
    const dataLine = lines
      .filter((line) => line.startsWith('data:'))
      .map((line) => line.slice(5).trim())
      .join('\n')

    if (!dataLine) {
      return
    }

    let payload: any
    try {
      payload = JSON.parse(dataLine)
    } catch {
      const message = normalizeApiErrorMessage('流式响应解析失败，请重试', url)
      handlers.onError?.(message)
      throw new Error(message)
    }

    if (eventName === 'chunk' && Object.prototype.hasOwnProperty.call(payload, 'content')) {
      handlers.onChunk(String(payload.content ?? ''))
    }
    if (eventName === 'done') {
      doneEventReceived = true
      handlers.onDone?.(payload.message)
    }
    if (eventName === 'error') {
      const message = normalizeApiErrorMessage(payload.message || '生成失败', url)
      handlers.onError?.(message)
      throw new Error(message)
    }
  }

  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) {
        break
      }

      buffer += decoder.decode(value, { stream: true })
      const events = buffer.split('\n\n')
      buffer = events.pop() || ''

      for (const rawEvent of events) {
        processEventBlock(rawEvent)
      }
    }
  } catch (error) {
    if (options.signal?.aborted) {
      return
    }
    throw error
  } finally {
    reader.releaseLock()
  }

  const tail = decoder.decode()
  if (tail) {
    buffer += tail
  }
  if (buffer.trim()) {
    processEventBlock(buffer)
  }

  if (!doneEventReceived && !options.signal?.aborted) {
    handlers.onDone?.('流式连接已结束')
  }
}
