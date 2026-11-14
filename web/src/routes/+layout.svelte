<script lang="ts">
  import '$lib/design/app.css';
  import { onMount } from 'svelte';
  import { dev } from '$app/environment';

  let { children } = $props();

  onMount(() => {
    if (dev || !('serviceWorker' in navigator)) return;
    import('virtual:pwa-register').then(({ registerSW }) => registerSW({ immediate: true })).catch(() => {});
  });
</script>

{@render children()}
