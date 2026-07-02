import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('node_modules')) {
            if (id.includes('@element-plus/icons-vue')) return 'element-icons'
            if (id.includes('element-plus/es/components/')) {
              const component = id.split('element-plus/es/components/')[1]?.split('/')[0]
              return component ? `element-${component}` : 'element-components'
            }
            if (id.includes('element-plus')) return 'element-core'
            if (id.includes('vue') || id.includes('pinia')) return 'vue'
            return 'vendor'
          }
        },
      },
    },
  },
})
