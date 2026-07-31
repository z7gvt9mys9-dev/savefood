import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  // 127.0.0.1 (not localhost): Node 17+ may resolve localhost to ::1 first,
  // while uvicorn binds IPv4 only — the proxy would 502 on every API call.
  const backend = env.VITE_API_URL || 'http://127.0.0.1:8000';

  const apiProxy = { target: backend, changeOrigin: true };
  // Optional Go microservice (geows) for the hot paths: set VITE_GO_URL
  // (e.g. http://127.0.0.1:8001) to route /ws/ and volunteer location to it
  // in dev, mirroring the prod nginx layout. Unset → Python handles them.
  const goBackend = env.VITE_GO_URL || '';
  const hotProxy = goBackend ? { target: goBackend, changeOrigin: true } : apiProxy;
  const wsProxy = {
    target: (goBackend || backend).replace(/^http/, 'ws'),
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
      // Cloudflare quick tunnels get a random *.trycloudflare.com hostname;
      // without this Vite rejects the Host header and remote access breaks.
      allowedHosts: ['.trycloudflare.com'],
      proxy: {
        // hot paths first — order matters, the generic /volunteers rule below
        // would otherwise swallow the location endpoint
        '^/volunteers/\\d+/location$': hotProxy,
        '^/auth/.+': apiProxy,
        '^/shops($|/)': apiProxy,
        '^/lots($|/)': apiProxy,
        '^/volunteers($|/)': apiProxy,
        '^/needy/.+': apiProxy,
        '^/admin/.+': apiProxy,
        '^/stats$': apiProxy,
        '^/impact/': apiProxy,
        '^/push/': apiProxy,
        '^/api/': apiProxy,
        // In-app ticket chat (§53) — the same path nginx used to miss.
        '^/tickets/': apiProxy,
        '^/uploads/': apiProxy,
        '^/needy_uploads/': apiProxy,
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
