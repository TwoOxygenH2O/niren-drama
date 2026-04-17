import request from './request'

export const storyboardApi = {
  generate: (data: { scriptId: number | string; projectId: number | string }) =>
    request.post('/storyboards/generate', data),

  listByProject: (projectId: number | string) =>
    request.get(`/storyboards/project/${projectId}`),

  listByScript: (scriptId: number | string) =>
    request.get(`/storyboards/script/${scriptId}`),

  get: (id: number | string) =>
    request.get(`/storyboards/${id}`),

  update: (id: number | string, data: any) =>
    request.put(`/storyboards/${id}`, data),
}
