import { sveltekit } from '@sveltejs/kit/vite';
import { SvelteKitPWA } from '@vite-pwa/sveltekit';
import { defineConfig } from 'vite';

const base = (process.env.BASE_PATH ?? '').replace(/\/$/, '');
const scope = `${base}/`;
const esc = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

export default defineConfig({
  // TMDB v4 read token baked in at build (CI secret TMDB_TOKEN → PUBLIC_TMDB_TOKEN). Empty → mock catalog.
  define: { __TMDB_TOKEN__: JSON.stringify(process.env.PUBLIC_TMDB_TOKEN ?? '') },
  worker: { format: 'es' },
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
          { name: 'Search', short_name: 'Search', description: 'Search movies, series, anime and drama', url: `${base}/app/search`, icons: [{ src: `${base}/brand/icon-192.png`, sizes: '192x192', type: 'image/png' }] },
          { name: 'Live TV', short_name: 'Live TV', description: 'Live channels, sports and the guide', url: `${base}/app/tv`, icons: [{ src: `${base}/brand/icon-192.png`, sizes: '192x192', type: 'image/png' }] },
          { name: 'Music', short_name: 'Music', description: 'Albums, moods and tracks', url: `${base}/app/music`, icons: [{ src: `${base}/brand/icon-192.png`, sizes: '192x192', type: 'image/png' }] },
          { name: 'Downloads', short_name: 'Downloads', description: 'Offline movies and episodes', url: `${base}/app/downloads`, icons: [{ src: `${base}/brand/icon-192.png`, sizes: '192x192', type: 'image/png' }] }
        ],
        screenshots: [
          { src: `${base}/screens/phone-music.jpg`, sizes: '780x1688', type: 'image/jpeg', form_factor: 'narrow', label: 'Music space' },
          { src: `${base}/screens/phone-livetv.jpg`, sizes: '780x1688', type: 'image/jpeg', form_factor: 'narrow', label: 'Live TV & Sports' },
          { src: `${base}/screens/phone-comics.jpg`, sizes: '780x1688', type: 'image/jpeg', form_factor: 'narrow', label: 'Comics' },
          { src: `${base}/screens/phone-downloads.jpg`, sizes: '780x1688', type: 'image/jpeg', form_factor: 'narrow', label: 'Offline downloads' },
          { src: `${base}/screens/wide-details.jpg`, sizes: '1920x1080', type: 'image/jpeg', form_factor: 'wide', label: 'Title details' },
          { src: `${base}/screens/wide-music.jpg`, sizes: '1920x1080', type: 'image/jpeg', form_factor: 'wide', label: 'Music on desktop' }
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
        // The lazy shaka-player chunk (~780 KB) is NOT precached (runtime-cached on first use below).
        maximumFileSizeToCacheInBytes: 400 * 1024,
        navigateFallback: `${base}/app`,
        navigateFallbackAllowlist: [new RegExp(`^${esc(base)}/app(/|$)`)],
        cleanupOutdatedCaches: true,
        runtimeCaching: [
          {
            // Non-app navigations (landing etc.) offline → offline page. /app/* uses the SPA shell above.
            // Built with new Function so the base path is inlined (workbox serializes the function source; closures are lost).
            urlPattern: new Function('o', `return o.request.mode === 'navigate' && !o.url.pathname.startsWith(${JSON.stringify(`${base}/app`)})`) as (o: { request: Request; url: URL }) => boolean,
            handler: 'NetworkOnly',
            options: { precacheFallback: { fallbackURL: `${base}/offline` } }
          },
          {
            // Hashed immutable chunks not in the precache (shaka player) → cache on first use.
            urlPattern: /\/_app\/immutable\/.+\.js$/,
            handler: 'CacheFirst',
            options: { cacheName: 'cinetor-chunks', expiration: { maxEntries: 20, maxAgeSeconds: 30 * 24 * 3600 }, cacheableResponse: { statuses: [200] } }
          },
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
