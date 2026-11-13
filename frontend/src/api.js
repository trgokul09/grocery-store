import axios from 'axios'

export const TOKEN_KEY = 'grocery_token'

const api = axios.create({ baseURL: (import.meta.env.VITE_API_URL || '') + '/api', timeout: 30000 })

api.interceptors.request.use((cfg) => {
  const t = localStorage.getItem(TOKEN_KEY)
  if (t) cfg.headers.Authorization = `Bearer ${t}`
  return cfg
})

api.interceptors.response.use(
  (r) => r,
  (err) => {
    const url = err.config?.url || ''
    if (err.response?.status === 401 && !url.includes('/auth/login')) {
      localStorage.removeItem(TOKEN_KEY)
      window.location.assign('/login')
    }
    return Promise.reject(err)
  },
)

export default api
