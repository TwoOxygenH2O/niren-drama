import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export interface UserInfo {
  id: number
  username: string
  nickname: string
  avatar?: string
  roles: string
}

export const useUserStore = defineStore('user', () => {
  const token = ref<string>(localStorage.getItem('token') || '')
  const userInfo = ref<UserInfo | null>(
    localStorage.getItem('userInfo')
      ? JSON.parse(localStorage.getItem('userInfo')!)
      : null
  )

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
