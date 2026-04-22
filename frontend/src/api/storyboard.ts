import request from './request'
import { streamPreview, type StreamHandlers } from './stream'

const STORYBOARD_REPAIR_TIMEOUT = 300000

export const storyboardApi = {
  generate: (data: { scriptId: number | string; projectId: number | string }) =>
    request.post('/storyboards/generate', data),

  generatePreviewStream: (
    data: { scriptId: number | string; projectId: number | string },
    handlers: StreamHandlers,
  ) => streamPreview('/storyboards/generate/stream', data, handlers),

  savePreview: (data: { scriptId: number | string; projectId: number | string; content: string }) =>
    request.post('/storyboards/preview/save', data),

  repairPreview: (data: { scriptId: number | string; projectId: number | string; content: string }) =>
    request.post('/storyboards/preview/repair', data, { timeout: STORYBOARD_REPAIR_TIMEOUT }),

  listByProject: (projectId: number | string) =>
    request.get(`/storyboards/project/${projectId}`),

  listByScript: (scriptId: number | string) =>
    request.get(`/storyboards/script/${scriptId}`),

  get: (id: number | string) =>
    request.get(`/storyboards/${id}`),

  update: (id: number | string, data: any) =>
    request.put(`/storyboards/${id}`, data),
}
