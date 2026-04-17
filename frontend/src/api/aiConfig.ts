import request from './request'

export const aiConfigApi = {
  list: () =>
    request.get('/ai-configs'),

  save: (data: any) =>
    request.post('/ai-configs', data),

  delete: (id: number | string) =>
    request.delete(`/ai-configs/${id}`),

  setDefault: (id: number | string) =>
    request.put(`/ai-configs/${id}/default`),
}
