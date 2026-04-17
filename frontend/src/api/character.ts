import request from './request'

export interface CharacterCreateParams {
  projectId: number | string
  name: string
  description?: string
  personality?: string
  appearance?: string
  gender?: string
  age?: string
  voiceId?: string
  voiceName?: string
}

export const characterApi = {
  create: (data: CharacterCreateParams) =>
    request.post('/characters', data),

  listByProject: (projectId: number | string) =>
    request.get(`/characters/project/${projectId}`),

  get: (id: number | string) =>
    request.get(`/characters/${id}`),

  update: (id: number | string, data: Partial<CharacterCreateParams>) =>
    request.put(`/characters/${id}`, data),

  delete: (id: number | string) =>
    request.delete(`/characters/${id}`),

  generateImage: (id: number | string) =>
    request.post(`/characters/${id}/generate-image`),
}
