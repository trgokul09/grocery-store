import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// In dev, /api is proxied to the Spring Boot backend (default http://localhost:8080)
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    host: true,
    proxy: { '/api': { target: process.env.VITE_BACKEND_URL || 'http://localhost:8080', changeOrigin: true } },
  },
})
