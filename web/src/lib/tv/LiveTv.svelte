<script lang="ts">
  import { onDestroy, onMount } from 'svelte';
  import { base } from '$app/paths';
  import { repo, img } from '$lib/data/repo';
  import type { CatalogFile, Item } from '$lib/data/types';
  import { SkeletonEpgGrid } from '$lib/design/skeleton';
  import { engine } from '$lib/player/engine';
  import { prepareMedia, session } from '$lib/player/controller';
  import type { Media } from '$lib/player/sources';
  import {
    FAVORITES,
    channelGroups,
    channelsOf,
    clockLabel,
    currentProgram,
    filterChannels,
    genreChips,
    nextProgram,
    programProgress,
    zapChannel
  } from './epg';
  import { live } from './live.svelte';
  import EpgGuide from './EpgGuide.svelte';
  import Sports from './Sports.svelte';

  let { cat = null }: { cat?: string | null } = $props();

  let file = $state.raw<CatalogFile | null>(null);
  let error = $state<string | null>(null);
  let section = $state<'live' | 'sports'>('live');
  let group = $state<string | null>(null);
  let genre = $state<string | null>(null);
  let view = $state<'grid' | 'guide'>('grid');
  let reload = $state(0);

  let playingItem = $state.raw<Item | null>(null);
  let activeMedia = $state.raw<Media | null>(null);
  let stageWrap = $state<HTMLDivElement>();
  const sess = session.state;

  $effect(() => {
    if (cat === 'tv-sports') section = 'sports';
    else if (cat === 'tv-live') section = 'live';
  });

  $effect(() => {
    void reload;
    let alive = true;
    error = null;
    repo.file('TV').then(
      (f) => alive && (file = f),
      (e: unknown) => alive && (error = e instanceof Error ? e.message : String(e))
    );
    return () => {
      alive = false;
    };
  });

  onMount(() => live.tick());

  onDestroy(() => {
    if (activeMedia && session.snapshot.mode === 'full' && session.snapshot.media?.key === activeMedia.key) {
      session.minimize();
    }
  });

  function mountVideoStage(node: HTMLElement) {
    const detach = engine.attach(node);
    return { destroy: detach };
  }

  async function playLiveItem(target: Item) {
    playingItem = target;
    session.expand();
    session.clearError();
    if (typeof window !== 'undefined') {
      window.scrollTo({ top: 0, behavior: 'smooth' });
    }
    try {
      const m = await prepareMedia(target);
      if (playingItem?.id !== target.id) return;
      activeMedia = m;
      await session.play(m);
    } catch {
      /* session state handles stream error */
    }
  }

  function closeInlinePlayer() {
    playingItem = null;
    activeMedia = null;
    void session.stop();
  }

  function zap(dir: 1 | -1) {
    if (!playingItem || !shown.length) return;
    const next = zapChannel(shown, playingItem.id, dir);
    if (next) void playLiveItem(next);
  }

  function expandFullscreen() {
    if (stageWrap?.requestFullscreen) {
      void stageWrap.requestFullscreen().catch(() => undefined);
    }
  }

  const channels = $derived(file ? channelsOf(file.items) : []);
  const liveMatchCount = $derived(file ? file.items.filter((i) => i.type === 'EVENT' && i.status === 'LIVE').length : 0);
  const groups = $derived(channelGroups(channels));
  const inGroup = $derived(
    filterChannels(channels, { group, genre: null, favorites: live.favorites, baseSec: live.base, nowSec: live.now })
  );
  const genres = $derived(genreChips(inGroup, file?.genres ?? [], live.base, live.now));
  const shown = $derived(
    filterChannels(channels, { group, genre, favorites: live.favorites, baseSec: live.base, nowSec: live.now })
  );
  const playingProg = $derived(
    playingItem?.type === 'CHANNEL' ? currentProgram(playingItem.programs, live.base, live.now) : undefined
  );

  function pickGroup(g: string | null) {
    group = g;
    genre = null;
  }
</script>

