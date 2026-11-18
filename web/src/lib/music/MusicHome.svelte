<script lang="ts">
  // This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer,
  // scrape or hack anything without Mythbrix's explicit permission.
  import { base } from '$app/paths';
  import { GENERIC, report } from '$lib/errors';
  import { SkeletonHero, SkeletonPosterRow } from '$lib/design/skeleton';
  import { music, musicApi, toPlayerTrack } from './player.svelte';
  import {
    DEFAULT_MUSIC_CATEGORIES,
    type MusicAlbumSummary,
    type MusicChart,
    type MusicTrendingFeed
  } from './gateway-music';
  import type { Track } from './core';
  import TrackList from './TrackList.svelte';

  type SearchScope = 'song' | 'album' | 'playlist';

  let { cat = null }: { cat?: string | null } = $props();

  let feed = $state.raw<MusicTrendingFeed | null>(null);
  let loadError = $state<string | null>(null);
  let reloadTick = $state(0);

  let selectedChip = $state('Trending');
  let chipTracks = $state.raw<Track[]>([]);
  let chipCharts = $state.raw<MusicChart[]>([]);
  let chipBusy = $state(false);

  let searchQuery = $state('');
  let searchScope = $state<SearchScope>('song');
  let searchBusy = $state(false);
  let searchPage = $state(1);
  let searchHasMore = $state(false);
  let foundTracks = $state.raw<Track[]>([]);
  let foundAlbums = $state.raw<MusicAlbumSummary[]>([]);
  let foundPlaylists = $state.raw<MusicChart[]>([]);

  const enc = encodeURIComponent;
  const tracksTabActive = $derived(cat === 'mu-tracks');
  const albumsTabActive = $derived(cat === 'mu-albums');

  $effect(() => {
    void reloadTick;
    const ctl = new AbortController();
    loadError = null;
    musicApi.trending(ctl.signal).then(
      (res) => {
        if (ctl.signal.aborted) return;
        feed = res;
        if (res.categories.length && !res.categories.includes(selectedChip)) {
          selectedChip = res.categories[0];
        }
      },
      (err: unknown) => {
        if (!ctl.signal.aborted) {
          loadError = report('music.trending', err, GENERIC.catalog);
        }
      }
    );
    return () => ctl.abort();
  });

  // Category chip filter (live gateway search when a non-Trending genre/artist chip is picked)
  $effect(() => {
    const chip = selectedChip;
    if (!chip || chip === 'Trending' || chip === 'Top Charts' || searchQuery.trim()) {
      chipTracks = [];
      chipCharts = [];
      chipBusy = false;
      return;
    }
    const ctl = new AbortController();
    chipBusy = true;
    Promise.all([
      musicApi.searchTracks(chip, 1, ctl.signal),
      musicApi.searchPlaylists(chip, 1, ctl.signal)
    ])
      .then(([trks, pls]) => {
        if (ctl.signal.aborted) return;
        chipTracks = trks.map(toPlayerTrack);
        chipCharts = pls;
        chipBusy = false;
      })
      .catch(() => {
        if (!ctl.signal.aborted) chipBusy = false;
      });
    return () => ctl.abort();
  });

  // Instant debounced live search across Tracks / Albums / Playlists
  $effect(() => {
    const q = searchQuery.trim();
    const scope = searchScope;
    if (q.length < 2) {
      searchBusy = false;
      searchPage = 1;
      searchHasMore = false;
      foundTracks = [];
      foundAlbums = [];
      foundPlaylists = [];
      return;
    }
    const ctl = new AbortController();
    searchBusy = true;
    const timer = setTimeout(() => {
      if (scope === 'song') {
        musicApi
          .searchTracks(q, 1, ctl.signal)
          .then((res) => {
            if (ctl.signal.aborted) return;
            foundTracks = res.map(toPlayerTrack);
            searchPage = 1;
            searchHasMore = res.length >= 10;
            searchBusy = false;
          })
          .catch(() => {
            if (!ctl.signal.aborted) searchBusy = false;
          });
      } else if (scope === 'album') {
        musicApi
          .searchAlbums(q, 1, ctl.signal)
          .then((res) => {
            if (ctl.signal.aborted) return;
            foundAlbums = res;
            searchHasMore = false;
            searchBusy = false;
          })
          .catch(() => {
            if (!ctl.signal.aborted) searchBusy = false;
          });
      } else {
        musicApi
          .searchPlaylists(q, 1, ctl.signal)
          .then((res) => {
            if (ctl.signal.aborted) return;
            foundPlaylists = res;
            searchHasMore = false;
            searchBusy = false;
          })
          .catch(() => {
            if (!ctl.signal.aborted) searchBusy = false;
          });
      }
    }, 220);

    return () => {
      clearTimeout(timer);
      ctl.abort();
    };
  });

  async function loadMoreSearch() {
    const q = searchQuery.trim();
    if (!q || searchBusy || !searchHasMore || searchScope !== 'song') return;
    const nextPage = searchPage + 1;
    searchBusy = true;
    try {
      const more = await musicApi.searchTracks(q, nextPage);
      const seen = new Set(foundTracks.map((t) => t.id));
      const fresh = more.map(toPlayerTrack).filter((t) => !seen.has(t.id));
      searchPage = nextPage;
      if (!fresh.length) {
        searchHasMore = false;
      } else {
        foundTracks = [...foundTracks, ...fresh];
        searchHasMore = more.length >= 10;
      }
    } finally {
      searchBusy = false;
    }
  }

  const categories = $derived(feed?.categories.length ? feed.categories : [...DEFAULT_MUSIC_CATEGORIES]);
  const charts = $derived(feed?.charts ?? []);
  const trendingTracks = $derived((feed?.trendingTracks ?? []).map(toPlayerTrack));
  const spotlightChart = $derived(charts[0] ?? null);
  const activeSearch = $derived(searchQuery.trim().length >= 2);

  function playTracks(list: Track[], shuffle = false) {
    if (!list.length) return;
    music.setShuffle(shuffle);
    const startIdx = shuffle ? Math.floor(Math.random() * list.length) : 0;
    music.playQueue(list, startIdx);
  }

  function chartHref(chartId: string): string {
    return `${base}/app/music/album?id=${enc(chartId)}&kind=playlist`;
  }

  function albumHref(albumId: string): string {
    return `${base}/app/music/album?id=${enc(albumId)}&kind=album`;
  }
