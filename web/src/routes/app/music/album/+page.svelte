<script lang="ts">
  // This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer,
  // scrape or hack anything without Mythbrix's explicit permission.
  import { base } from '$app/paths';
  import { page } from '$app/state';
  import { SkeletonBox, SkeletonChapterList } from '$lib/design/skeleton';
  import { isTauriEnv, locationInfo, saveCopySupported } from '$lib/desktop/env';
  import { music, musicApi, toPlayerTrack } from '$lib/music/player.svelte';
  import { qualityShortLabel, rawMusicId } from '$lib/music/gateway-music';
  import type { Track } from '$lib/music/core';
  import { fmtDur } from '$lib/music/queue';
  import TrackList from '$lib/music/TrackList.svelte';

  interface CollectionView {
    id: string;
    kind: 'album' | 'playlist';
    title: string;
    artist: string;
    year: string | null;
    artwork: string;
    tracks: Track[];
  }

  const id = $derived(page.url.searchParams.get('id') ?? '');
  const kindParam = $derived(page.url.searchParams.get('kind'));
  const trackParam = $derived(page.url.searchParams.get('track'));

  let collection = $state.raw<CollectionView | null>(null);
  let loadStatus = $state<'loading' | 'ready' | 'missing'>('loading');
  let selectionMode = $state(false);
  let selectedIds = $state<string[]>([]);
  let batchBusy = $state(false);
  let autoPlayed = '';

  const destInfo = $derived(
    locationInfo({
      tauri: isTauriEnv(),
      saveCopy: saveCopySupported()
    })
  );

  $effect(() => {
    const wantId = id.trim();
    const wantKind = kindParam;
    const deepTrack = trackParam;
    if (!wantId) {
      collection = null;
      loadStatus = 'missing';
      return;
    }

    const ctl = new AbortController();
    loadStatus = 'loading';
    selectionMode = false;
    selectedIds = [];

    (async () => {
      if (wantKind === 'playlist') {
        const pl = await musicApi.playlist(wantId, ctl.signal);
        if (pl && pl.tracks.length) {
          return {
            id: pl.id,
            kind: 'playlist' as const,
            title: pl.title,
            artist: pl.artist ?? 'Curated Playlist',
            year: null,
            artwork: pl.artwork,
            tracks: pl.tracks.map(toPlayerTrack)
          };
        }
        const alFallback = await musicApi.album(wantId, ctl.signal);
        if (alFallback && alFallback.tracks.length) {
          return {
            id: alFallback.id,
            kind: 'album' as const,
            title: alFallback.title,
            artist: alFallback.artist,
            year: alFallback.year,
            artwork: alFallback.artwork,
            tracks: alFallback.tracks.map(toPlayerTrack)
          };
        }
        return null;
      }

      const al = await musicApi.album(wantId, ctl.signal);
      if (al && al.tracks.length) {
        return {
          id: al.id,
          kind: 'album' as const,
          title: al.title,
          artist: al.artist,
          year: al.year,
          artwork: al.artwork,
          tracks: al.tracks.map(toPlayerTrack)
        };
      }
      const plFallback = await musicApi.playlist(wantId, ctl.signal);
      if (plFallback && plFallback.tracks.length) {
        return {
          id: plFallback.id,
          kind: 'playlist' as const,
          title: plFallback.title,
          artist: plFallback.artist ?? 'Curated Playlist',
          year: null,
          artwork: plFallback.artwork,
          tracks: plFallback.tracks.map(toPlayerTrack)
        };
      }
      return null;
    })()
      .then((res) => {
        if (ctl.signal.aborted) return;
        if (!res) {
          collection = null;
          loadStatus = 'missing';
          return;
        }
        collection = res;
        loadStatus = 'ready';
        if (deepTrack && autoPlayed !== deepTrack) {
          autoPlayed = deepTrack;
          const wantRaw = rawMusicId(deepTrack);
          const idx = res.tracks.findIndex((t) => t.id === deepTrack || t.rawId === wantRaw);
          if (idx >= 0 && music.current?.id !== res.tracks[idx].id) {
            music.playQueue(res.tracks, idx);
          }
        }
      })
      .catch(() => {
        if (!ctl.signal.aborted) {
          collection = null;
          loadStatus = 'missing';
        }
      });

    return () => ctl.abort();
  });

  const tracks = $derived(collection?.tracks ?? []);
  const totalSec = $derived(tracks.reduce((sum, t) => sum + (t.durationSec || 0), 0));

  function playAll(shuffle: boolean) {
    if (!tracks.length) return;
    music.setShuffle(shuffle);
    music.playQueue(tracks, shuffle ? Math.floor(Math.random() * tracks.length) : 0);
  }

  function toggleSelect(trackId: string) {
    if (selectedIds.includes(trackId)) {
      selectedIds = selectedIds.filter((x) => x !== trackId);
    } else {
      selectedIds = [...selectedIds, trackId];
    }
  }

  function toggleSelectAll() {
    if (selectedIds.length === tracks.length) {
      selectedIds = [];
    } else {
      selectedIds = tracks.map((t) => t.id);
    }
  }

  async function downloadAll() {
    if (!tracks.length || batchBusy) return;
    batchBusy = true;
    try {
      await music.downloadBatch(tracks, music.quality);
    } finally {
      batchBusy = false;
    }
  }

  async function downloadSelected() {
    if (!selectedIds.length || batchBusy) return;
    const chosen = tracks.filter((t) => selectedIds.includes(t.id));
    batchBusy = true;
    try {
      await music.downloadBatch(chosen, music.quality);
      selectionMode = false;
      selectedIds = [];
    } finally {
      batchBusy = false;
    }
  }
