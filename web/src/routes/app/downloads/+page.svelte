<script lang="ts">
  import { onMount } from 'svelte';
  import { base } from '$app/paths';
  import DownloadDetailsSheet from '$lib/download/DownloadDetailsSheet.svelte';
  import { downloads } from '$lib/download/manager.svelte';
  import { formatBytes, formatDuration, formatSpeed, percent } from '$lib/download/math';
  import { isIOS } from '$lib/download/store';
  import { tabOf, type Tab } from '$lib/download/types';
  import { imgFor } from '$lib/ui/img';

  const TABS: { k: Tab; l: string }[] = [
    { k: 'active', l: 'Active' },
    { k: 'completed', l: 'Completed' },
    { k: 'failed', l: 'Failed' }
  ];
  let tab = $state<Tab>('active');
  let openId = $state<string | null>(null);
  let detailsOpen = $state(false);
  let ios = $state(false);
  let persistMsg = $state('');

  onMount(() => {
    ios = isIOS(navigator.userAgent, navigator.maxTouchPoints);
    void downloads.init().then(() => downloads.refreshQuota());
  });

  const all = $derived(downloads.list);
  const counts = $derived({
    active: all.filter((r) => tabOf(r.status) === 'active').length,
    completed: all.filter((r) => tabOf(r.status) === 'completed').length,
    failed: all.filter((r) => tabOf(r.status) === 'failed').length
  });
  const shown = $derived(all.filter((r) => tabOf(r.status) === tab));
  const q = $derived(downloads.quota);
  const mine = $derived(downloads.usedBytes);
  const other = $derived(Math.max(0, q.usage - mine));
  const free = $derived(Math.max(0, q.quota - q.usage));
  const pctOf = (n: number) => (q.quota ? Math.min(100, (n / q.quota) * 100) : 0);
  const statusText: Record<string, string> = { queued: 'Queued', running: 'Downloading', paused: 'Paused', completed: 'Downloaded', failed: 'Failed', canceled: 'Canceled' };

  function open(id: string) {
    openId = id;
    detailsOpen = true;
  }
  async function persist() {
    const ok = await downloads.persistStorage();
    persistMsg = ok ? 'Storage is now persistent.' : 'The browser declined — install the app (Add to Home Screen) and try again.';
  }
  async function clearTab() {
    for (const r of shown) await downloads.remove(r.id);
  }
</script>

<svelte:head><title>Downloads · Cinetor</title></svelte:head>

