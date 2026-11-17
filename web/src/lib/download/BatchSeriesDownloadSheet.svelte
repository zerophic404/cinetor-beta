<script lang="ts">
  // This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer,
  // scrape or hack anything without Mythbrix's explicit permission.
  import { goto } from '$app/navigation';
  import { base } from '$app/paths';
  import Sheet from '$lib/components/Sheet.svelte';
  import { catalog } from '$lib/data/app-catalog';
  import { isLazySource } from '$lib/data/gateway-play';
  import type { Episode, Item } from '$lib/data/types';
  import { isTauriEnv, locationInfo, saveCopySupported } from '$lib/desktop/env';
  import { GENERIC, report } from '$lib/errors';
  import { mediaFor, preferredHeight, resolveMedia } from '$lib/player/controller';
  import { toasts } from '$lib/ui/toast.svelte';
  import {
    areAllLoadedSelected,
    BATCH_QUALITIES,
    clearAllSelections,
    countSelectedInSeason,
    countTotalLoaded,
    countTotalSelected,
    createBatchSelection,
    formatSeasonBadge,
    formatSeriesSubtitle,
    isEpisodeSelected,
    isSeasonFullySelected,
    pickClosestQuality,
    selectAllLoadedSeasons,
    selectedEntries,
    setSeasonEpisodes,
    toggleEpisode,
    toggleSeason,
    type BatchQualityHeight,
    type BatchSelectionState
  } from './batch';
  import { downloadKindOf, downloads } from './manager.svelte';
  import { estimateSize, formatBytes } from './math';
  import { isIOS } from './store';
  import type { TrackChoice } from './types';

  let {
    open = $bindable(false),
    item,
    initialSeason = null,
    onstarted
  }: {
    open?: boolean;
    item: Item;
    initialSeason?: number | null;
    onstarted?: (queued: number) => void;
  } = $props();

  const seasons = $derived(item.seasons ?? []);
  let activeSeason = $state<number>(1);
  let selection = $state<BatchSelectionState<Episode>>(createBatchSelection<Episode>());
  let loadingSeason = $state(false);
  let loadingAllSeasons = $state(false);
  let preferredQuality = $state<BatchQualityHeight>(1080);
  let resolving = $state(false);
  let progressDone = $state(0);
  let progressTotal = $state(0);
  let err = $state('');
  let batchAbort: AbortController | null = null;

  const ios = typeof navigator !== 'undefined' && isIOS(navigator.userAgent, navigator.maxTouchPoints);
  const tauri = typeof window !== 'undefined' && isTauriEnv();
  let dir = $state<string | null>(null);
  let picking = $state(false);
  const loc = $derived(locationInfo({ tauri, dir, saveCopy: saveCopySupported() }));

  function nearestPreset(h: number): BatchQualityHeight {
    let best: BatchQualityHeight = 1080;
    let bestDist = Infinity;
    for (const q of BATCH_QUALITIES) {
      const d = Math.abs(q - h);
      if (d < bestDist) {
        best = q;
        bestDist = d;
      }
    }
    return best;
  }

  async function changeDir() {
    picking = true;
    try {
      const { pickDownloadsDir } = await import('$lib/desktop/native-dl');
      const picked = await pickDownloadsDir();
      if (picked) dir = picked;
    } catch {
      toasts.show("That folder can't be used");
    } finally {
      picking = false;
    }
  }

  // Initialize multi-season map when the sheet opens for this series.
  $effect(() => {
    if (!open) {
      if (batchAbort) {
        batchAbort.abort();
        batchAbort = null;
      }
      resolving = false;
      return;
    }
    const curItem = item;
    const list = curItem.seasons ?? [];
    const startSeason = initialSeason ?? list[0]?.n ?? 1;
    activeSeason = startSeason;
    preferredQuality = nearestPreset(preferredHeight());
    err = '';
    progressDone = 0;
    progressTotal = 0;

    void downloads.init().then(() => downloads.refreshQuota());
    if (tauri && dir === null) {
      void import('$lib/desktop/native-dl')
        .then((m) => m.downloadsDir())
        .then((d) => (dir = d))
        .catch(() => {});
    }

    const seeded: [number, readonly Episode[]][] = [];
    for (const s of list) {
      if (s.episodes?.length) seeded.push([s.n, s.episodes]);
    }
    let next = createBatchSelection<Episode>(seeded);
    const activeEps = next.seasons.get(startSeason);
    if (activeEps?.length && activeEps.length <= 24) {
      for (const ep of activeEps) {
        if (!downloads.isDownloaded(`${curItem.id}#${ep.id}`)) {
          next = toggleEpisode(next, startSeason, ep.id);
        }
      }
    }
    selection = next;
  });

  // Lazily fetch episodes when switching to a season whose episodes weren't inline.
  $effect(() => {
    if (!open) return;
    const curItem = item;
    const sn = activeSeason;
    if (selection.seasons.has(sn)) {
      loadingSeason = false;
      return;
    }
    let alive = true;
    loadingSeason = true;
    catalog.season(curItem, sn).then(
      (eps) => {
        if (!alive) return;
        selection = setSeasonEpisodes(selection, sn, eps);
        loadingSeason = false;
      },
      () => {
        if (!alive) return;
        selection = setSeasonEpisodes(selection, sn, []);
        loadingSeason = false;
      }
    );
    return () => {
      alive = false;
    };
  });

  const activeEpisodes = $derived(selection.seasons.get(activeSeason) ?? []);
  const seasonSelectedCount = $derived(countSelectedInSeason(selection, activeSeason));
  const seasonFullySelected = $derived(isSeasonFullySelected(selection, activeSeason));
  const totalSelected = $derived(countTotalSelected(selection));
  const totalLoaded = $derived(countTotalLoaded(selection));
  const totalCatalogCount = $derived(
    seasons.reduce((acc, s) => acc + (selection.seasons.get(s.n)?.length ?? s.episodeCount ?? s.episodes?.length ?? 0), 0) || totalLoaded
  );
  const allSeasonsLoaded = $derived(seasons.every((s) => selection.seasons.has(s.n)));
  const allSeasonsSelected = $derived(allSeasonsLoaded && areAllLoadedSelected(selection));
  const free = $derived(Math.max(0, downloads.quota.quota - downloads.quota.usage));

  const fmtDur = (sec?: number) => {
    if (!sec) return '';
    const m = Math.round(sec / 60);
    return m >= 60 ? `${Math.floor(m / 60)}h ${m % 60}m` : `${m}m`;
  };

  function estimatedBpsFor(h: number): number {
    if (h >= 1080) return 3_200_000;
    if (h >= 720) return 1_800_000;
    if (h >= 480) return 950_000;
    return 600_000;
  }

  const batchEstimatedBytes = $derived.by(() => {
    const bps = estimatedBpsFor(preferredQuality);
    let bytes = 0;
    for (const { episode } of selectedEntries(selection)) {
      bytes += estimateSize(bps, episode.durationSec || 1440);
    }
    return bytes;
  });

  async function handleSelectAllSeasons() {
    if (allSeasonsSelected) {
      selection = clearAllSelections(selection);
      return;
    }
    const missing = seasons.filter((s) => !selection.seasons.has(s.n));
    if (missing.length) {
      loadingAllSeasons = true;
      try {
        const fetched = await Promise.all(
          missing.map((s) =>
            catalog.season(item, s.n).then(
              (eps) => [s.n, eps] as const,
              () => [s.n, [] as Episode[]] as const
            )
          )
        );
        let next = selection;
        for (const [sn, eps] of fetched) next = setSeasonEpisodes(next, sn, eps);
        selection = selectAllLoadedSeasons(next);
      } finally {
        loadingAllSeasons = false;
      }
    } else {
      selection = selectAllLoadedSeasons(selection);
    }
  }

  function cancelResolve() {
    if (batchAbort) {
      batchAbort.abort();
      batchAbort = null;
    }
    resolving = false;
  }

  async function startBatch() {
    const entries = selectedEntries(selection);
    if (!entries.length || resolving) return;
    err = '';
    resolving = true;
    progressDone = 0;
    progressTotal = entries.length;
    const ctl = new AbortController();
    batchAbort = ctl;

    let queuedCount = 0;
    try {
      for (const { seasonNumber, episode } of entries) {
        if (ctl.signal.aborted) break;
        const baseMedia = mediaFor(item, episode);
        const subtitle = formatSeriesSubtitle(seasonNumber, episode.n, episode.title);
        const lazyUrl = isLazySource(baseMedia.source) ? baseMedia.source?.url : undefined;
        const resolvedMedia = lazyUrl ? await resolveMedia(baseMedia, ctl.signal) : baseMedia;
        if (ctl.signal.aborted) break;

        const candidates = resolvedMedia.variants?.length
          ? resolvedMedia.variants
          : resolvedMedia.source
            ? [resolvedMedia.source]
            : [];
        const chosen = pickClosestQuality(candidates, preferredQuality);
        if (chosen && downloadKindOf(chosen)) {
          const h = chosen.height;
          const choice: TrackChoice = h
            ? { quality: chosen.label ?? `${h}p`, height: h, texts: [] }
            : { quality: chosen.label ?? `Original (${chosen.kind ?? 'MP4'})`, texts: [] };
          const dur = resolvedMedia.durationSec ?? episode.durationSec ?? 0;
          const est = estimateSize(estimatedBpsFor(h ?? preferredQuality), dur || 1440);
          await downloads.start({
            media: { ...resolvedMedia, source: chosen, subtitle },
            choice,
            estimatedBytes: est,
            connections: downloads.defaultConnections,
            durationSec: dur,
            lazyUrl
          });
          queuedCount++;
        }
        progressDone++;
      }

      if (ctl.signal.aborted) {
        if (queuedCount > 0) {
          toasts.show(`Queued ${queuedCount} ${queuedCount === 1 ? 'episode' : 'episodes'} before canceling`, {
            label: 'Show downloads',
            run: () => void goto(`${base}/app/downloads`)
          });
        }
        return;
      }

      if (queuedCount === 0) {
        err = 'No downloadable source is available for the selected episodes.';
        return;
      }

      open = false;
      toasts.show(`Queued ${queuedCount} ${queuedCount === 1 ? 'episode' : 'episodes'} for download`, {
        label: 'Show downloads',
        run: () => void goto(`${base}/app/downloads`)
      });
      onstarted?.(queuedCount);
    } catch (e) {
      if (!ctl.signal.aborted) {
        err = report('dl.batch', e, GENERIC.download);
      }
    } finally {
      if (batchAbort === ctl) batchAbort = null;
      resolving = false;
    }
  }
