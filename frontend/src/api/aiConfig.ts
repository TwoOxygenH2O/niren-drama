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

  getProviderDefaults: (provider: string, configType: string) =>
    request.get('/ai-configs/provider-defaults', { params: { provider, configType } }),
}
