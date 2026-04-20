import request from './request'

export interface ScriptGenerateParams {
  projectId: number | string
  idea: string
  episodeNo?: number
  totalEpisodes?: number
  genre?: string
  style?: string
}

export const scriptApi = {
  generate: (data: ScriptGenerateParams) =>
    request.post('/scripts/generate', data),

  generatePreviewStream: async (
    data: ScriptGenerateParams,
    handlers: {
      onChunk: (content: string) => void
      onDone?: () => void
      onError?: (message: string) => void
    }
  ) => {
    const token = localStorage.getItem('token')
    const response = await fetch('/api/scripts/generate/stream', {
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
        const dataLine = lines.filter((line) => line.startsWith('data:')).map((line) => line.slice(5).trim()).join('')
        if (!dataLine) {
          continue
        }

        const payload = JSON.parse(dataLine)
        if (eventName === 'chunk' && payload.content) {
          handlers.onChunk(payload.content)
        }
        if (eventName === 'done') {
          handlers.onDone?.()
        }
        if (eventName === 'error') {
          handlers.onError?.(payload.message || '生成失败')
          throw new Error(payload.message || '生成失败')
        }
      }
    }
  },

  listByProject: (projectId: number | string) =>
    request.get(`/scripts/project/${projectId}`),

  create: (data: { id?: number | string; projectId: number | string; episodeNo: number; title: string; content: string; aiPrompt?: string }) =>
    request.post('/scripts', data),

  get: (id: number | string) =>
    request.get(`/scripts/${id}`),

  update: (id: number | string, data: { content?: string; title?: string }) =>
    request.put(`/scripts/${id}`, data),

  delete: (id: number | string) =>
    request.delete(`/scripts/${id}`),
}