</script>

<Sheet bind:open title="Download Episodes" wide>
  <div class="subhead">
    <p class="series-name">
      <b>{item.title}</b>
      <span> · {totalSelected} of {totalCatalogCount} selected · {preferredQuality}p</span>
    </p>
  </div>

  {#if seasons.length > 0}
    <div class="season-tabs" role="tablist" aria-label="Seasons">
      {#each seasons as s (s.n)}
        {@const selInS = countSelectedInSeason(selection, s.n)}
        {@const totalInS = selection.seasons.get(s.n)?.length ?? s.episodeCount ?? s.episodes?.length ?? 0}
        {@const active = activeSeason === s.n}
        <button
          type="button"
          role="tab"
          class="season-tab"
          class:active
          class:has-sel={selInS > 0 && !active}
          aria-selected={active}
          disabled={resolving}
          onclick={() => (activeSeason = s.n)}
        >
          <span class="st-title">{s.title ?? (s.n === 0 ? 'Specials' : `Season ${s.n}`)}</span>
          <span class="st-badge">{formatSeasonBadge(s.n, selInS, totalInS)}</span>
        </button>
      {/each}
    </div>
  {/if}

  <div class="toolbar">
    <div class="sel-actions">
      <button
        type="button"
        class="ctrl-pill"
        disabled={resolving || loadingSeason || !activeEpisodes.length}
        onclick={() => (selection = toggleSeason(selection, activeSeason))}
      >
        {seasonFullySelected ? 'Unselect Season' : 'Select Season'}
      </button>
      {#if seasons.length > 1}
        <button
          type="button"
          class="ctrl-pill"
          disabled={resolving || loadingAllSeasons}
          onclick={handleSelectAllSeasons}
        >
          {loadingAllSeasons ? 'Loading…' : allSeasonsSelected ? 'Clear All' : 'Select All Seasons'}
        </button>
      {/if}
      {#if totalSelected > 0 && !allSeasonsSelected}
        <button
          type="button"
          class="ctrl-pill subtle"
          disabled={resolving}
          onclick={() => (selection = clearAllSelections(selection))}
        >
          Clear All
        </button>
      {/if}
    </div>

    <div class="qual-group" role="radiogroup" aria-label="Preferred quality">
      {#each BATCH_QUALITIES as q (q)}
        <button
          type="button"
          role="radio"
          aria-checked={preferredQuality === q}
          class="qual-pill"
          class:active={preferredQuality === q}
          disabled={resolving}
          onclick={() => (preferredQuality = q)}
        >
          {q}p
        </button>
      {/each}
    </div>
  </div>

  {#if loadingSeason}
    <div class="loading" aria-busy="true">
      <span class="spin"></span> Loading Season {activeSeason} episodes…
    </div>
  {:else if !activeEpisodes.length}
    <p class="empty">No episodes available in this season yet.</p>
  {:else}
    <ul class="ep-list" aria-label="Season {activeSeason} episodes">
      {#each activeEpisodes as ep (ep.id)}
        {@const checked = isEpisodeSelected(selection, activeSeason, ep.id)}
        {@const rec = downloads.get(`${item.id}#${ep.id}`)}
        {@const dlStatus = rec && rec.status !== 'canceled' ? rec.status : null}
        <li>
          <label class="ep-row" class:checked>
            <input
              type="checkbox"
              {checked}
              disabled={resolving}
              onchange={() => (selection = toggleEpisode(selection, activeSeason, ep.id))}
            />
            <span class="check-ui" aria-hidden="true">
              {#if checked}
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3">
                  <path d="M5 12.5l4.5 4.5L19 7.5" />
                </svg>
              {/if}
            </span>
            <span class="ep-body">
              <span class="ep-top">
                <span class="ep-num">E{String(ep.n).padStart(2, '0')}</span>
                <span class="ep-title">{ep.title}</span>
                {#if ep.durationSec}<span class="ep-dur">{fmtDur(ep.durationSec)}</span>{/if}
                {#if dlStatus === 'completed'}
                  <span class="dl-tag done">Downloaded</span>
                {:else if dlStatus === 'running' || dlStatus === 'queued' || dlStatus === 'paused'}
                  <span class="dl-tag active">{dlStatus === 'paused' ? 'Paused' : 'Queued'}</span>
                {/if}
              </span>
              {#if ep.synopsis}
                <span class="ep-syn">{ep.synopsis}</span>
              {/if}
            </span>
          </label>
        </li>
      {/each}
    </ul>
  {/if}

  <div class="meta-footer">
    <div class="sum">
      <span>Estimated total <b>{totalSelected ? `≈ ${formatBytes(batchEstimatedBytes)}` : '0 B'}</b></span>
      {#if !tauri && downloads.quota.quota}<span>Free <b>{formatBytes(free)}</b></span>{/if}
    </div>

    <div class="loc">
      <div class="locrow">
        <span class="lk">Save to</span>
        <span class="lv" title={loc.label}>{loc.label}</span>
        {#if loc.changeable}
          <button type="button" class="chip" disabled={picking || resolving} onclick={changeDir}>
            {picking ? 'Choosing…' : 'Change…'}
          </button>
        {/if}
      </div>
      <p class="note">{loc.note}</p>
    </div>

    {#if ios}
      <p class="ios">iOS: web storage is limited. Add Cinetor to your Home Screen to keep offline episodes longer.</p>
    {/if}
    {#if err}
      <p class="err" role="alert">{err}</p>
    {/if}

    <div class="cta-row">
      {#if resolving}
        <button type="button" class="btn btn-ghost" onclick={cancelResolve}>Cancel</button>
      {/if}
      <button
        type="button"
        class="btn btn-primary go"
        disabled={totalSelected === 0 || resolving || loadingAllSeasons}
        onclick={startBatch}
      >
        {#if resolving}
          <span class="spin on-btn" aria-hidden="true"></span>
          Resolving {progressDone} / {progressTotal}…
        {:else if totalSelected === 0}
          Select episodes to download
        {:else}
          Download {totalSelected} {totalSelected === 1 ? 'Episode' : 'Episodes'}
        {/if}
      </button>
    </div>
  </div>
</Sheet>

<style>
  .subhead {
    margin: 0 0 10px;
  }
  .series-name {
    margin: 0;
    font-size: 0.88rem;
    color: var(--text-secondary);
    font-variant-numeric: tabular-nums;
  }
  .series-name b {
    color: var(--text-primary);
  }
  .season-tabs {
    display: flex;
    gap: 8px;
    overflow-x: auto;
    overscroll-behavior-x: contain;
    scrollbar-width: none;
    padding: 2px 0 10px;
  }
  .season-tabs::-webkit-scrollbar {
    display: none;
  }
  .season-tab {
    flex: none;
    display: inline-flex;
    align-items: center;
    gap: 8px;
    min-height: 36px;
    padding: 4px 12px;
    border: 0;
    border-radius: var(--radius-pill);
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
    color: var(--text-secondary);
    font-size: 0.84rem;
    font-weight: 650;
    cursor: pointer;
    transition:
      background calc(var(--dur-short) * var(--dur-scale)) var(--ease-standard),
      color calc(var(--dur-short) * var(--dur-scale)) var(--ease-standard),
      box-shadow calc(var(--dur-short) * var(--dur-scale)) var(--ease-standard);
  }
  .season-tab.has-sel {
    color: var(--text-primary);
    box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--accent) 55%, var(--glass-stroke));
  }
  .season-tab.active {
    color: var(--on-accent);
    background: linear-gradient(135deg, var(--accent), var(--accent-end));
    box-shadow: 0 6px 18px -8px var(--accent-glow);
  }
  .st-badge {
    padding: 2px 7px;
    border-radius: var(--radius-pill);
    background: color-mix(in srgb, var(--text-primary) 10%, transparent);
    font-size: 0.72rem;
    font-weight: 750;
    font-variant-numeric: tabular-nums;
  }
  .season-tab.active .st-badge {
    background: rgb(0 0 0 / 0.24);
    color: var(--on-accent);
  }
  .season-tab.has-sel .st-badge {
    background: color-mix(in srgb, var(--accent) 20%, transparent);
    color: var(--text-primary);
  }
  .toolbar {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 8px 12px;
    margin-bottom: 10px;
  }
  .sel-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }
  .ctrl-pill {
    min-height: 32px;
    padding: 0 12px;
    border-radius: var(--radius-pill);
    border: 0;
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
    color: var(--text-primary);
    font-size: 0.78rem;
    font-weight: 700;
    cursor: pointer;
  }
  .ctrl-pill.subtle {
    color: var(--text-secondary);
  }
  .ctrl-pill:disabled,
  .qual-pill:disabled,
  .season-tab:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
  .qual-group {
    display: inline-flex;
    padding: 3px;
    border-radius: var(--radius-pill);
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
    gap: 2px;
  }
  .qual-pill {
    min-height: 28px;
    padding: 0 10px;
    border: 0;
    border-radius: var(--radius-pill);
    background: transparent;
    color: var(--text-secondary);
    font-size: 0.76rem;
    font-weight: 750;
    font-variant-numeric: tabular-nums;
    cursor: pointer;
  }
  .qual-pill.active {
    background: linear-gradient(135deg, var(--accent), var(--accent-end));
    color: var(--on-accent);
    box-shadow: 0 4px 12px -4px var(--accent-glow);
  }
  .loading {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 28px 4px;
    color: var(--text-secondary);
    font-size: 0.88rem;
  }
  .empty {
    padding: 20px 4px;
    color: var(--text-secondary);
    font-size: 0.88rem;
    margin: 0;
  }
  .spin {
    width: 18px;
    height: 18px;
    border-radius: 50%;
    border: 2px solid var(--glass-stroke);
    border-top-color: var(--accent);
    animation: spin 0.8s linear infinite;
    flex: none;
  }
  .spin.on-btn {
    width: 16px;
    height: 16px;
    border-color: rgb(255 255 255 / 0.35);
    border-top-color: var(--on-accent);
  }
  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }
  .ep-list {
    list-style: none;
    margin: 0;
    padding: 2px;
    max-height: min(44dvh, 360px);
    overflow-y: auto;
    display: grid;
    gap: 6px;
    scrollbar-width: thin;
    scrollbar-color: var(--glass-stroke) transparent;
  }
  .ep-list::-webkit-scrollbar {
    width: 6px;
  }
  .ep-list::-webkit-scrollbar-thumb {
    background: var(--glass-stroke);
    border-radius: 99px;
  }
  .ep-row {
    display: flex;
    align-items: flex-start;
    gap: 10px;
    padding: 9px 12px;
    border-radius: 14px;
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
    cursor: pointer;
    transition:
      box-shadow calc(var(--dur-short) * var(--dur-scale)) var(--ease-standard),
      background calc(var(--dur-short) * var(--dur-scale)) var(--ease-standard);
  }
  .ep-row.checked {
    background: color-mix(in srgb, var(--accent) 10%, var(--glass-fill));
    box-shadow: inset 0 0 0 1.5px color-mix(in srgb, var(--accent) 68%, var(--glass-stroke));
  }
  .ep-row input {
    position: absolute;
    opacity: 0;
    width: 1px;
    height: 1px;
    pointer-events: none;
  }
  .ep-row:focus-within {
    outline: 2px solid var(--focus-ring);
    outline-offset: 2px;
  }
  .check-ui {
    flex: none;
    margin-top: 1px;
    width: 20px;
    height: 20px;
    border-radius: 6px;
    display: grid;
    place-items: center;
    background: color-mix(in srgb, var(--surface) 65%, transparent);
    box-shadow: inset 0 0 0 1.5px var(--glass-stroke);
    color: var(--on-accent);
  }
  .ep-row.checked .check-ui {
    background: var(--accent);
    box-shadow: none;
  }
  .ep-body {
    flex: 1;
    min-width: 0;
    display: grid;
    gap: 2px;
  }
  .ep-top {
    display: flex;
    align-items: center;
    gap: 7px;
    min-width: 0;
  }
  .ep-num {
    flex: none;
    font-size: 0.74rem;
    font-weight: 800;
    font-variant-numeric: tabular-nums;
    color: var(--text-secondary);
  }
  .ep-row.checked .ep-num {
    color: var(--accent);
  }
  .ep-title {
    flex: 1;
    min-width: 0;
    font-size: 0.88rem;
    font-weight: 650;
    color: var(--text-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .ep-dur {
    flex: none;
    font-size: 0.74rem;
    font-variant-numeric: tabular-nums;
    color: var(--text-tertiary);
  }
  .dl-tag {
    flex: none;
    padding: 1px 7px;
    border-radius: var(--radius-pill);
    font-size: 0.68rem;
    font-weight: 750;
  }
  .dl-tag.done {
    color: var(--success);
    background: color-mix(in srgb, var(--success) 14%, transparent);
  }
  .dl-tag.active {
    color: var(--accent);
    background: color-mix(in srgb, var(--accent) 14%, transparent);
  }
  .ep-syn {
    font-size: 0.76rem;
    color: var(--text-secondary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .meta-footer {
    margin-top: 12px;
    display: grid;
    gap: 8px;
  }
  .sum {
    display: flex;
    justify-content: space-between;
    gap: 10px;
    font-size: 0.82rem;
    color: var(--text-secondary);
    font-variant-numeric: tabular-nums;
  }
  .sum b {
    color: var(--text-primary);
  }
  .loc {
    padding: 10px 12px;
    border-radius: 14px;
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
  }
  .locrow {
    display: flex;
    align-items: center;
    gap: 10px;
    font-size: 0.84rem;
  }
  .lk {
    flex: none;
    color: var(--text-secondary);
  }
  .lv {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-weight: 700;
  }
  .note {
    margin: 4px 0 0;
    font-size: 0.74rem;
    color: var(--text-secondary);
  }
  .ios {
    margin: 0;
    font-size: 0.76rem;
    padding: 8px 10px;
    border-radius: 12px;
    background: color-mix(in srgb, var(--warning) 16%, transparent);
  }
  .err {
    margin: 0;
    color: var(--error);
    font-size: 0.84rem;
  }
  .cta-row {
    display: flex;
    gap: 8px;
    margin-top: 2px;
  }
  .go {
    flex: 1;
    font-variant-numeric: tabular-nums;
  }
  .go:disabled {
    opacity: 0.55;
    cursor: not-allowed;
  }
</style>
