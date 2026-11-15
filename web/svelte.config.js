import adapter from '@sveltejs/adapter-static';
import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';

const base = (process.env.BASE_PATH ?? '').replace(/\/$/, '');

/** @type {import('@sveltejs/kit').Config} */
const config = {
  preprocess: vitePreprocess(),
  kit: {
    adapter: adapter({ pages: 'build', assets: 'build', fallback: '404.html', precompress: false, strict: true }),
    paths: { base, relative: false },
    serviceWorker: { register: false },
    prerender: {
      entries: [
        '*',
        '/app',
        '/app/search',
        '/app/title',
        '/app/watch',
        '/app/me',
        '/app/downloads',
        '/app/music/album',
        '/app/comics/title',
        '/app/comics/read',
        '/app/library',
        '/app/settings',
        '/app/about',
        '/app/anime',
        '/app/drama',
        '/app/tv',
        '/app/music',
        '/app/comics',
        '/app/adult',
        '/app/tv/search',
        '/app/music/search',
        '/app/comics/search',
        '/app/adult/search',
        '/app/anime/search',
        '/app/drama/search'
      ]
    }
  }
};

export default config;
