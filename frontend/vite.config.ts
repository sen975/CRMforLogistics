import { defineConfig } from 'vitest/config';
import vue from '@vitejs/plugin-vue';
import yaml from '@rollup/plugin-yaml';
import path from 'path';

export default defineConfig({
  plugins: [
    vue({
      template: {
        compilerOptions: {
          isCustomElement: (tag: string) => ['ninja-keys'].includes(tag),
        },
      },
    }),
    yaml(),
  ],
  css: {
    preprocessorOptions: {
      scss: {
        api: 'modern-compiler',
        // Let SCSS resolve imports like 'shared/assets/...'
        // and 'dashboard/assets/...' relative to src/
        includePaths: [path.resolve('./src')],
      },
    },
  },
  resolve: {
    alias: {
      vue: 'vue/dist/vue.esm-bundler.js',
      components: path.resolve('./src/dashboard/components'),
      next: path.resolve('./src/dashboard/components-next'),
      dashboard: path.resolve('./src/dashboard'),
      v3: path.resolve('./src/v3'),
      helpers: path.resolve('./src/shared/helpers'),
      shared: path.resolve('./src/shared'),
      assets: path.resolve('./src/assets'),
      theme: path.resolve('./theme'),
    },
  },
  test: {
    clearMocks: true,
    environment: 'jsdom',
    globals: true,
    setupFiles: ['fake-indexeddb/auto'],
  },
  server: {
    port: 3000,
    host: '0.0.0.0',
  },
});