</script>

<div class="music-space">
  <header class="space-head">
    <div class="head-copy">
      <h1>Music</h1>
      <p>Lossless Streaming · Synced Lyrics · FLAC &amp; 320kbps</p>
    </div>

    <div class="q-switch glass" role="group" aria-label="Preferred audio quality">
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
  </header>

  <div class="search-wrap">
    <div class="search-box glass">
      <svg class="s-ic" viewBox="0 0 24 24" width="19" height="19" aria-hidden="true">
        <circle cx="11" cy="11" r="7" fill="none" stroke="currentColor" stroke-width="2" />
        <path d="M16.5 16.5L21 21" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
      </svg>
      <input
        type="search"
        bind:value={searchQuery}
        placeholder="Search songs, albums, artists, playlists…"
        aria-label="Search music"
        autocomplete="off"
        spellcheck="false"
      />
      {#if searchQuery}
        <button type="button" class="clear-btn" onclick={() => (searchQuery = '')} aria-label="Clear search">✕</button>
      {/if}
    </div>

    {#if activeSearch}
      <div class="h-scroll scope-tabs" role="tablist" aria-label="Music search categories">
        <button
          type="button"
          class="chip"
          role="tab"
          aria-selected={searchScope === 'song'}
          aria-pressed={searchScope === 'song'}
          onclick={() => (searchScope = 'song')}
        >
          Tracks
        </button>
        <button
          type="button"
          class="chip"
          role="tab"
          aria-selected={searchScope === 'album'}
          aria-pressed={searchScope === 'album'}
          onclick={() => (searchScope = 'album')}
        >
          Albums
        </button>
        <button
          type="button"
          class="chip"
          role="tab"
          aria-selected={searchScope === 'playlist'}
          aria-pressed={searchScope === 'playlist'}
          onclick={() => (searchScope = 'playlist')}
        >
          Playlists
        </button>
      </div>
    {:else}
      <div class="h-scroll chips" role="group" aria-label="Music categories">
        {#each categories as chipName (chipName)}
          <button
            type="button"
            class="chip"
            aria-pressed={selectedChip === chipName}
            onclick={() => (selectedChip = chipName)}
          >
            {chipName}
          </button>
        {/each}
      </div>
    {/if}
  </div>

  {#if feed?.offlineFallback}
    <div class="offline-pill glass" role="status">
      <span>Offline music library · Connect for live charts, FLAC &amp; synced lyrics</span>
      <button type="button" class="chip" onclick={() => reloadTick++}>Retry</button>
    </div>
  {/if}

  {#if activeSearch}
    <section aria-label="Search results">
      <div class="sec-bar">
        <h2 class="section-title">Results for “{searchQuery.trim()}”</h2>
      </div>

      {#if searchBusy && !foundTracks.length && !foundAlbums.length && !foundPlaylists.length}
        <div aria-busy="true" aria-label="Searching music"><SkeletonPosterRow /></div>
      {:else if searchScope === 'song'}
        {#if foundTracks.length}
          <TrackList tracks={foundTracks} />
          {#if searchHasMore}
            <div class="more-wrap">
              <button type="button" class="btn btn-ghost" disabled={searchBusy} onclick={loadMoreSearch}>
                {searchBusy ? 'Loading…' : 'Load more songs'}
              </button>
            </div>
          {/if}
        {:else}
          <p class="empty-box">No tracks matched “{searchQuery.trim()}”.</p>
        {/if}
      {:else if searchScope === 'album'}
        {#if foundAlbums.length}
          <div class="card-grid">
            {#each foundAlbums as al (al.id)}
              <a class="chart-card glass" href={albumHref(al.id)}>
                <div class="art-box">
                  {#if al.artwork}
                    <img referrerpolicy="no-referrer" src={al.artwork} alt="" width="164" height="164" loading="lazy" decoding="async" />
                  {:else}
                    <div class="card-ph" aria-hidden="true">♪</div>
                  {/if}
                  <span class="play-badge" aria-hidden="true">▶</span>
                </div>
                <span class="card-title">{al.title}</span>
                <small class="card-sub">{al.artist}{al.year ? ` · ${al.year}` : ''}</small>
              </a>
            {/each}
          </div>
        {:else}
          <p class="empty-box">No albums matched “{searchQuery.trim()}”.</p>
        {/if}
      {:else}
        {#if foundPlaylists.length}
          <div class="card-grid">
            {#each foundPlaylists as pl (pl.id)}
              <a class="chart-card glass" href={chartHref(pl.id)}>
                <div class="art-box">
                  {#if pl.artwork}
                    <img referrerpolicy="no-referrer" src={pl.artwork} alt="" width="164" height="164" loading="lazy" decoding="async" />
                  {:else}
                    <div class="card-ph" aria-hidden="true">♪</div>
                  {/if}
                  <span class="play-badge" aria-hidden="true">▶</span>
                </div>
                <span class="card-title">{pl.title}</span>
                <small class="card-sub">{pl.count ? `${pl.count} tracks` : (pl.subtitle ?? 'Playlist')}</small>
              </a>
            {/each}
          </div>
        {:else}
          <p class="empty-box">No playlists matched “{searchQuery.trim()}”.</p>
        {/if}
      {/if}
    </section>
  {:else if loadError && !feed}
    <div class="empty-box" role="alert">
      <p>{loadError}</p>
      <button type="button" class="btn btn-ghost" onclick={() => reloadTick++}>Retry</button>
    </div>
  {:else if !feed}
    <div aria-busy="true" aria-label="Loading music">
      <SkeletonHero />
      <SkeletonPosterRow />
      <SkeletonPosterRow />
    </div>
  {:else if selectedChip !== 'Trending' && selectedChip !== 'Top Charts'}
    <section aria-label="{selectedChip} music">
      {#if chipBusy}
        <div aria-busy="true"><SkeletonPosterRow /></div>
      {:else}
        {#if chipCharts.length}
          <div class="sec-bar">
            <h2 class="section-title">{selectedChip} Playlists</h2>
          </div>
          <div class="h-scroll">
            {#each chipCharts as pl (pl.id)}
              <a class="chart-card glass carousel-card" href={chartHref(pl.id)}>
                <div class="art-box">
                  {#if pl.artwork}
                    <img referrerpolicy="no-referrer" src={pl.artwork} alt="" width="156" height="156" loading="lazy" decoding="async" />
                  {:else}
                    <div class="card-ph" aria-hidden="true">♪</div>
                  {/if}
                  <span class="play-badge" aria-hidden="true">▶</span>
                </div>
                <span class="card-title">{pl.title}</span>
                <small class="card-sub">{pl.count ? `${pl.count} tracks` : 'Lossless Mix'}</small>
              </a>
            {/each}
          </div>
        {/if}

        <div class="sec-bar">
          <h2 class="section-title">{selectedChip} Songs</h2>
          {#if chipTracks.length}
            <div class="sec-actions">
              <button type="button" class="chip active" onclick={() => playTracks(chipTracks, false)}>▶ Play All</button>
              <button type="button" class="chip" onclick={() => playTracks(chipTracks, true)}>⤮ Shuffle</button>
            </div>
          {/if}
        </div>
        {#if chipTracks.length}
          <TrackList tracks={chipTracks} />
        {:else}
          <p class="empty-box">No songs found for {selectedChip}.</p>
        {/if}
      {/if}
    </section>
  {:else}
    {#if !tracksTabActive && (spotlightChart || trendingTracks[0])}
      {@const heroArt = spotlightChart?.artwork || trendingTracks[0]?.art || ''}
      <section class="hero glass" style:--c={trendingTracks[0]?.color ?? 'var(--accent)'}>
        {#if heroArt}
          <div class="hbg" style:background-image="url({heroArt})" aria-hidden="true"></div>
          <img class="hart" referrerpolicy="no-referrer" src={heroArt} alt="" width="188" height="188" decoding="async" />
        {:else}
          <div class="hart card-ph" aria-hidden="true">♪</div>
        {/if}
        <div class="htxt">
          <span class="kicker">FEATURED CHART · STUDIO MASTER</span>
          <h2>{spotlightChart?.title ?? 'Trending Now'}</h2>
          <p>
            {#if spotlightChart}
              {spotlightChart.count ? `${spotlightChart.count} tracks · ` : ''}Lossless FLAC &amp; 320kbps · Time-synced lyrics
            {:else}
              {trendingTracks.length} trending tracks · Lossless FLAC &amp; 320kbps
            {/if}
          </p>
          <div class="acts">
            {#if trendingTracks.length}
              <button type="button" class="btn btn-primary" onclick={() => playTracks(trendingTracks, false)}>
                ▶ Play Trending
              </button>
            {/if}
            {#if spotlightChart}
              <a class="btn btn-ghost" href={chartHref(spotlightChart.id)}>Open Chart</a>
            {/if}
          </div>
        </div>
      </section>
    {/if}

    {#if charts.length && !tracksTabActive}
      <div class="sec-bar">
        <h2 class="section-title">{albumsTabActive ? 'Charts & Featured Collections' : 'Top Charts & Playlists'}</h2>
        <span class="sec-tag">FLAC · 320K</span>
      </div>

      {#if albumsTabActive}
        <div class="card-grid">
          {#each charts as chart (chart.id)}
            <a class="chart-card glass" href={chartHref(chart.id)}>
              <div class="art-box">
                {#if chart.artwork}
                  <img referrerpolicy="no-referrer" src={chart.artwork} alt="" width="164" height="164" loading="lazy" decoding="async" />
                {:else}
                  <div class="card-ph" aria-hidden="true">♪</div>
                {/if}
                <span class="play-badge" aria-hidden="true">▶</span>
              </div>
              <span class="card-title">{chart.title}</span>
              <small class="card-sub">{chart.count ? `${chart.count} tracks` : (chart.subtitle ?? 'Curated Chart')}</small>
            </a>
          {/each}
        </div>
      {:else}
        <div class="h-scroll">
          {#each charts as chart (chart.id)}
            <a class="chart-card glass carousel-card" href={chartHref(chart.id)}>
              <div class="art-box">
                {#if chart.artwork}
                  <img referrerpolicy="no-referrer" src={chart.artwork} alt="" width="156" height="156" loading="lazy" decoding="async" />
                {:else}
                  <div class="card-ph" aria-hidden="true">♪</div>
                {/if}
                <span class="play-badge" aria-hidden="true">▶</span>
              </div>
              <span class="card-title">{chart.title}</span>
              <small class="card-sub">{chart.count ? `${chart.count} tracks` : (chart.subtitle ?? 'Curated Chart')}</small>
            </a>
          {/each}
        </div>
      {/if}
    {/if}

    {#if !albumsTabActive && !tracksTabActive && trendingTracks.length}
      <div class="sec-bar">
        <h2 class="section-title">Quick Picks</h2>
        <span class="sec-tag">{music.quality === 'flac' ? 'FLAC' : '320K'}</span>
      </div>
      <div class="h-scroll">
        {#each trendingTracks.slice(0, 12) as trk, idx (trk.id)}
          <button
            type="button"
            class="chart-card track-thumb glass carousel-card"
            class:is-playing={music.current?.id === trk.id}
            onclick={() => music.playQueue(trendingTracks, idx)}
          >
            <div class="art-box">
              {#if trk.art}
                <img referrerpolicy="no-referrer" src={trk.art} alt="" width="156" height="156" loading="lazy" decoding="async" />
              {:else}
                <div class="card-ph" aria-hidden="true">♪</div>
              {/if}
              <span class="play-badge" aria-hidden="true">{music.current?.id === trk.id && !music.paused ? '❚❚' : '▶'}</span>
            </div>
            <span class="card-title">{trk.title}</span>
            <small class="card-sub">{trk.artist}</small>
          </button>
        {/each}
      </div>
    {/if}

    {#if !albumsTabActive || selectedChip === 'Top Charts'}
      <div class="sec-bar">
        <h2 class="section-title">Trending Songs</h2>
        {#if trendingTracks.length}
          <div class="sec-actions">
            <button type="button" class="chip active" onclick={() => playTracks(trendingTracks, false)}>▶ Play All</button>
            <button type="button" class="chip" onclick={() => playTracks(trendingTracks, true)}>⤮ Shuffle</button>
          </div>
        {/if}
      </div>

      {#if trendingTracks.length}
        <TrackList tracks={trendingTracks} />
      {:else}
        <p class="empty-box">No trending tracks available right now.</p>
      {/if}
    {/if}
  {/if}
</div>

<style>
  .music-space {
    padding-bottom: 16px;
  }
  .space-head {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 4px 16px 10px;
  }
  .head-copy h1 {
    margin: 0;
    font-size: clamp(1.45rem, 3.5vw, 1.95rem);
    letter-spacing: -0.02em;
  }
  .head-copy p {
    margin: 2px 0 0;
    font-size: 0.8rem;
    color: var(--text-secondary);
  }
  .q-switch {
    display: inline-flex;
    padding: 3px;
    border-radius: var(--radius-pill, 999px);
  }
  .q-switch button {
    padding: 6px 13px;
    border: 0;
    border-radius: var(--radius-pill, 999px);
    background: none;
    color: var(--text-secondary);
    font-size: 0.78rem;
    font-weight: 700;
    cursor: pointer;
    transition: background 160ms ease, color 160ms ease;
  }
  .q-switch button.active {
    background: linear-gradient(135deg, var(--accent), var(--accent-end));
    color: var(--on-accent);
    box-shadow: 0 4px 14px -4px var(--accent-glow);
  }
  .search-wrap {
    display: grid;
    gap: 8px;
  }
  .search-box {
    margin: 0 16px;
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 0 14px;
    min-height: 46px;
    border-radius: 18px;
  }
  .s-ic {
    flex: none;
    color: var(--text-secondary);
  }
  .search-box input {
    flex: 1;
    min-width: 0;
    border: 0;
    background: none;
    color: var(--text-primary);
    font: inherit;
    font-size: 0.94rem;
    padding: 10px 0;
    outline: none;
  }
  .search-box input::placeholder {
    color: var(--text-tertiary);
  }
  .clear-btn {
    flex: none;
    width: 30px;
    height: 30px;
    border: 0;
    border-radius: 50%;
    background: var(--glass-fill);
    color: var(--text-secondary);
    cursor: pointer;
  }
  .chips,
  .scope-tabs {
    padding-top: 2px;
    padding-bottom: 8px;
  }
  .offline-pill {
    margin: 4px 16px 12px;
    padding: 10px 14px;
    border-radius: 16px;
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    font-size: 0.82rem;
    color: var(--text-secondary);
  }
  .hero {
    position: relative;
    display: flex;
    flex-wrap: wrap;
    gap: 20px;
    align-items: center;
    margin: 6px 16px 10px;
    padding: 22px;
    border-radius: 26px;
    overflow: hidden;
    background:
      linear-gradient(135deg, color-mix(in srgb, var(--c) 22%, transparent), transparent 72%),
      var(--glass-fill);
  }
  .hbg {
    position: absolute;
    inset: -40px;
    z-index: -1;
    background-size: cover;
    background-position: center;
    filter: blur(42px) saturate(1.3);
    opacity: 0.24;
  }
  :global([data-tier='low']) .hbg {
    display: none;
  }
  .hart {
    width: clamp(112px, 28vw, 184px);
    height: auto;
    aspect-ratio: 1;
    border-radius: 18px;
    object-fit: cover;
    box-shadow: 0 18px 38px -16px rgb(0 0 0 / 0.65);
  }
  .htxt {
    flex: 1;
    min-width: 210px;
  }
  .kicker {
    font-size: 0.7rem;
    letter-spacing: 0.1em;
    text-transform: uppercase;
    color: var(--accent);
    font-weight: 800;
  }
  .htxt h2 {
    margin: 4px 0 6px;
    font-size: clamp(1.3rem, 3.8vw, 2rem);
    line-height: 1.15;
  }
  .htxt p {
    margin: 0 0 14px;
    color: var(--text-secondary);
    font-size: 0.88rem;
  }
  .acts {
    display: flex;
    gap: 10px;
    flex-wrap: wrap;
  }
  .sec-bar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    padding-right: 16px;
  }
  .sec-tag {
    font-size: 0.7rem;
    font-weight: 800;
    letter-spacing: 0.06em;
    color: var(--accent);
  }
  .sec-actions {
    display: flex;
    gap: 8px;
    align-items: center;
  }
  .carousel-card {
    flex: none;
    width: 166px;
    scroll-snap-align: start;
  }
  .card-grid {
    padding: 4px 16px 12px;
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(154px, 1fr));
    gap: 14px;
  }
  .chart-card {
    display: grid;
    align-content: start;
    gap: 6px;
    padding: 10px;
    border-radius: 18px;
    text-decoration: none;
    color: var(--text-primary);
    transition: transform calc(var(--dur-short, 150ms) * var(--dur-scale, 1)) var(--ease-emphasized, ease);
  }
  .track-thumb {
    border: 0;
    font: inherit;
    text-align: left;
    cursor: pointer;
  }
  .track-thumb.is-playing {
    box-shadow: 0 0 0 2px var(--accent);
  }
  .chart-card:hover {
    transform: translateY(-2px);
  }
  .art-box {
    position: relative;
  }
  .chart-card img,
  .card-ph {
    display: block;
    width: 100%;
    aspect-ratio: 1;
    height: auto;
    border-radius: 13px;
    object-fit: cover;
    background: var(--surface-elevated);
  }
  .card-ph {
    display: grid;
    place-items: center;
    font-size: 2rem;
    color: var(--text-tertiary);
  }
  .play-badge {
    position: absolute;
    right: 8px;
    bottom: 8px;
    width: 30px;
    height: 30px;
    border-radius: 50%;
    display: grid;
    place-items: center;
    font-size: 0.72rem;
    background: var(--accent);
    color: var(--on-accent);
    box-shadow: 0 6px 16px rgb(0 0 0 / 0.45);
    opacity: 0.92;
  }
  .card-title {
    font-size: 0.88rem;
    font-weight: 700;
    line-height: 1.3;
    color: var(--text-primary);
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }
  .track-thumb.is-playing .card-title {
    color: var(--accent);
  }
  .card-sub {
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    color: var(--text-secondary);
    font-size: 0.76rem;
  }
  .more-wrap {
    display: flex;
    justify-content: center;
    padding: 16px;
  }
  .empty-box {
    padding: 40px 16px;
    text-align: center;
    color: var(--text-secondary);
  }
</style>
