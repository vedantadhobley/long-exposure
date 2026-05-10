import { defineConfig } from 'vite';
import { svelte } from '@sveltejs/vite-plugin-svelte';

export default defineConfig({
  plugins: [svelte()],
  server: {
    host: '0.0.0.0',
    port: 3000,
    // In dev, the SPA hits /api/* via Caddy on the same hostname (same-origin).
    // In container-only dev (no Caddy), proxy directly to the api container:
    proxy: {
      '/api': {
        target: 'http://long-exposure-dev-api:3001',
        changeOrigin: true,
      },
    },
  },
});
