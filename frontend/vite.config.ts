import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import { apiOrigin } from './src/api.ts';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_');
  const target = apiOrigin(env.VITE_RELAY_API_BASE_URL);
  return {
    plugins: [react()],
    server: {
      host: '127.0.0.1', port: 5173, strictPort: true,
      proxy: { '^/(workflows|runs|approvals|hooks|actuator)(/|\\?|$)': { target, changeOrigin: true } },
    },
    preview: { host: '127.0.0.1', port: 4173, strictPort: true, proxy: {} },
  };
});
