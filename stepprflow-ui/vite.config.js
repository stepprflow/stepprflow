import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    proxy: {
      // API, plus the Spring auth endpoints that live at the servlet root:
      // form login/logout and the OIDC authorization redirect.
      '/api': { target: 'http://localhost:8090', changeOrigin: true },
      '/login': {
        target: 'http://localhost:8090',
        changeOrigin: true,
        // Only the POST form-login goes to the backend. GET /login is the
        // SPA's own login page, so let it fall through to index.html.
        bypass: (req) => (req.method === 'GET' ? '/index.html' : undefined)
      },
      '/logout': { target: 'http://localhost:8090', changeOrigin: true },
      '/oauth2': { target: 'http://localhost:8090', changeOrigin: true }
    }
  },
  build: {
    outDir: 'dist',
    sourcemap: false
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.js'],
    globals: false
  }
})
