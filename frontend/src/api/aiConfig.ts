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

  /** 文生图调试：按当前账号解析的配置生成图片并写入 COS */
  debugGenerateImage: (data: { prompt: string; size?: string }) =>
    request.post('/ai-configs/debug/generate-image', data, { timeout: 240000 }),
}
