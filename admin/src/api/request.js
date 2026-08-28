import axios from 'axios'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import router from '@/router'

const request = axios.create({ baseURL: '/api', timeout: 15000 })

request.interceptors.request.use(config => {
  const userStore = useUserStore()
  if (userStore.token) config.headers['x-token'] = userStore.token
  return config
})

request.interceptors.response.use(
  response => response.data,
  error => {
    const { response } = error
    if (response) {
      if (response.status === 401) {
        useUserStore().logout()
        router.push('/login')
        ElMessage.error('登录已过期，请重新登录')
      } else {
        ElMessage.error(response.data?.error || '请求失败')
      }
    } else {
      ElMessage.error('网络错误，请检查服务器地址')
    }
    return Promise.reject(error)
  }
)

export default request
