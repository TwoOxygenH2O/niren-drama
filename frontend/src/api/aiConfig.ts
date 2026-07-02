import request from './request'

const VIDEO_DEBUG_TIMEOUT = 12 * 60 * 60 * 1000

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

  /** 图生视频调试：按当前账号解析的视频配置生成视频 */
  debugGenerateImageToVideo: (data: { imageUrl: string; referenceImageUrls?: string[]; prompt?: string; duration?: number; resolution?: string; quality?: string; withSound?: boolean }) =>
    request.post('/ai-configs/debug/generate-image-to-video', data, { timeout: VIDEO_DEBUG_TIMEOUT }),

  /** 获取 ComfyUI 可用工作流模板列表 */
  listComfyUiWorkflows: () =>
    request.get('/ai-configs/comfyui/workflows'),

  /** 获取指定 ComfyUI 工作流模板详情 */
  getComfyUiWorkflow: (name: string) =>
    request.get('/ai-configs/comfyui/workflow', { params: { name } }),

  /** 生成外站视频训练提示词包 */
  buildWan22PromptPack: (id: number | string, data: { theme?: string; genre?: string; count?: number }) =>
    request.post(`/ai-configs/${id}/wan22-lora/prompt-pack`, data, { timeout: VIDEO_DEBUG_TIMEOUT }),

  /** 提交 Wan2.2 LoRA 训练任务 */
  trainWan22Lora: (id: number | string, data: FormData) =>
    request.post(`/ai-configs/${id}/wan22-lora/train`, data, { timeout: VIDEO_DEBUG_TIMEOUT }),
}
