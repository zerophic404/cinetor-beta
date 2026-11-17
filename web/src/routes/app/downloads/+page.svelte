<script lang="ts">
  // This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer,
  // scrape or hack anything without Mythbrix's explicit permission.
  import { onMount } from 'svelte';
  import { base } from '$app/paths';
  import Sheet from '$lib/components/Sheet.svelte';
  import DownloadDetailsSheet from '$lib/download/DownloadDetailsSheet.svelte';
  import DeleteDownloadDialog from '$lib/download/DeleteDownloadDialog.svelte';
  import { groupDownloadsBySeries } from '$lib/download/batch';
  import { downloads } from '$lib/download/manager.svelte';
  import { formatBytes, formatDuration, formatSpeed, percent } from '$lib/download/math';
  import { isIOS } from '$lib/download/store';
  import { tabOf, type DownloadRecord, type Tab } from '$lib/download/types';
  import { isTauriEnv, locationInfo, saveCopySupported } from '$lib/desktop/env';
  import { GENERIC, userMessage } from '$lib/errors';
  import { toasts } from '$lib/ui/toast.svelte';
  import { imgFor } from '$lib/ui/img';

  const TABS: { k: Tab; l: string }[] = [
    { k: 'active', l: 'Active' },
    { k: 'completed', l: 'Completed' },
    { k: 'failed', l: 'Failed' }
  ];
  let tab = $state<Tab>('active');
  let openId = $state<string | null>(null);
  let detailsOpen = $state(false);
  let delId = $state<string | null>(null);
  let delOpen = $state(false);
  let ios = $state(false);
  let persistMsg = $state('');
  let tauri = $state(false);
  let dir = $state<string | null>(null);
  let picking = $state(false);

  // Batch selection & confirmation state
  let selectMode = $state(false);
  let selectedIds = $state<Set<string>>(new Set());
  let collapsedSeries = $state<Set<string>>(new Set());
  let batchDelOpen = $state(false);
  let batchDelIds = $state<string[]>([]);
  let batchDelLabel = $state('');
  let batchCancelOpen = $state(false);
  let batchCancelIds = $state<string[]>([]);
  let batchCancelLabel = $state('');
  let batchBusy = $state(false);

  const loc = $derived(locationInfo({ tauri, dir, saveCopy: saveCopySupported() }));

  onMount(() => {
    ios = isIOS(navigator.userAgent, navigator.maxTouchPoints);
    tauri = isTauriEnv();
    void downloads.init().then(() => downloads.refreshQuota());
    if (tauri) void loadDir();
  });

  async function loadDir() {
    try {
      const { downloadsDir } = await import('$lib/desktop/native-dl');
      dir = await downloadsDir();
    } catch {
      dir = null;
    }
  }

  /** Native Tauri folder picker; the chosen folder becomes the remembered default (§4). */
  async function changeDir() {
    picking = true;
    try {
      const { pickDownloadsDir } = await import('$lib/desktop/native-dl');
      const picked = await pickDownloadsDir();
      if (picked) {
        dir = picked;
        toasts.show('New downloads will be saved to the folder you chose');
      }
    } catch {
      toasts.show("That folder can't be used");
    } finally {
      picking = false;
    }
  }

  const all = $derived(downloads.list);
  const counts = $derived({
    active: all.filter((r) => tabOf(r.status) === 'active').length,
    completed: all.filter((r) => tabOf(r.status) === 'completed').length,
    failed: all.filter((r) => tabOf(r.status) === 'failed').length
  });
  const shown = $derived(all.filter((r) => tabOf(r.status) === tab));
  const groups = $derived(groupDownloadsBySeries(shown));
  const allPausedInTab = $derived(tab === 'active' && shown.length > 0 && shown.every((r) => r.status === 'paused'));
  const q = $derived(downloads.quota);
  const mine = $derived(downloads.usedBytes);
  const other = $derived(Math.max(0, q.usage - mine));
  const free = $derived(Math.max(0, q.quota - q.usage));
  const pctOf = (n: number) => (q.quota ? Math.min(100, (n / q.quota) * 100) : 0);
  const statusText: Record<string, string> = {
    queued: 'Queued',
    running: 'Downloading',
    paused: 'Paused',
    completed: 'Downloaded',
    failed: 'Failed',
    canceled: 'Canceled'
  };

  // Prune selectedIds whenever visible list changes
  $effect(() => {
    const visibleSet = new Set(shown.map((r) => r.id));
    let changed = false;
    const next = new Set<string>();
    for (const id of selectedIds) {
      if (visibleSet.has(id)) next.add(id);
      else changed = true;
    }
    if (changed) selectedIds = next;
    if (!shown.length && selectMode) selectMode = false;
  });

  function switchTab(nextTab: Tab) {
    tab = nextTab;
    selectMode = false;
    selectedIds = new Set();
  }

  function openDetails(id: string) {
    if (selectMode) {
      toggleSelect(id);
      return;
    }
    openId = id;
    detailsOpen = true;
  }

  function askDelete(id: string) {
    delId = id;
    delOpen = true;
  }

  function toggleSelect(id: string) {
    const next = new Set(selectedIds);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    selectedIds = next;
  }

  function toggleGroupSelect(ids: readonly string[]) {
    const allChosen = ids.length > 0 && ids.every((id) => selectedIds.has(id));
    const next = new Set(selectedIds);
    for (const id of ids) {
      if (allChosen) next.delete(id);
      else next.add(id);
    }
    selectedIds = next;
  }

  function toggleCollapse(key: string) {
    const next = new Set(collapsedSeries);
    if (next.has(key)) next.delete(key);
    else next.add(key);
    collapsedSeries = next;
  }

  function selectAllShown() {
    if (selectedIds.size === shown.length) selectedIds = new Set();
    else selectedIds = new Set(shown.map((r) => r.id));
  }

  function askBatchDelete(ids: readonly string[], label: string) {
    if (!ids.length) return;
    batchDelIds = [...ids];
    batchDelLabel = label;
    batchDelOpen = true;
  }

  function askBatchCancel(ids: readonly string[], label: string) {
    if (!ids.length) return;
    batchCancelIds = [...ids];
    batchCancelLabel = label;
    batchCancelOpen = true;
  }

  async function runBatchDelete(deleteFile: boolean) {
    if (!batchDelIds.length) return;
    batchBusy = true;
    try {
      const n = batchDelIds.length;
      await downloads.removeMany(batchDelIds, { deleteFile });
      batchDelOpen = false;
      selectMode = false;
      selectedIds = new Set();
      toasts.show(deleteFile ? `Deleted ${n} ${n === 1 ? 'download' : 'downloads'}` : `Removed ${n} ${n === 1 ? 'entry' : 'entries'}`);
    } finally {
      batchBusy = false;
    }
  }

  async function runBatchCancel() {
    if (!batchCancelIds.length) return;
    batchBusy = true;
    try {
      const n = batchCancelIds.length;
      await downloads.cancelMany(batchCancelIds);
      batchCancelOpen = false;
      selectMode = false;
      selectedIds = new Set();
      toasts.show(`Canceled ${n} ${n === 1 ? 'download' : 'downloads'}`);
    } finally {
      batchBusy = false;
    }
  }

  async function persist() {
    const ok = await downloads.persistStorage();
    persistMsg = ok ? 'Storage is now persistent.' : 'The browser declined — install the app (Add to Home Screen) and try again.';
  }

  async function clearTab() {
    const ids = shown.map((r) => r.id);
    await downloads.removeMany(ids, { deleteFile: false });
    toasts.show(`Cleared ${ids.length} ${ids.length === 1 ? 'entry' : 'entries'}`);
  }

  function togglePauseAllShown() {
    const ids = shown.map((r) => r.id);
    if (allPausedInTab) {
      downloads.resumeMany(ids);
      toasts.show(`Resumed ${ids.length} downloads`);
    } else {
      downloads.pauseMany(ids);
      toasts.show(`Paused ${ids.length} downloads`);
    }
  }

  function groupPaused(items: readonly { record: DownloadRecord }[]): boolean {
    return items.length > 0 && items.every((x) => x.record.status === 'paused');
  }

  const batchHasExternal = $derived(batchDelIds.some((id) => downloads.hasExternalFile(id)));
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
    <div class="loc">
      <div class="locrow">
        <span class="lk">Save to</span>
        <span class="lv" title={loc.label}>{loc.label}</span>
        {#if loc.changeable}
          <button class="chip" disabled={picking} onclick={changeDir}>{picking ? 'Choosing…' : 'Change…'}</button>
        {/if}
      </div>
      <p class="lnote">{loc.note}</p>
    </div>
  </section>

  <div class="h-scroll tabs" role="tablist" aria-label="Download status">
    {#each TABS as t (t.k)}
      <button class="chip" role="tab" aria-selected={tab === t.k} aria-pressed={tab === t.k} onclick={() => switchTab(t.k)}>
        {t.l} <span class="n">{counts[t.k]}</span>
      </button>
    {/each}
    <span class="tab-spacer"></span>
    {#if tab === 'active' && shown.length > 1}
      <button class="chip" onclick={togglePauseAllShown}>{allPausedInTab ? 'Resume all' : 'Pause all'}</button>
      <button class="chip danger-chip" onclick={() => askBatchCancel(shown.map((r) => r.id), `all ${shown.length} active downloads`)}>
        Cancel all
      </button>
    {/if}
    {#if tab !== 'active' && shown.length}
      <button class="chip" onclick={clearTab}>Clear all</button>
    {/if}
    {#if shown.length > 1}
      <button class="chip" aria-pressed={selectMode} onclick={() => ((selectMode = !selectMode), !selectMode && (selectedIds = new Set()))}>
        {selectMode ? 'Done' : 'Select'}
      </button>
    {/if}
  </div>

  {#if selectMode && shown.length}
    <div class="batch-bar glass" role="region" aria-label="Batch actions">
      <button type="button" class="chip" onclick={selectAllShown}>
        {selectedIds.size === shown.length ? 'Unselect all' : 'Select all'}
      </button>
      <span class="batch-count">{selectedIds.size} selected</span>
      <div class="batch-btns">
        {#if tab === 'active'}
          <button
            type="button"
            class="chip"
            disabled={selectedIds.size === 0}
            onclick={() => downloads.pauseMany([...selectedIds])}
          >
            Pause
          </button>
          <button
            type="button"
            class="chip"
            disabled={selectedIds.size === 0}
            onclick={() => downloads.resumeMany([...selectedIds])}
          >
            Resume
          </button>
          <button
            type="button"
            class="chip danger-chip"
            disabled={selectedIds.size === 0}
            onclick={() => askBatchCancel([...selectedIds], `${selectedIds.size} selected ${selectedIds.size === 1 ? 'download' : 'downloads'}`)}
          >
            Cancel
          </button>
        {/if}
        <button
          type="button"
          class="chip danger-chip"
          disabled={selectedIds.size === 0}
          onclick={() => askBatchDelete([...selectedIds], `${selectedIds.size} selected ${selectedIds.size === 1 ? 'download' : 'downloads'}`)}
        >
          Delete…
        </button>
      </div>
    </div>
  {/if}

  {#if !downloads.loaded}
    <p class="empty">Loading…</p>
  {:else if !shown.length}
    <div class="empty glass">
      <p>{tab === 'active' ? 'Nothing downloading.' : tab === 'completed' ? 'No downloads yet.' : 'No failed downloads.'}</p>
      <p class="muted">Tap <b>Download</b> on any movie or series to watch it offline.</p>
      <a class="btn btn-ghost" href="{base}/app">Browse</a>
    </div>
  {:else}
    <div class="groups">
      {#each groups as g (g.key)}
        {#if g.isSeries && g.items.length > 1}
          {@const groupIds = g.items.map((x) => x.record.id)}
          {@const collapsed = collapsedSeries.has(g.key)}
          {@const paused = groupPaused(g.items)}
          {@const groupChecked = groupIds.every((id) => selectedIds.has(id))}
          {@const gImg = g.poster ? imgFor(g.poster, 'poster') : null}
          <section class="series-group glass" aria-label={g.title}>
            <div class="sg-head">
              {#if selectMode}
                <button
                  type="button"
                  class="sel-box"
                  class:checked={groupChecked}
                  aria-label="Select all episodes of {g.title}"
                  aria-pressed={groupChecked}
                  onclick={() => toggleGroupSelect(groupIds)}
                >
                  {#if groupChecked}
                    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><path d="M5 12.5l4.5 4.5L19 7.5" /></svg>
                  {/if}
                </button>
              {/if}
              <button type="button" class="sg-toggle" aria-expanded={!collapsed} onclick={() => toggleCollapse(g.key)}>
                {#if gImg}<img src={gImg.src} alt="" width="44" height="66" loading="lazy" />{:else}<span class="ph sm"></span>{/if}
                <span class="sg-info">
                  <b>{g.title}</b>
                  <small>
                    {g.items.length} episodes{#if g.seasonSummary} · {g.seasonSummary}{/if}
                    {#if tab === 'completed' && g.totalBytes} · {formatBytes(g.totalBytes)}{/if}
                    {#if tab === 'active'} · {g.progressPct.toFixed(0)}%{/if}
                  </small>
                  {#if tab === 'active'}
                    <span class="bar"><i style:width="{g.progressPct}%"></i></span>
                  {/if}
                </span>
                <svg class="chev" class:collapsed width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                  <path d="M6 9l6 6 6-6" />
                </svg>
              </button>
              <span class="sg-actions">
                {#if tab === 'active'}
                  <button
                    type="button"
                    class="qb"
                    aria-label={paused ? `Resume ${g.title}` : `Pause ${g.title}`}
                    title={paused ? 'Resume series' : 'Pause series'}
                    onclick={() => (paused ? downloads.resumeMany(groupIds) : downloads.pauseMany(groupIds))}
                  >
                    {#if paused}
                      <svg width="15" height="15" viewBox="0 0 24 24" fill="currentColor"><path d="M8 5.5v13l10.5-6.5z" /></svg>
                    {:else}
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M6 5h4v14H6zM14 5h4v14h-4z" /></svg>
                    {/if}
                  </button>
                  <button
                    type="button"
                    class="qb del"
                    aria-label="Cancel {g.title}"
                    title="Cancel series"
                    onclick={() => askBatchCancel(groupIds, `${g.items.length} episodes of ${g.title}`)}
                  >
                    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><path d="M6 6l12 12M18 6L6 18" /></svg>
                  </button>
                {:else}
                  {#if tab === 'failed'}
                    <button
                      type="button"
                      class="qb"
                      aria-label="Retry {g.title}"
                      title="Retry series"
                      onclick={() => groupIds.forEach((id) => downloads.retry(id))}
                    >
                      <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><path d="M4 12a8 8 0 1 0 2.4-5.7M4 4v4h4" /></svg>
                    </button>
                  {/if}
                  <button
                    type="button"
                    class="qb del"
                    aria-label="Delete {g.title}"
                    title="Delete series"
                    onclick={() => askBatchDelete(groupIds, `${g.items.length} episodes of ${g.title}`)}
                  >
                    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                      <path d="M4 7h16M9 7V4h6v3M6.5 7l1 13h9l1-13" />
                    </svg>
                  </button>
                {/if}
              </span>
            </div>

            {#if !collapsed}
              <ul class="ep-sublist">
                {#each g.items as { record: r, meta } (r.id)}
                  {@const live = downloads.live[r.id]}
                  {@const total = r.totalBytes || r.estimatedBytes}
                  {@const p = r.kind === 'adaptive' ? (r.fraction ?? 0) * 100 : percent(r.doneBytes, total)}
                  {@const checked = selectedIds.has(r.id)}
                  <li class="ep-item">
                    <button type="button" class="ep-row-btn" class:checked onclick={() => openDetails(r.id)}>
                      {#if selectMode}
                        <span class="sel-box inline" class:checked aria-hidden="true">
                          {#if checked}
                            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><path d="M5 12.5l4.5 4.5L19 7.5" /></svg>
                          {/if}
                        </span>
                      {/if}
                      <span class="txt">
                        <span class="ep-line">
                          {#if meta.code}<span class="ep-code">{meta.code}</span>{/if}
                          <b>{meta.episodeTitle ?? (r.subtitle || r.title)}</b>
                        </span>
                        <small class="st s-{r.status}">
                          {statusText[r.status]}
                          {#if r.status === 'running'}· {p.toFixed(0)}% · {formatSpeed(live?.speed ?? 0)} · ETA {formatDuration(live?.eta)}{/if}
                          {#if r.status === 'paused'}· {p.toFixed(0)}%{/if}
                          {#if r.status === 'completed'}· {formatBytes(r.totalBytes)} · {r.tracks.quality}{/if}
                          {#if r.status === 'failed' && r.lastError}· {userMessage(new Error(r.lastError), GENERIC.download)}{/if}
                        </small>
                        {#if r.status !== 'completed'}<span class="bar"><i style:width="{p}%"></i></span>{/if}
                      </span>
                    </button>
                    <span class="quick">
                      {#if r.status === 'running' || r.status === 'queued'}
                        <button type="button" class="qb" onclick={() => downloads.pause(r.id)} aria-label="Pause">
                          <svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor"><path d="M6 5h4v14H6zM14 5h4v14h-4z" /></svg>
                        </button>
                      {/if}
                      {#if r.status === 'paused'}
                        <button type="button" class="qb" onclick={() => downloads.resume(r.id)} aria-label="Resume">
                          <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M8 5.5v13l10.5-6.5z" /></svg>
                        </button>
                      {/if}
                      {#if r.status === 'failed' || r.status === 'canceled'}
                        <button type="button" class="qb" onclick={() => downloads.retry(r.id)} aria-label="Retry">
                          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><path d="M4 12a8 8 0 1 0 2.4-5.7M4 4v4h4" /></svg>
                        </button>
                      {/if}
                      <button type="button" class="qb del" onclick={() => askDelete(r.id)} aria-label="Delete {r.title} {meta.code ?? ''}">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                          <path d="M4 7h16M9 7V4h6v3M6.5 7l1 13h9l1-13" />
                        </svg>
                      </button>
                    </span>
                  </li>
                {/each}
              </ul>
            {/if}
          </section>
        {:else}
          {@const { record: r, meta } = g.items[0]}
          {@const live = downloads.live[r.id]}
          {@const total = r.totalBytes || r.estimatedBytes}
          {@const p = r.kind === 'adaptive' ? (r.fraction ?? 0) * 100 : percent(r.doneBytes, total)}
          {@const img = r.poster ? imgFor(r.poster, 'poster') : null}
          {@const checked = selectedIds.has(r.id)}
          <div class="single-wrap">
            <button type="button" class="row glass" class:checked onclick={() => openDetails(r.id)}>
              {#if selectMode}
                <span class="sel-box inline" class:checked aria-hidden="true">
                  {#if checked}
                    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><path d="M5 12.5l4.5 4.5L19 7.5" /></svg>
                  {/if}
                </span>
              {/if}
              {#if img}<img src={img.src} alt="" width="54" height="80" loading="lazy" />{:else}<span class="ph"></span>{/if}
              <span class="txt">
                <b>{r.title}</b>
                {#if meta.code || r.subtitle}
                  <small class="subline">
                    {#if meta.code}<span class="ep-code">{meta.code}</span>{/if}
                    <span>{meta.episodeTitle ?? (!meta.code ? r.subtitle : '')}</span>
                  </small>
                {/if}
                <small class="st s-{r.status}">
                  {statusText[r.status]}
                  {#if r.status === 'running'}· {p.toFixed(0)}% · {formatSpeed(live?.speed ?? 0)} · ETA {formatDuration(live?.eta)}{/if}
                  {#if r.status === 'paused'}· {p.toFixed(0)}%{/if}
                  {#if r.status === 'completed'}· {formatBytes(r.totalBytes)} · {r.tracks.quality}{/if}
                  {#if r.status === 'failed' && r.lastError}· {userMessage(new Error(r.lastError), GENERIC.download)}{/if}
                </small>
                {#if r.status !== 'completed'}<span class="bar"><i style:width="{p}%"></i></span>{/if}
              </span>
            </button>
            <span class="quick">
              {#if r.status === 'running' || r.status === 'queued'}
                <button type="button" class="qb" onclick={() => downloads.pause(r.id)} aria-label="Pause">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M6 5h4v14H6zM14 5h4v14h-4z" /></svg>
                </button>
              {/if}
              {#if r.status === 'paused'}
                <button type="button" class="qb" onclick={() => downloads.resume(r.id)} aria-label="Resume">
                  <svg width="15" height="15" viewBox="0 0 24 24" fill="currentColor"><path d="M8 5.5v13l10.5-6.5z" /></svg>
                </button>
              {/if}
              {#if r.status === 'failed' || r.status === 'canceled'}
                <button type="button" class="qb" onclick={() => downloads.retry(r.id)} aria-label="Retry">
                  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><path d="M4 12a8 8 0 1 0 2.4-5.7M4 4v4h4" /></svg>
                </button>
              {/if}
              <button type="button" class="qb del" onclick={() => askDelete(r.id)} aria-label="Delete {r.title}">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M4 7h16M9 7V4h6v3M6.5 7l1 13h9l1-13" />
                </svg>
              </button>
            </span>
          </div>
        {/if}
      {/each}
    </div>
  {/if}
</div>

{#if openId && detailsOpen}<DownloadDetailsSheet bind:open={detailsOpen} id={openId} />{/if}
{#if delId && delOpen}<DeleteDownloadDialog bind:open={delOpen} id={delId} />{/if}

<Sheet bind:open={batchDelOpen} title="Delete downloads?">
  <p class="sheet-copy">
    Remove <b>{batchDelLabel}</b> from the list, or delete the downloaded files too?
  </p>
  <p class="sheet-muted">
    {#if batchHasExternal}
      Files saved in your desktop download folder can stay on disk if you only remove the list entries.
    {:else}
      On the web, removing downloads frees their private app storage immediately.
    {/if}
  </p>
  <div class="sheet-actions">
    <button type="button" class="btn btn-ghost" disabled={batchBusy} onclick={() => (batchDelOpen = false)}>Cancel</button>
    <button type="button" class="btn btn-ghost" disabled={batchBusy} onclick={() => runBatchDelete(false)}>Remove entries</button>
    <button type="button" class="btn btn-ghost danger-btn" disabled={batchBusy} onclick={() => runBatchDelete(true)}>Delete files</button>
  </div>
</Sheet>

<Sheet bind:open={batchCancelOpen} title="Cancel active downloads?">
  <p class="sheet-copy">
    Cancel <b>{batchCancelLabel}</b>? Partial chunks downloaded so far will be discarded.
  </p>
  <div class="sheet-actions">
    <button type="button" class="btn btn-ghost" disabled={batchBusy} onclick={() => (batchCancelOpen = false)}>Keep downloading</button>
    <button type="button" class="btn btn-ghost danger-btn" disabled={batchBusy} onclick={runBatchCancel}>Cancel downloads</button>
  </div>
</Sheet>

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
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
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
    background: color-mix(in srgb, var(--warning) 15%, transparent);
    box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--warning) 35%, transparent);
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
    background: color-mix(in srgb, var(--text-primary) 10%, transparent);
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
    background: color-mix(in srgb, var(--text-primary) 35%, transparent);
  }
  .legend {
    display: flex;
    flex-wrap: wrap;
    gap: 6px 16px;
    margin-top: 10px;
    font-size: 0.8rem;
    color: var(--text-secondary);
    font-variant-numeric: tabular-nums;
  }
  .dot {
    display: inline-block;
    width: 9px;
    height: 9px;
    border-radius: 50%;
    margin-right: 6px;
    background: color-mix(in srgb, var(--text-primary) 14%, transparent);
  }
  .dot.mine {
    background: var(--accent);
  }
  .dot.other {
    background: color-mix(in srgb, var(--text-primary) 35%, transparent);
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
    color: var(--warning);
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
  .loc {
    margin-top: 12px;
    padding-top: 12px;
    border-top: 1px solid var(--glass-stroke);
  }
  .locrow {
    display: flex;
    align-items: center;
    gap: 10px;
    font-size: 0.85rem;
  }
  .lk {
    color: var(--text-secondary);
    flex: none;
  }
  .lv {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-weight: 700;
  }
  .lnote {
    margin: 6px 0 0;
    font-size: 0.76rem;
    color: var(--text-secondary);
  }
  .tabs {
    margin-top: 14px;
    align-items: center;
  }
  .tab-spacer {
    flex: 1;
  }
  .n {
    margin-left: 6px;
    opacity: 0.75;
    font-variant-numeric: tabular-nums;
  }
  .danger-chip {
    color: var(--error);
  }
  .batch-bar {
    margin: 0 16px 12px;
    padding: 10px 12px;
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px 12px;
  }
  .batch-count {
    font-size: 0.84rem;
    font-weight: 700;
    color: var(--text-secondary);
    font-variant-numeric: tabular-nums;
  }
  .batch-btns {
    margin-left: auto;
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }
  .batch-bar .chip:disabled {
    opacity: 0.45;
    cursor: not-allowed;
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
  .groups {
    padding: 0 16px;
    display: grid;
    gap: 12px;
  }
  .single-wrap {
    position: relative;
  }
  .row {
    width: 100%;
    display: flex;
    gap: 12px;
    align-items: center;
    padding: 10px 104px 10px 10px;
    border: 0;
    text-align: left;
    cursor: pointer;
  }
  .row.checked,
  .ep-row-btn.checked {
    box-shadow: inset 0 0 0 1.5px color-mix(in srgb, var(--accent) 68%, var(--glass-stroke));
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
  .ph.sm,
  .sg-toggle img {
    flex: none;
    width: 44px;
    height: 66px;
    object-fit: cover;
    border-radius: 8px;
    background: var(--surface);
  }
  .series-group {
    padding: 10px;
    display: grid;
    gap: 8px;
  }
  .sg-head {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .sg-toggle {
    flex: 1;
    min-width: 0;
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 2px 4px;
    border: 0;
    background: transparent;
    text-align: left;
    cursor: pointer;
  }
  .sg-info {
    flex: 1;
    min-width: 0;
    display: grid;
    gap: 3px;
    font-variant-numeric: tabular-nums;
  }
  .sg-info b,
  .sg-info small {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .sg-info small {
    color: var(--text-secondary);
    font-size: 0.78rem;
  }
  .chev {
    flex: none;
    color: var(--text-secondary);
    transition: transform calc(var(--dur-short) * var(--dur-scale)) var(--ease-standard);
  }
  .chev.collapsed {
    transform: rotate(-90deg);
  }
  .sg-actions {
    flex: none;
    display: flex;
    gap: 6px;
  }
  .ep-sublist {
    list-style: none;
    margin: 0;
    padding: 6px 0 0;
    border-top: 1px solid var(--glass-stroke);
    display: grid;
    gap: 6px;
  }
  .ep-item {
    position: relative;
  }
  .ep-row-btn {
    width: 100%;
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 9px 98px 9px 10px;
    border: 0;
    border-radius: 12px;
    background: color-mix(in srgb, var(--surface) 45%, var(--glass-fill));
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
    text-align: left;
    cursor: pointer;
  }
  .ep-line,
  .subline {
    display: flex;
    align-items: center;
    gap: 6px;
    min-width: 0;
  }
  .ep-code {
    flex: none;
    padding: 1px 6px;
    border-radius: 6px;
    background: color-mix(in srgb, var(--accent) 16%, var(--glass-fill));
    color: var(--accent);
    font-size: 0.7rem;
    font-weight: 800;
    font-variant-numeric: tabular-nums;
  }
  .sel-box {
    flex: none;
    width: 22px;
    height: 22px;
    border: 0;
    border-radius: 6px;
    display: grid;
    place-items: center;
    background: color-mix(in srgb, var(--surface) 65%, transparent);
    box-shadow: inset 0 0 0 1.5px var(--glass-stroke);
    color: var(--on-accent);
    cursor: pointer;
  }
  .sel-box.checked {
    background: var(--accent);
    box-shadow: none;
  }
  .txt {
    display: grid;
    gap: 2px;
    min-width: 0;
    flex: 1;
    font-variant-numeric: tabular-nums;
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
    color: var(--error);
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
    display: flex;
    gap: 6px;
  }
  .qb {
    width: 38px;
    height: 38px;
    border: 0;
    border-radius: 50%;
    display: grid;
    place-items: center;
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
    cursor: pointer;
  }
  .qb.del {
    color: var(--error);
  }
  .sheet-copy {
    margin: 0 0 6px;
    font-size: 0.92rem;
  }
  .sheet-muted {
    margin: 0;
    color: var(--text-secondary);
    font-size: 0.85rem;
  }
  .sheet-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 16px;
  }
  .sheet-actions .btn {
    flex: 1 1 auto;
  }
  .danger-btn {
    color: var(--error);
  }
</style>