</script>

<svelte:head>
  <title>{collection?.title ?? (kindParam === 'playlist' ? 'Playlist' : 'Album')} · Music · Cinetor</title>
</svelte:head>

<div class="album-page">
  <div class="top-nav">
    <a class="back-btn glass" href="{base}/app/music" aria-label="Back to Music">‹ Music</a>

    <div class="q-switch glass" role="group" aria-label="Audio quality">
      <button
        type="button"
        class:active={music.quality === 'flac'}
        aria-pressed={music.quality === 'flac'}
        onclick={() => music.setQuality('flac')}
      >
        FLAC
      </button>
      <button
        type="button"
        class:active={music.quality === '320kbps'}
        aria-pressed={music.quality === '320kbps'}
        onclick={() => music.setQuality('320kbps')}
      >
        320kbps
      </button>
    </div>
  </div>

  {#if loadStatus === 'loading'}
    <div class="head sk" aria-busy="true" aria-label="Loading tracklist">
      <SkeletonBox width="200px" height="200px" radius="18px" />
      <div class="sk-txt">
        <SkeletonBox width="60%" height="28px" />
        <SkeletonBox width="40%" height="16px" />
      </div>
    </div>
    <SkeletonChapterList count={6} />
  {:else if loadStatus === 'missing' || !collection}
    <div class="empty-box">
      <p>{kindParam === 'playlist' ? 'Playlist not found.' : 'Album not found.'}</p>
      <a class="btn btn-ghost" href="{base}/app/music">Back to Music</a>
    </div>
  {:else}
    <section class="head glass" style:--c={tracks[0]?.color ?? 'var(--accent)'}>
      {#if collection.artwork}
        <div class="bg" style:background-image="url({collection.artwork})" aria-hidden="true"></div>
        <img class="art" referrerpolicy="no-referrer" src={collection.artwork} alt="{collection.title} artwork" width="210" height="210" decoding="async" />
      {:else}
        <div class="art art-ph" aria-hidden="true">♪</div>
      {/if}

      <div class="txt">
        <span class="kicker">
          {collection.kind === 'playlist' ? 'PLAYLIST' : 'ALBUM'} · {qualityShortLabel(music.quality).toUpperCase()}
        </span>
        <h1>{collection.title}</h1>
        <p>
          {collection.artist}{collection.year ? ` · ${collection.year}` : ''} · {tracks.length}
          {tracks.length === 1 ? 'track' : 'tracks'}{totalSec > 0 ? ` · ${fmtDur(totalSec)}` : ''}
        </p>
        <p class="dest-note">Save destination: <b>{destInfo.label}</b></p>

        <div class="acts">
          <button type="button" class="btn btn-primary" onclick={() => playAll(false)}>▶ Play All</button>
          <button type="button" class="btn btn-ghost" onclick={() => playAll(true)}>⤮ Shuffle</button>
          <button
            type="button"
            class="btn btn-ghost"
            disabled={batchBusy}
            onclick={downloadAll}
          >
            {batchBusy && !selectionMode ? 'Queueing…' : `⬇ Download All (${qualityShortLabel(music.quality)})`}
          </button>
          <button
            type="button"
            class="btn btn-ghost"
            aria-pressed={selectionMode}
            onclick={() => {
              selectionMode = !selectionMode;
              if (!selectionMode) selectedIds = [];
            }}
          >
            {selectionMode ? 'Cancel' : 'Select'}
          </button>
        </div>
      </div>
    </section>

    {#if selectionMode}
      <div class="sel-bar glass" role="region" aria-label="Batch download selection">
        <button type="button" class="chip" onclick={toggleSelectAll}>
          {selectedIds.length === tracks.length ? 'Deselect All' : 'Select All'}
        </button>
        <button
          type="button"
          class="btn btn-primary sm-btn"
          disabled={!selectedIds.length || batchBusy}
          onclick={downloadSelected}
        >
          {batchBusy ? 'Queueing…' : `⬇ Download ${selectedIds.length} (${qualityShortLabel(music.quality)})`}
        </button>
      </div>
    {/if}

    <div class="list-wrap">
      <TrackList
        {tracks}
        art={collection.kind === 'playlist'}
        selectable={selectionMode}
        {selectedIds}
        onToggleSelect={toggleSelect}
      />
    </div>
  {/if}
</div>

<style>
  .album-page {
    padding-bottom: 18px;
  }
  .top-nav {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 0 16px 12px;
  }
  .back-btn {
    display: inline-flex;
    align-items: center;
    min-height: 36px;
    padding: 0 14px;
    border-radius: var(--radius-pill, 999px);
    text-decoration: none;
    font-size: 0.88rem;
    font-weight: 650;
  }
  .q-switch {
    display: inline-flex;
    padding: 3px;
    border-radius: var(--radius-pill, 999px);
  }
  .q-switch button {
    padding: 5px 12px;
    border: 0;
    border-radius: var(--radius-pill, 999px);
    background: none;
    color: var(--text-secondary);
    font-size: 0.76rem;
    font-weight: 700;
    cursor: pointer;
  }
  .q-switch button.active {
    background: linear-gradient(135deg, var(--accent), var(--accent-end));
    color: var(--on-accent);
  }
  .empty-box {
    padding: 48px 16px;
    text-align: center;
    color: var(--text-secondary);
    display: grid;
    justify-items: center;
    gap: 12px;
  }
  .head {
    position: relative;
    display: flex;
    flex-wrap: wrap;
    gap: 22px;
    align-items: flex-end;
    margin: 0 16px;
    padding: 24px;
    border-radius: 26px;
    overflow: hidden;
    background:
      linear-gradient(155deg, color-mix(in srgb, var(--c) 24%, transparent), transparent 72%),
      var(--glass-fill);
  }
  .head.sk {
    background: none;
  }
  .sk-txt {
    flex: 1;
    display: grid;
    gap: 10px;
  }
  .bg {
    position: absolute;
    inset: -50px;
    z-index: -1;
    background-size: cover;
    background-position: center;
    filter: blur(48px) saturate(1.3);
    opacity: 0.25;
  }
  :global([data-tier='low']) .bg {
    display: none;
  }
  .art {
    width: clamp(136px, 36vw, 210px);
    height: auto;
    aspect-ratio: 1;
    object-fit: cover;
    border-radius: 18px;
    box-shadow: 0 22px 46px -18px rgb(0 0 0 / 0.65);
    background: var(--surface-elevated);
  }
  .art-ph {
    display: grid;
    place-items: center;
    font-size: 3rem;
    color: var(--text-tertiary);
  }
  .txt {
    flex: 1;
    min-width: 220px;
  }
  .kicker {
    font-size: 0.7rem;
    font-weight: 800;
    letter-spacing: 0.1em;
    text-transform: uppercase;
    color: var(--accent);
  }
  h1 {
    margin: 4px 0 6px;
    font-size: clamp(1.45rem, 4.4vw, 2.4rem);
    line-height: 1.12;
  }
  .txt p {
    margin: 0;
    color: var(--text-secondary);
    font-size: 0.9rem;
  }
  .dest-note {
    margin-top: 6px !important;
    font-size: 0.78rem !important;
    color: var(--text-tertiary) !important;
  }
  .dest-note b {
    color: var(--text-secondary);
  }
  .acts {
    display: flex;
    gap: 10px;
    flex-wrap: wrap;
    margin-top: 14px;
  }
  .sel-bar {
    margin: 12px 16px 4px;
    padding: 10px 14px;
    border-radius: 16px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  }
  .sm-btn {
    min-height: 38px;
    padding: 0 16px;
    font-size: 0.86rem;
  }
  .list-wrap {
    margin-top: 12px;
  }
</style>
