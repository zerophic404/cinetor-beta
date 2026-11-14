import { sveltekit } from '@sveltejs/kit/vite';
import { SvelteKitPWA } from '@vite-pwa/sveltekit';
import { defineConfig } from 'vite';

const base = (process.env.BASE_PATH ?? '').replace(/\/$/, '');
const scope = `${base}/`;
const esc = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

export default defineConfig({
  plugins: [
    sveltekit(),
    SvelteKitPWA({
      registerType: 'autoUpdate',
      injectRegister: false,
      scope,
      base: scope,
      manifestFilename: 'manifest.webmanifest',
      manifest: {
        id: `${base}/app`,
        name: 'Cinetor',
        short_name: 'Cinetor',
        description: 'Movies, series, anime, drama, live TV, music and comics — in one red/black glass app.',
        start_url: `${base}/app`,
        scope,
        display: 'standalone',
        orientation: 'any',
        background_color: '#000000',
        theme_color: '#000000',
        categories: ['entertainment', 'video'],
        icons: [
          { src: `${base}/brand/icon-192.png`, sizes: '192x192', type: 'image/png', purpose: 'any' },
          { src: `${base}/brand/icon-512.png`, sizes: '512x512', type: 'image/png', purpose: 'any' },
          { src: `${base}/brand/maskable-512.png`, sizes: '512x512', type: 'image/png', purpose: 'maskable' }
        ],
        shortcuts: [
          { name: 'Search', url: `${base}/app/search` },
          { name: 'Live TV', url: `${base}/app/tv` },
          { name: 'Music', url: `${base}/app/music` }
        ]
      },
      kit: { trailingSlash: 'never' },
      workbox: {
        // App shell only — never the 30 MB of mock images / iOS splash PNGs.
        globPatterns: [
          'client/_app/**/*.{js,css}',
          'client/favicon.svg',
          'client/brand/{favicon.svg,icon-192.png,icon-512.png,maskable-512.png}',
          'prerendered/**/*.html'
        ],
        navigateFallback: `${base}/app`,
        navigateFallbackAllowlist: [new RegExp(`^${esc(base)}/app(/|$)`)],
        cleanupOutdatedCaches: true,
        runtimeCaching: [
          {
            // NEVER cache media (video segments / manifests / progressive files / audio).
            urlPattern: /\.(?:m3u8|mpd|mp4|m4s|m4a|ts|webm|mkv|mp3)(?:[?#].*)?$/i,
            handler: 'NetworkOnly'
          },
          {
            urlPattern: /\/mock\/[^/]+\.json(?:[?#].*)?$/i,
            handler: 'NetworkFirst',
            options: {
              cacheName: 'cinetor-mock',
              networkTimeoutSeconds: 4,
              expiration: { maxEntries: 20, maxAgeSeconds: 7 * 24 * 3600 }
            }
          },
          {
            urlPattern: ({ request }) => request.destination === 'image',
            handler: 'StaleWhileRevalidate',
            options: {
              cacheName: 'cinetor-images',
              expiration: { maxEntries: 300, maxAgeSeconds: 7 * 24 * 3600 },
              cacheableResponse: { statuses: [0, 200] }
            }
          }
        ]
      }
    })
  ]
});
