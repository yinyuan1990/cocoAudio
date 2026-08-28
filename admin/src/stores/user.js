import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem('xs_token') || '')
  const username = ref(localStorage.getItem('xs_user') || 'admin')
  const isLoggedIn = computed(() => !!token.value)

  function setLogin(t, name) {
    token.value = t
    username.value = name
    localStorage.setItem('xs_token', t)
    localStorage.setItem('xs_user', name)
  }
  function logout() {
    token.value = ''
    localStorage.removeItem('xs_token')
  }
  return { token, username, isLoggedIn, setLogin, logout }
})
