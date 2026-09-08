import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { VitePWA } from 'vite-plugin-pwa'

export default defineConfig({
  // #579: the shared test setup (jsdom localStorage fallback — see src/test/setup.ts).
  test: {
    setupFiles: ['./src/test/setup.ts'],
  },
  server: {
    proxy: {
      // spec-43/T3: у dev /api йде на локальний wrangler dev (порт 8787).
      '/api': 'http://127.0.0.1:8787',
    },
  },
  plugins: [
    react(),
    VitePWA({
      registerType: 'autoUpdate',
      // W6.2: injectManifest — the custom service worker (src/sw.ts) owns
      // the offline streaming-cache fallback; precaching still comes from
      // workbox. v0.20 resolves the PROD build entry from srcDir+filename
      // (injectManifest.swSrc serves the dev plugin); swDest derives as
      // dist/sw.js.
      strategies: 'injectManifest',
      srcDir: 'src',
      filename: 'sw.ts',
      injectManifest: {
        swSrc: 'src/sw.ts',
        // The vite-built bundle path (renamed sw.mjs → sw.js in dist) that
        // workbox injects the precache manifest into.
        swDest: 'dist/sw.js',
      },
      devOptions: {
        enabled: true,
        type: 'module',
      },
      includeAssets: ['icons/icon-192.png', 'icons/icon-512.png'],
      manifest: {
        name: 'Слухайка — аудіокниги українською',
        short_name: 'Слухайка',
        description: 'Аудіокниги українською з відкритих джерел, без реклами.',
        lang: 'uk',
        display: 'standalone',
        orientation: 'portrait',
        background_color: '#121212',
        theme_color: '#121212',
        icons: [
          { src: '/icons/icon-192.png', sizes: '192x192', type: 'image/png' },
          { src: '/icons/icon-512.png', sizes: '512x512', type: 'image/png' },
          { src: '/icons/icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
        ],
      },
    }),
  ],
})
