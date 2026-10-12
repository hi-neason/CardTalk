import { defineConfig } from 'vite';
import uni from '@dcloudio/vite-plugin-uni';

const uniPlugin = (uni as unknown as { default?: typeof uni }).default || uni;

export default defineConfig({
  plugins: [uniPlugin()],
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    proxy: { '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true } },
  },
});
