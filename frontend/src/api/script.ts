import request from './request'
import { streamPreview, type StreamHandlers, type StreamPreviewOptions } from './stream'

export interface ScriptGenerateParams {
  projectId: number | string
  idea: string
  episodeNo?: number
  startEpisode?: number
  endEpisode?: number
  totalEpisodes?: number
  genre?: string
  style?: string
}

export const scriptApi = {
  generateOutline: (data: ScriptGenerateParams) =>
    request.post('/scripts/generate/outline', data),

  generateOutlinePreviewStream: (
    data: ScriptGenerateParams,
    handlers: StreamHandlers,
    options?: StreamPreviewOptions,
  ) => streamPreview('/scripts/generate/outline/stream', data, handlers, options),

  saveOutlinePreview: (data: { projectId: number | string; content: string; idea?: string }) =>
    request.post('/scripts/preview/outline/save', data),

  repairOutlinePreview: (data: { projectId: number | string; content: string; idea?: string }) =>
    request.post('/scripts/preview/outline/repair', data),

  generate: (data: ScriptGenerateParams) =>
    request.post('/scripts/generate', data),

  generateBatch: (data: ScriptGenerateParams) =>
    request.post('/scripts/generate/batch', data),

  generatePreviewStream: async (
    data: ScriptGenerateParams,
    handlers: StreamHandlers,
    options?: StreamPreviewOptions,
  ) => streamPreview('/scripts/generate/stream', data, handlers, options),

  saveBatchPreview: (data: {
    projectId: number | string
    startEpisode: number
    endEpisode: number
    content: string
    idea?: string
  }) => request.post('/scripts/preview/batch/save', data),

  listByProject: (projectId: number | string) =>
    request.get(`/scripts/project/${projectId}`),

  create: (data: { id?: number | string; projectId: number | string; episodeNo: number; title: string; content: string; summary?: string; aiPrompt?: string }) =>
    request.post('/scripts', data),

  get: (id: number | string) =>
    request.get(`/scripts/${id}`),

  update: (id: number | string, data: { content?: string; title?: string; summary?: string }) =>
    request.put(`/scripts/${id}`, data),

  delete: (id: number | string) =>
    request.delete(`/scripts/${id}`),
}
