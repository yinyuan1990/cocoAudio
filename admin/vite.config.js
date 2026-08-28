import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'path'

// 联调服务器：本地 dev 时把 /api 与 /admin(ws) 代理到心声服务器
const TARGET = 'http://8.162.5.160:40000'

export default defineConfig({
  plugins: [vue()],
  resolve: { alias: { '@': path.resolve(__dirname, 'src') } },
  server: {
    port: 3000,
    host: true,
    proxy: {
      '/api': { target: TARGET, changeOrigin: true },
      '/admin': { target: TARGET.replace('http', 'ws'), ws: true, changeOrigin: true }
    }
  }
})
