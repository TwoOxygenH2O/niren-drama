import request from './request'

export interface ProjectCreateParams {
  name: string
  description?: string
  projectType?: string
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

  /** 沉浸式导演助手：结合项目上下文调用配置的文本模型，并可触发分镜/剧本/大纲修复 */
  immersiveChat: (
    projectId: number | string,
    data: {
      message: string
      episodeNo?: number
      workflowPhase?: string
      outlineContent?: string
    },
  ) =>
    request.post(`/projects/${projectId}/immersive-chat`, data, {
      timeout: 300000,
    }),
}