<div class="tv-hub">
  <header class="top-bar">
    <div class="main-seg glass" role="tablist" aria-label="TV sections">
      <button
        type="button"
        role="tab"
        aria-selected={section === 'live'}
        class:active={section === 'live'}
        onclick={() => (section = 'live')}
      >
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <rect x="3" y="7" width="18" height="12" rx="2" />
          <path d="m8 3 4 4 4-4" />
        </svg>
        Live TV
      </button>
      <button
        type="button"
        role="tab"
        aria-selected={section === 'sports'}
        class:active={section === 'sports'}
        onclick={() => (section = 'sports')}
      >
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <circle cx="12" cy="12" r="9" />
          <path d="m12 7 4 3-1.5 4.5h-5L8 10z" />
        </svg>
        Sports
        {#if liveMatchCount > 0}
          <span class="live-count">{liveMatchCount}</span>
        {/if}
      </button>
    </div>

    {#if section === 'live' && file}
      <div class="view-seg glass" role="radiogroup" aria-label="Channel view">
        <button
          type="button"
          role="radio"
          aria-checked={view === 'grid'}
          class:on={view === 'grid'}
          onclick={() => (view = 'grid')}
        >
          Channels
        </button>
        <button
          type="button"
          role="radio"
          aria-checked={view === 'guide'}
          class:on={view === 'guide'}
          onclick={() => (view = 'guide')}
        >
          Guide
        </button>
      </div>
    {/if}
  </header>

  {#if playingItem}
    <section class="inline-stage glass" bind:this={stageWrap} aria-label="Now playing {playingItem.title}">
      <div class="video-slot" use:mountVideoStage>
        {#if $sess.loading}
          <div class="stage-overlay"><span class="spin" aria-label="Connecting to live stream"></span></div>
        {:else if $sess.error === 'gesture'}
          <button type="button" class="stage-overlay tap-play" onclick={() => engine.play()}>
            <span>Tap to start live stream</span>
          </button>
        {:else if $sess.error}
          <div class="stage-overlay stage-err">
            <span>This live stream isn't available right now.</span>
            <button type="button" class="btn btn-ghost retry-live" onclick={() => playingItem && playLiveItem(playingItem)}>Retry</button>
          </div>
        {/if}
      </div>
      <div class="stage-bar">
        <div class="stage-info">
          <span class="live-tag">● LIVE</span>
          <div class="stage-titles">
            <b>{playingItem.channelNumber ? `${playingItem.channelNumber} · ` : ''}{playingItem.title}</b>
            <small>{playingProg?.program.title ?? playingItem.league ?? 'Live broadcast'}</small>
          </div>
        </div>
        <div class="stage-actions">
          {#if playingItem.type === 'CHANNEL'}
            <button type="button" class="ic-btn" onclick={() => zap(-1)} aria-label="Previous channel" title="Previous channel">‹</button>
            <button type="button" class="ic-btn" onclick={() => zap(1)} aria-label="Next channel" title="Next channel">›</button>
          {/if}
          <a
            class="ic-btn"
            href="{base}/app/watch?id={encodeURIComponent(playingItem.id)}&live=1"
            onclick={(e) => {
              if (stageWrap?.requestFullscreen && e.button === 0 && !e.metaKey && !e.ctrlKey) {
                e.preventDefault();
                expandFullscreen();
              }
            }}
            aria-label="Fullscreen player"
            title="Fullscreen"
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M8 3H5a2 2 0 0 0-2 2v3M21 8V5a2 2 0 0 0-2-2h-3M3 16v3a2 2 0 0 0 2 2h3M16 21h3a2 2 0 0 0 2-2v-3" />
            </svg>
          </a>
          <button type="button" class="ic-btn" onclick={closeInlinePlayer} aria-label="Close live player">×</button>
        </div>
      </div>
    </section>
  {/if}

  {#if error}
    <div class="msg-state" role="alert">
      <p>Couldn't load Live TV & Sports.</p>
      <button type="button" class="btn btn-ghost" onclick={() => reload++}>Retry</button>
    </div>
  {:else if !file}
    <div class="loading" aria-busy="true" aria-label="Loading TV">
      <SkeletonEpgGrid channels={7} />
    </div>
  {:else if section === 'sports'}
    <Sports items={file.items} onWatchLive={playLiveItem} />
  {:else}
    <div class="h-scroll" role="group" aria-label="Channel categories">
      <button type="button" class="chip" aria-pressed={group === null} onclick={() => pickGroup(null)}>All</button>
      <button type="button" class="chip" aria-pressed={group === FAVORITES} onclick={() => pickGroup(FAVORITES)}>Favorites</button>
      {#each groups as g (g)}
        <button type="button" class="chip" aria-pressed={group === g} onclick={() => pickGroup(g)}>{g}</button>
      {/each}
    </div>

    {#if genres.length > 1}
      <div class="h-scroll genres" role="group" aria-label="Genres">
        {#each genres as g (g)}
          <button type="button" class="chip small" aria-pressed={genre === g} onclick={() => (genre = genre === g ? null : g)}>
            {g}
          </button>
        {/each}
      </div>
    {/if}

    {#if !shown.length}
      <p class="msg-state">
        {group === FAVORITES ? 'Star any channel to save it in Favorites.' : 'No channels match this filter.'}
      </p>
    {:else if view === 'guide'}
      <EpgGuide channels={shown} base={live.base} now={live.now} />
    {:else}
      <div class="ch-grid">
        {#each shown as ch (ch.id)}
          {@const cur = currentProgram(ch.programs, live.base, live.now)}
          {@const nxt = nextProgram(ch.programs, live.base, live.now)}
          {@const isPlaying = playingItem?.id === ch.id}
          <article class="ch-card" class:playing={isPlaying} style:--c={ch.accentColor}>
            <div class="thumb-wrap">
              <button
                type="button"
                class="thumb-btn"
                onclick={() => playLiveItem(ch)}
                aria-label="Play {ch.title}{cur ? ` — ${cur.program.title}` : ''}"
              >
                {#if ch.backdrop}
                  <img class="bg-img" src={img(ch.backdrop)} alt="" loading="lazy" decoding="async" width="360" height="202" />
                {/if}
                <span class="thumb-scrim" aria-hidden="true"></span>
                {#if ch.logo}
                  <span class="logo-box">
                    <img src={img(ch.logo)} alt="" loading="lazy" width="52" height="52" />
                  </span>
                {/if}
                <span class="ch-num">CH {ch.channelNumber}</span>
                <span class="live-tag top-right">● LIVE</span>
                {#if cur}
                  <span class="prog-bar" aria-hidden="true">
                    <i style:width="{Math.round(programProgress(cur, live.now) * 100)}%"></i>
                  </span>
                {/if}
              </button>
              <button
                type="button"
                class="fav-btn"
                aria-pressed={live.isFav(ch.id)}
                aria-label={live.isFav(ch.id) ? `Remove ${ch.title} from favorites` : `Add ${ch.title} to favorites`}
                onclick={() => live.toggleFav(ch.id)}
              >
                {live.isFav(ch.id) ? '★' : '☆'}
              </button>
            </div>

            <button type="button" class="caption" onclick={() => playLiveItem(ch)}>
              <span class="ch-title">{ch.title}</span>
              <span class="ch-now">{cur?.program.title ?? 'Live broadcast'}</span>
              {#if nxt}
                <span class="ch-next">Up next {clockLabel(nxt.start)} · {nxt.program.title}</span>
              {/if}
            </button>
          </article>
        {/each}
      </div>
    {/if}
  {/if}
</div>

<style>
  .tv-hub {
    display: grid;
    gap: 8px;
  }
  .top-bar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    flex-wrap: wrap;
    gap: 10px;
    padding: 4px 16px;
  }
  .main-seg,
  .view-seg {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 4px;
    border-radius: 999px;
  }
  .main-seg button,
  .view-seg button {
    display: inline-flex;
    align-items: center;
    gap: 7px;
    min-height: 36px;
    padding: 0 16px;
    border: 0;
    border-radius: 999px;
    background: none;
    color: var(--text-secondary);
    font-size: 0.88rem;
    font-weight: 740;
    cursor: pointer;
    transition:
      background-color 0.16s ease,
      color 0.16s ease;
  }
  .view-seg button {
    min-height: 32px;
    padding: 0 13px;
    font-size: 0.82rem;
  }
  .main-seg button.active,
  .view-seg button.on {
    color: var(--on-accent);
    background: linear-gradient(135deg, var(--accent), var(--accent-end));
    box-shadow: 0 6px 16px -6px var(--accent-glow);
  }
  .live-count {
    padding: 1px 7px;
    border-radius: 999px;
    background: rgb(255 255 255 / 0.18);
    font-size: 0.72rem;
    font-weight: 800;
    font-variant-numeric: tabular-nums;
  }
  .inline-stage {
    margin: 4px 16px 8px;
    border-radius: 22px;
    overflow: hidden;
  }
  .video-slot {
    position: relative;
    width: 100%;
    aspect-ratio: 16 / 9;
    max-height: 54dvh;
    background: #000;
  }
  .video-slot :global(video),
  .video-slot :global(iframe) {
    width: 100%;
    height: 100%;
    object-fit: contain;
  }
  .stage-overlay {
    position: absolute;
    inset: 0;
    z-index: 2;
    display: grid;
    place-items: center;
    background: rgb(0 0 0 / 0.45);
    color: #fff;
    border: 0;
  }
  .tap-play {
    width: 100%;
    font-weight: 750;
    cursor: pointer;
  }
  .stage-err {
    align-content: center;
    gap: 10px;
    padding: 16px;
    text-align: center;
    font-size: 0.88rem;
    background: rgb(0 0 0 / 0.68);
  }
  .retry-live {
    justify-self: center;
    min-height: 34px;
    padding: 0 16px;
    font-size: 0.82rem;
  }
  .spin {
    width: 34px;
    height: 34px;
    border-radius: 50%;
    border: 3px solid rgb(255 255 255 / 0.22);
    border-top-color: var(--accent);
    animation: tv-spin 0.85s linear infinite;
  }
  @keyframes tv-spin {
    to {
      transform: rotate(360deg);
    }
  }
  .stage-bar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 10px 14px;
  }
  .stage-info {
    display: flex;
    align-items: center;
    gap: 10px;
    min-width: 0;
  }
  .stage-titles {
    display: grid;
    min-width: 0;
  }
  .stage-titles b,
  .stage-titles small {
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .stage-titles small {
    color: var(--text-secondary);
    font-size: 0.78rem;
  }
  .stage-actions {
    display: flex;
    align-items: center;
    gap: 6px;
    flex: none;
  }
  .ic-btn {
    width: 34px;
    height: 34px;
    display: grid;
    place-items: center;
    border: 0;
    border-radius: 50%;
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
    color: var(--text-primary);
    font-size: 1.15rem;
    text-decoration: none;
    cursor: pointer;
  }
  .genres {
    padding-top: 0;
  }
  .chip.small {
    min-height: 30px;
    font-size: 0.8rem;
    padding: 0 12px;
  }
  .msg-state {
    padding: 40px 16px;
    text-align: center;
    color: var(--text-secondary);
  }
  .loading {
    padding-top: 8px;
  }
  .ch-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
    gap: 16px 14px;
    padding: 4px 16px 18px;
  }
  .ch-card {
    display: grid;
    align-content: start;
    gap: 8px;
  }
  .thumb-wrap {
    position: relative;
    aspect-ratio: 16 / 9;
    border-radius: 16px;
    overflow: hidden;
    background: linear-gradient(145deg, color-mix(in srgb, var(--c) 38%, #14151b), #0d0e12);
    box-shadow:
      inset 0 0 0 1px var(--glass-stroke),
      0 10px 24px -14px rgb(0 0 0 / 0.65);
    transition:
      transform 0.16s var(--ease-emphasized, ease),
      box-shadow 0.16s ease;
  }
  .ch-card:hover .thumb-wrap,
  .ch-card.playing .thumb-wrap {
    transform: translateY(-2px);
    box-shadow:
      inset 0 0 0 1.5px var(--accent),
      0 14px 28px -14px var(--accent-glow);
  }
  .thumb-btn {
    position: relative;
    display: grid;
    place-items: center;
    width: 100%;
    height: 100%;
    padding: 0;
    border: 0;
    background: none;
    cursor: pointer;
  }
  .bg-img {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    object-fit: cover;
    opacity: 0.52;
  }
  .thumb-scrim {
    position: absolute;
    inset: 0;
    background: linear-gradient(180deg, rgb(0 0 0 / 0.18), rgb(0 0 0 / 0.68));
  }
  .logo-box {
    position: relative;
    z-index: 1;
    width: 54px;
    height: 54px;
    border-radius: 14px;
    overflow: hidden;
    background: rgb(0 0 0 / 0.45);
    box-shadow:
      inset 0 0 0 1px rgb(255 255 255 / 0.18),
      0 6px 16px -6px rgb(0 0 0 / 0.7);
  }
  .logo-box img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
  .ch-num {
    position: absolute;
    top: 8px;
    left: 8px;
    z-index: 2;
    padding: 2px 8px;
    border-radius: 999px;
    background: rgb(10 10 14 / 0.72);
    color: #fff;
    font-size: 0.68rem;
    font-weight: 800;
    font-variant-numeric: tabular-nums;
  }
  .live-tag {
    display: inline-flex;
    align-items: center;
    padding: 2px 8px;
    border-radius: 999px;
    background: #e11d2e;
    color: #fff;
    font-size: 0.65rem;
    font-weight: 820;
    letter-spacing: 0.04em;
  }
  .live-tag.top-right {
    position: absolute;
    top: 8px;
    right: 42px;
    z-index: 2;
  }
  .fav-btn {
    position: absolute;
    top: 5px;
    right: 6px;
    z-index: 3;
    width: 30px;
    height: 30px;
    display: grid;
    place-items: center;
    border: 0;
    border-radius: 50%;
    background: rgb(10 10 14 / 0.62);
    color: rgb(255 255 255 / 0.82);
    font-size: 1rem;
    cursor: pointer;
  }
  .fav-btn[aria-pressed='true'] {
    color: #fbbf24;
  }
  .prog-bar {
    position: absolute;
    left: 0;
    right: 0;
    bottom: 0;
    z-index: 2;
    height: 3px;
    background: rgb(255 255 255 / 0.2);
  }
  .prog-bar i {
    display: block;
    height: 100%;
    background: var(--accent);
  }
  .caption {
    display: grid;
    gap: 2px;
    padding: 0 2px;
    border: 0;
    background: none;
    text-align: left;
    cursor: pointer;
    min-width: 0;
  }
  .ch-title {
    font-size: 0.9rem;
    font-weight: 750;
    color: var(--text-primary);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .ch-now {
    font-size: 0.8rem;
    font-weight: 600;
    color: var(--text-secondary);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .ch-next {
    font-size: 0.72rem;
    color: var(--text-tertiary);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
</style>
