import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  // 127.0.0.1 (not localhost): Node 17+ may resolve localhost to ::1 first,
  // while uvicorn binds IPv4 only — the proxy would 502 on every API call.
  const backend = env.VITE_API_URL || 'http://127.0.0.1:8000';

  const apiProxy = { target: backend, changeOrigin: true };
  const wsProxy = {
    target: backend.replace(/^http/, 'ws'),
    ws: true,
    changeOrigin: true,
  };

  return {
    plugins: [react()],

    resolve: {
      alias: {
        '@': path.resolve(__dirname, './src'),
      },
    },

    server: {
      port: 3000,
      open: false,
      proxy: {
        '^/auth/.+': apiProxy,
        '^/shops($|/)': apiProxy,
        '^/lots($|/)': apiProxy,
        '^/volunteers($|/)': apiProxy,
        '^/needy/.+': apiProxy,
        '^/admin/.+': apiProxy,
        '^/stats$': apiProxy,
        '^/uploads/': apiProxy,
        '^/needy_uploads/': apiProxy,
        '^/volunteer_uploads/': apiProxy,
        '^/telegram/': apiProxy,
        '^/ws/': wsProxy,
      },
    },

    build: {
      outDir: 'build',
      sourcemap: false,
      rollupOptions: {
        output: {
          manualChunks(id) {
            if (!id.includes('node_modules')) return;
            if (/[\\/]node_modules[\\/](react|react-dom|react-router|react-router-dom|scheduler)[\\/]/.test(id)) {
              return 'vendor';
            }
            if (/[\\/]node_modules[\\/](i18next|react-i18next|i18next-browser-languagedetector)[\\/]/.test(id)) {
              return 'i18n';
            }
            if (id.includes('@pbe/react-yandex-maps')) {
              return 'maps';
            }
            if (/[\\/]node_modules[\\/]recharts[\\/]/.test(id)) {
              return 'charts';
            }
          },
        },
      },
    },

    test: {
      globals: true,
      environment: 'jsdom',
      setupFiles: [],
    },
  };
});
