import { defineConfig } from 'vite';

/**
 * Dev/build configuration.
 *
 * Notes for contributors:
 *  - `base: './'` makes the build work from any sub-path (GitHub Pages, a file
 *    server on the phone itself, Capacitor/TWA asset roots).
 *  - The PWA service worker lets the app open with no network at all, which is
 *    the whole point of an offline PSD editor.
 */
export default defineConfig({
  base: './',
  server: {
    host: '0.0.0.0',
    port: 5173,
    strictPort: false,
    // the app must be reachable through the browser preview host (*.e2b.app)
    allowedHosts: true,
    cors: true,
  },
  preview: {
    host: '0.0.0.0',
    port: 4173,
    allowedHosts: true,
  },
  build: {
    target: 'es2022',
    sourcemap: false,
    chunkSizeWarningLimit: 700,
    rollupOptions: {
      output: {
        manualChunks: {
          psd: ['ag-psd'],
        },
      },
    },
  },
  publicDir: 'public',
  worker: { format: 'es' },
});