<div class="dl-page">
  <header class="head">
    <a class="back" href="{base}/app/me" aria-label="Back to Me">‹</a>
    <h1>Downloads</h1>
  </header>

  {#if ios}
    <p class="banner" role="note">
      <b>iPhone / iPad:</b> Safari limits web storage and may clear offline downloads after a few weeks of not using the app. Add Cinetor to your Home Screen for more reliable storage.
    </p>
  {/if}

  <section class="storage glass" aria-label="Storage">
    <div class="sbar" role="img" aria-label="Storage: {formatBytes(mine)} Cinetor downloads, {formatBytes(other)} other, {formatBytes(free)} free">
      <i class="mine" style:width="{pctOf(mine)}%"></i>
      <i class="other" style:width="{pctOf(other)}%"></i>
    </div>
    <div class="legend">
      <span><i class="dot mine"></i>Downloads {formatBytes(mine)}</span>
      <span><i class="dot other"></i>Other app data {formatBytes(other)}</span>
      <span><i class="dot"></i>Free {q.quota ? formatBytes(free) : 'unknown'}</span>
    </div>
    <div class="persist">
      {#if q.persisted}
        <span class="ok">● Persistent storage — the browser won't evict downloads.</span>
      {:else}
        <span class="warn">● Best-effort storage — may be cleared when the device is low on space.</span>
        <button class="chip" onclick={persist}>Make persistent</button>
      {/if}
    </div>
    {#if persistMsg}<p class="pmsg" role="status">{persistMsg}</p>{/if}
    <div class="settings">
      <label>
        Simultaneous downloads
        <select value={downloads.maxConcurrent} onchange={(e) => downloads.setMaxConcurrent(Number(e.currentTarget.value))}>
          {#each [1, 2, 3, 4, 5] as n (n)}<option value={n}>{n}</option>{/each}
        </select>
      </label>
      <label>
        Connections per file
        <select value={downloads.defaultConnections} onchange={(e) => downloads.setDefaultConnections(Number(e.currentTarget.value))}>
          {#each [1, 2, 3, 4, 5, 6, 7, 8] as n (n)}<option value={n}>{n}</option>{/each}
        </select>
      </label>
    </div>
  </section>

  <div class="h-scroll tabs" role="tablist" aria-label="Download status">
    {#each TABS as t (t.k)}
      <button class="chip" role="tab" aria-selected={tab === t.k} aria-pressed={tab === t.k} onclick={() => (tab = t.k)}>{t.l} <span class="n">{counts[t.k]}</span></button>
    {/each}
    {#if tab !== 'active' && shown.length}<button class="chip clear" onclick={clearTab}>Clear all</button>{/if}
  </div>

  {#if !downloads.loaded}
    <p class="empty">Loading…</p>
  {:else if !shown.length}
    <div class="empty glass">
      <p>{tab === 'active' ? 'Nothing downloading.' : tab === 'completed' ? 'No downloads yet.' : 'No failed downloads.'}</p>
      <p class="muted">Tap <b>Download</b> on any movie or episode to watch it offline.</p>
      <a class="btn btn-ghost" href="{base}/app">Browse</a>
    </div>
  {:else}
    <ul class="list">
      {#each shown as r (r.id)}
        {@const live = downloads.live[r.id]}
        {@const total = r.totalBytes || r.estimatedBytes}
        {@const p = r.kind === 'adaptive' ? (r.fraction ?? 0) * 100 : percent(r.doneBytes, total)}
        {@const img = r.poster ? imgFor(r.poster, 'poster') : null}
        <li>
          <button class="row glass" onclick={() => open(r.id)}>
            {#if img}<img src={img.src} alt="" width="54" height="80" loading="lazy" />{:else}<span class="ph"></span>{/if}
            <span class="txt">
              <b>{r.title}</b>
              {#if r.subtitle}<small>{r.subtitle}</small>{/if}
              <small class="st s-{r.status}">
                {statusText[r.status]}
                {#if r.status === 'running'}· {p.toFixed(0)}% · {formatSpeed(live?.speed ?? 0)} · ETA {formatDuration(live?.eta)}{/if}
                {#if r.status === 'paused'}· {p.toFixed(0)}%{/if}
                {#if r.status === 'completed'}· {formatBytes(r.totalBytes)} · {r.tracks.quality}{/if}
                {#if r.status === 'failed' && r.lastError}· {r.lastError}{/if}
              </small>
              {#if r.status !== 'completed'}<span class="bar"><i style:width="{p}%"></i></span>{/if}
            </span>
          </button>
          <span class="quick">
            {#if r.status === 'running' || r.status === 'queued'}<button class="qb" onclick={() => downloads.pause(r.id)} aria-label="Pause">❚❚</button>{/if}
            {#if r.status === 'paused'}<button class="qb" onclick={() => downloads.resume(r.id)} aria-label="Resume">▶</button>{/if}
            {#if r.status === 'failed' || r.status === 'canceled'}<button class="qb" onclick={() => downloads.retry(r.id)} aria-label="Retry">↻</button>{/if}
          </span>
        </li>
      {/each}
    </ul>
  {/if}
</div>

{#if openId && detailsOpen}<DownloadDetailsSheet bind:open={detailsOpen} id={openId} />{/if}

<style>
  .dl-page {
    max-width: 920px;
  }
  .head {
    display: flex;
    align-items: center;
    gap: 10px;
    margin: 0 16px 12px;
  }
  .back {
    display: grid;
    place-items: center;
    width: 40px;
    height: 40px;
    border-radius: 50%;
    background: var(--glass-fill);
    text-decoration: none;
    font-size: 1.6rem;
    line-height: 1;
  }
  h1 {
    margin: 0;
    font-size: 1.5rem;
  }
  .banner {
    margin: 0 16px 12px;
    padding: 10px 14px;
    border-radius: 16px;
    background: color-mix(in srgb, #f5a623 15%, transparent);
    box-shadow: inset 0 0 0 1px color-mix(in srgb, #f5a623 35%, transparent);
    font-size: 0.85rem;
  }
  .storage {
    margin: 0 16px;
    padding: 16px;
  }
  .sbar {
    display: flex;
    height: 12px;
    border-radius: 99px;
    background: rgb(255 255 255 / 0.1);
    overflow: hidden;
  }
  .sbar i {
    display: block;
    height: 100%;
  }
  .mine {
    background: linear-gradient(90deg, var(--accent), var(--accent-end));
  }
  .other {
    background: rgb(255 255 255 / 0.35);
  }
  .legend {
    display: flex;
    flex-wrap: wrap;
    gap: 6px 16px;
    margin-top: 10px;
    font-size: 0.8rem;
    color: var(--text-secondary);
  }
  .dot {
    display: inline-block;
    width: 9px;
    height: 9px;
    border-radius: 50%;
    margin-right: 6px;
    background: rgb(255 255 255 / 0.1);
  }
  .persist {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px 12px;
    margin-top: 10px;
    font-size: 0.8rem;
  }
  .ok {
    color: var(--success);
  }
  .warn {
    color: #f5c06a;
  }
  .pmsg {
    margin: 6px 0 0;
    font-size: 0.78rem;
    color: var(--text-secondary);
  }
  .settings {
    display: flex;
    flex-wrap: wrap;
    gap: 10px 20px;
    margin-top: 12px;
    font-size: 0.85rem;
    color: var(--text-secondary);
  }
  .settings select {
    margin-left: 8px;
    padding: 4px 8px;
    border-radius: 10px;
    border: 0;
    background: var(--glass-fill);
    color: var(--text-primary);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
  }
  .tabs {
    margin-top: 14px;
  }
  .n {
    margin-left: 6px;
    opacity: 0.75;
    font-variant-numeric: tabular-nums;
  }
  .clear {
    margin-left: auto;
  }
  .empty {
    margin: 12px 16px;
    padding: 24px;
    text-align: center;
  }
  .muted {
    color: var(--text-secondary);
    font-size: 0.85rem;
  }
  .list {
    list-style: none;
    margin: 0;
    padding: 0 16px;
    display: grid;
    gap: 10px;
  }
  li {
    position: relative;
  }
  .row {
    width: 100%;
    display: flex;
    gap: 12px;
    align-items: center;
    padding: 10px 56px 10px 10px;
    border: 0;
    text-align: left;
    cursor: pointer;
  }
  .row img,
  .ph {
    flex: none;
    width: 54px;
    height: 80px;
    object-fit: cover;
    border-radius: 10px;
    background: var(--surface);
  }
  .txt {
    display: grid;
    gap: 2px;
    min-width: 0;
    flex: 1;
  }
  .txt b,
  .txt small {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .txt small {
    color: var(--text-secondary);
    font-size: 0.78rem;
  }
  .st.s-running {
    color: var(--accent);
  }
  .st.s-completed {
    color: var(--success);
  }
  .st.s-failed,
  .st.s-canceled {
    color: #ff8a8a;
  }
  .bar {
    display: block;
    height: 5px;
    margin-top: 4px;
    border-radius: 99px;
    background: var(--glass-fill);
    overflow: hidden;
  }
  .bar i {
    display: block;
    height: 100%;
    background: linear-gradient(90deg, var(--accent), var(--accent-end));
    transition: width 0.5s linear;
  }
  .quick {
    position: absolute;
    right: 10px;
    top: 50%;
    transform: translateY(-50%);
  }
  .qb {
    width: 40px;
    height: 40px;
    border: 0;
    border-radius: 50%;
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
    cursor: pointer;
    font-size: 0.8rem;
  }
</style>
