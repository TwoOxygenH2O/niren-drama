import request from './request'

export interface ProjectCreateParams {
  name: string
  description?: string
  genre?: string
  episodes: number
  episodeDuration: number
}

export const projectApi = {
  list: (params?: any) =>
    request.get('/projects', { params }),

  create: (data: ProjectCreateParams) =>
    request.post('/projects', data),

  get: (id: number | string) =>
    request.get(`/projects/${id}`),

  update: (id: number | string, data: Partial<ProjectCreateParams>) =>
    request.put(`/projects/${id}`, data),

  delete: (id: number | string) =>
    request.delete(`/projects/${id}`),
}
