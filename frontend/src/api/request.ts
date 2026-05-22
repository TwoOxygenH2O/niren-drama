import axios from 'axios'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { normalizeApiErrorMessage } from './error'

function createBusinessError(payload: any, requestUrl = '') {
  const message = normalizeApiErrorMessage(payload?.message, requestUrl)
  const error = new Error(message)
  ;(error as any).code = payload?.code
  ;(error as any).data = payload?.data
  return error
}

// 防止 JSON.parse 将超大整数 ID 精度丢失：将超过 16 位的纯数字替换为字符串
function safeJsonParse(text: string) {
  const safe = text.replace(/:\s*(\d{17,})/g, ':"$1"')
  return JSON.parse(safe)
}

const request = axios.create({
  baseURL: '/api',
  timeout: 120000,
  transformResponse: [(data) => {
    try {
      return safeJsonParse(data)
    } catch {
      return JSON.parse(data)
    }
  }],
})

// Request interceptor - add JWT token
request.interceptors.request.use(
  (config) => {
    const userStore = useUserStore()
    if (userStore.token) {
      config.headers.Authorization = `Bearer ${userStore.token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

// Response interceptor - handle errors
request.interceptors.response.use(
  (response) => {
    const res = response.data
    if (res.code !== undefined && res.code !== 200) {
      const requestUrl = response.config?.url || ''
      const message = normalizeApiErrorMessage(res.message, requestUrl)
      ElMessage.error(message)
      return Promise.reject(createBusinessError({ ...res, message }, requestUrl))
    }
    return response
  },
  (error) => {
    const requestUrl = error.config?.url || ''
    if (error.response?.status === 401 && !requestUrl.includes('/auth/login')) {
      const userStore = useUserStore()
      userStore.logout()
      window.location.href = '/login'
    } else if (error.response?.status === 403) {
      const userStore = useUserStore()
      const payload = error.response?.data
      const message = normalizeApiErrorMessage(payload?.message || error.message, requestUrl)
      ElMessage.error(message || '无权限访问')
      userStore.logout()
      window.location.href = '/login'
      if (payload?.message) {
        return Promise.reject(createBusinessError({ ...payload, message }, requestUrl))
      }
    } else {
      const payload = error.response?.data
      const message = normalizeApiErrorMessage(payload?.message || error.message, requestUrl)
      ElMessage.error(message || '网络错误')
      if (payload?.message) {
        return Promise.reject(createBusinessError({ ...payload, message }, requestUrl))
      }
    }
    return Promise.reject(error)
  }
)

export default request
