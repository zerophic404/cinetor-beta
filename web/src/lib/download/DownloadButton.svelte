<script lang="ts">
  import { onMount } from 'svelte';
  import type { Media } from '$lib/player/sources';
  import DownloadDetailsSheet from './DownloadDetailsSheet.svelte';
  import DownloadOptionsSheet from './DownloadOptionsSheet.svelte';
  import { canDownload, downloads } from './manager.svelte';
  import { percent } from './math';

  let { media, compact = false }: { media: Media | undefined; compact?: boolean } = $props();

  let optionsOpen = $state(false);
  let detailsOpen = $state(false);
  onMount(() => void downloads.init());

  const ok = $derived(!!media && canDownload(media));
  const r = $derived(media ? downloads.get(media.key) : undefined);
  const pct = $derived(r ? (r.kind === 'adaptive' ? (r.fraction ?? 0) * 100 : percent(r.doneBytes, r.totalBytes || r.estimatedBytes)) : 0);
  const st = $derived(!r || r.status === 'canceled' ? 'idle' : r.status);
  const label = $derived(
    ({ idle: 'Download', queued: 'Queued', running: `${Math.floor(pct)}%`, paused: `Paused ${Math.floor(pct)}%`, completed: 'Downloaded', failed: 'Retry download' } as Record<string, string>)[st] ?? 'Download'
  );

  function click(e: MouseEvent) {
    e.preventDefault();
    e.stopPropagation();
    if (!media || !ok) return;
    if (st === 'idle') optionsOpen = true;
    else detailsOpen = true;
  }
</script>

{#if media && ok}
  <button
    class={compact ? 'dl-mini' : 'btn btn-ghost dl'}
    data-state={st}
    onclick={click}
    aria-label={compact ? `${label}: ${media.subtitle ?? media.title}` : undefined}
    title={label}
    style:--p="{pct}%"
  >
    <span class="ic" aria-hidden="true">
      {#if st === 'completed'}
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"><path d="M5 12.5l4.5 4.5L19 7.5" /></svg>
      {:else if st === 'running' || st === 'queued' || st === 'paused'}
        <span class="ring"></span>
        {#if st === 'paused'}<svg class="in" width="10" height="10" viewBox="0 0 10 10"><path fill="currentColor" d="M2 1h2v8H2zM6 1h2v8H6z" /></svg>{/if}
      {:else if st === 'failed'}
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 12a8 8 0 1 0 2.4-5.7M4 4v4h4" /></svg>
      {:else}
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 4v11m0 0l-4.5-4.5M12 15l4.5-4.5M5 19.5h14" /></svg>
      {/if}
    </span>
    {#if !compact}<span>{label}</span>{/if}
  </button>
  {#if optionsOpen}<DownloadOptionsSheet bind:open={optionsOpen} {media} onstarted={() => (detailsOpen = false)} />{/if}
  {#if detailsOpen && r}<DownloadDetailsSheet bind:open={detailsOpen} id={r.id} />{/if}
{:else if media && !compact && media.item.space !== 'TV'}
  <button class="btn btn-ghost dl" disabled title="This source format can't be saved offline">Download unavailable</button>
{/if}

<style>
  .dl[data-state='completed'] {
    color: var(--success);
  }
  .dl[data-state='failed'] {
    color: #ff8a8a;
  }
  .dl[disabled] {
    opacity: 0.55;
    cursor: not-allowed;
  }
  .ic {
    position: relative;
    display: inline-grid;
    place-items: center;
    width: 20px;
    height: 20px;
  }
  .ring {
    width: 18px;
    height: 18px;
    border-radius: 50%;
    background: conic-gradient(var(--accent) var(--p), rgb(255 255 255 / 0.15) 0);
    -webkit-mask: radial-gradient(circle, transparent 5px, #000 5.5px);
    mask: radial-gradient(circle, transparent 5px, #000 5.5px);
  }
  [data-state='queued'] .ring {
    background: conic-gradient(rgb(255 255 255 / 0.5) 25%, rgb(255 255 255 / 0.15) 0);
    animation: spin 1.2s linear infinite;
  }
  .in {
    position: absolute;
  }
  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }
  .dl-mini {
    flex: none;
    display: grid;
    place-items: center;
    width: 44px;
    height: 44px;
    border: 0;
    border-radius: 50%;
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
    cursor: pointer;
  }
  .dl-mini[data-state='completed'] {
    color: var(--success);
  }
  .dl-mini[data-state='failed'] {
    color: #ff8a8a;
  }
</style>
