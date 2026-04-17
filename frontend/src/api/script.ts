import request from './request'

export interface ScriptGenerateParams {
  projectId: number | string
  idea: string
  episodeNo?: number
  genre?: string
  style?: string
}

export const scriptApi = {
  generate: (data: ScriptGenerateParams) =>
    request.post('/scripts/generate', data),

  listByProject: (projectId: number | string) =>
    request.get(`/scripts/project/${projectId}`),

  get: (id: number | string) =>
    request.get(`/scripts/${id}`),

  update: (id: number | string, data: { content?: string; title?: string }) =>
    request.put(`/scripts/${id}`, data),

  delete: (id: number | string) =>
    request.delete(`/scripts/${id}`),
}
