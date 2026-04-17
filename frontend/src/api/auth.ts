import request from './request'

export interface LoginParams {
  username: string
  password: string
}

export interface RegisterParams {
  username: string
  password: string
  nickname?: string
  email?: string
}

export const authApi = {
  login: (data: LoginParams) =>
    request.post('/auth/login', data),

  register: (data: RegisterParams) =>
    request.post('/auth/register', data),

  me: () =>
    request.get('/auth/me'),
}
