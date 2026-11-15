<script lang="ts">
  import '$lib/design/app.css';
  import { onMount } from 'svelte';
  import { dev } from '$app/environment';
  import { install } from '$lib/pwa/install.svelte';
  import { isTauriEnv } from '$lib/desktop/env';
  import { desktop } from '$lib/desktop/desktop.svelte';
  import TitleBar from '$lib/desktop/TitleBar.svelte';

  let { children } = $props();

  onMount(() => {
    // Desktop app (Tauri): frameless window + native shell; no service worker / install prompt.
    if (isTauriEnv()) {
      void desktop.init();
      return;
    }
    install.wire();
    if (dev || !('serviceWorker' in navigator)) return;
    import('virtual:pwa-register').then(({ registerSW }) => registerSW({ immediate: true })).catch(() => {});
  });
</script>

{#if desktop.isTauri && !desktop.mini}<TitleBar />{/if}
{@render children()}

<style>
  /* Desktop: content starts below the 36 px custom title bar (every screen pads with --safe-t). */
  :global(html.tauri) {
    --titlebar-h: 36px;
    --safe-t: var(--titlebar-h);
  }
  /* Windows Mica/Acrylic shows through a translucent page background. */
  :global(html.tauri-glass),
  :global(html.tauri-glass body) {
    background: rgb(0 0 0 / 0.6) !important;
  }
</style>
