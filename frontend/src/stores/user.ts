import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export interface UserInfo {
  id: string
  username: string
  nickname: string
  avatar?: string
  roles: string
}

function readStoredUserInfo(): UserInfo | null {
  const raw = localStorage.getItem('userInfo')
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw)
    if (parsed && typeof parsed === 'object') {
      return parsed as UserInfo
    }
  } catch {
    /* fall through to clear broken auth state */
  }
  localStorage.removeItem('token')
  localStorage.removeItem('userInfo')
  return null
}

export const useUserStore = defineStore('user', () => {
  const initialUserInfo = readStoredUserInfo()
  const token = ref<string>(localStorage.getItem('token') || '')
  const userInfo = ref<UserInfo | null>(initialUserInfo)

  const isLoggedIn = computed(() => !!token.value)
  const isAdmin = computed(() => userInfo.value?.roles?.includes('ADMIN') ?? false)

  function setToken(t: string) {
    token.value = t
    localStorage.setItem('token', t)
  }

  function setUserInfo(info: UserInfo) {
    userInfo.value = info
    localStorage.setItem('userInfo', JSON.stringify(info))
  }

  function logout() {
    token.value = ''
    userInfo.value = null
    localStorage.removeItem('token')
    localStorage.removeItem('userInfo')
  }

  return { token, userInfo, isLoggedIn, isAdmin, setToken, setUserInfo, logout }
})
