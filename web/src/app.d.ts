/// <reference types="vite-plugin-pwa/client" />
// See https://svelte.dev/docs/kit/types#app.d.ts
declare global {
  /** Injected by vite.config.ts `define` (may be ''). */
  const __TMDB_TOKEN__: string;
  const __GATEWAY_SECRET__: string;
  namespace App {}
  interface Window {
    __cinetorReady?: () => void;
  }
}

export {};
