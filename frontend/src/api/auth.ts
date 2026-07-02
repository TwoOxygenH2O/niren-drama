import request from './request'

export interface LoginParams {
  username: string
  password: string
  captchaId: string
  captchaCode: string
}

export interface RegisterParams {
  username: string
  password: string
  nickname?: string
  email?: string
}

export interface CaptchaData {
  captchaId: string
  image?: string | null
  expiresIn: number
  mode: 'PASSIVE' | 'SLIDER'
  sliderTarget: number
  sliderTolerance: number
  scene: string
}

export const authApi = {
  getCaptcha: () =>
    request.get<{ data: CaptchaData }>('/auth/captcha'),

  login: (data: LoginParams) =>
    request.post('/auth/login', data),

  register: (data: RegisterParams) =>
    request.post('/auth/register', data),

  me: () =>
    request.get('/auth/me'),
}
