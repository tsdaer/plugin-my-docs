import { defineConfig } from 'vitest/config'
import { fileURLToPath } from 'node:url'

// 仅供 vitest 使用（打包走 rsbuild.config.ts）。
// theme-switcher.spec.ts 要读取仓库 templates 下的模板文件，默认 Vite 会把
// 项目根之外的路径拦掉，所以这里显式放开上一级目录。
export default defineConfig({
  server: {
    fs: {
      allow: [
        fileURLToPath(new URL('.', import.meta.url)),
        fileURLToPath(new URL('..', import.meta.url)),
      ],
    },
  },
})
