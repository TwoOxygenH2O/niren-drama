import request from './request'
import { streamPreview, type StreamHandlers } from './stream'

export const storyboardApi = {
  generate: (data: { scriptId: number | string; projectId: number | string }) =>
    request.post('/storyboards/generate', data),

  generatePreviewStream: (
    data: { scriptId: number | string; projectId: number | string },
    handlers: StreamHandlers,
  ) => streamPreview('/storyboards/generate/stream', data, handlers),

  savePreview: (data: { scriptId: number | string; projectId: number | string; content: string }) =>
    request.post('/storyboards/preview/save', data),

  listByProject: (projectId: number | string) =>
    request.get(`/storyboards/project/${projectId}`),

  listByScript: (scriptId: number | string) =>
    request.get(`/storyboards/script/${scriptId}`),

  get: (id: number | string) =>
    request.get(`/storyboards/${id}`),

  update: (id: number | string, data: any) =>
    request.put(`/storyboards/${id}`, data),
}
