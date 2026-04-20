import request from './request'

export const sceneApi = {
  create: (data: any) =>
    request.post('/scenes', data),

  listByProject: (projectId: number | string) =>
    request.get(`/scenes/project/${projectId}`),

  get: (id: number | string) =>
    request.get(`/scenes/${id}`),

  update: (id: number | string, data: any) =>
    request.put(`/scenes/${id}`, data),

  delete: (id: number | string) =>
    request.delete(`/scenes/${id}`),

  generateImage: (id: number | string) =>
    request.post(`/scenes/${id}/generate-image`),
}
