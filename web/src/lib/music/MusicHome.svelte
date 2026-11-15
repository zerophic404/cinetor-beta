<script lang="ts">
  import { repo, img } from '$lib/data/repo';
  import type { CatalogFile, Item } from '$lib/data/types';
  import { SkeletonHero, SkeletonPosterRow } from '$lib/design/skeleton';
  import { detailsHref } from '$lib/ui/handoff';
  import { music } from './player.svelte';
  import { trackOf, type Track } from './core';
  import TrackList from './TrackList.svelte';

  let { cat = null }: { cat?: string | null } = $props();

  let file = $state.raw<CatalogFile | null>(null);
  let error = $state<string | null>(null);
  let genre = $state<string | null>(null);
  let artist = $state<string | null>(null);
  let reload = $state(0);

  $effect(() => {
    void reload;
    let alive = true;
    error = null;
    repo.file('MUSIC').then(
      (f) => alive && (file = f),
      (e: unknown) => alive && (error = e instanceof Error ? e.message : String(e))
    );
    return () => {
      alive = false;
    };
  });

  const tracksView = $derived(cat === 'mu-tracks');
  const byId = $derived(new Map((file?.items ?? []).map((i) => [i.id, i] as const)));
  const match = (i: Item) => (!genre || i.genres.includes(genre)) && (!artist || i.artist === artist);
  const albums = $derived((file?.items ?? []).filter((i) => i.type === 'ALBUM' && match(i)));
  const tracks = $derived((file?.items ?? []).filter((i) => i.type === 'TRACK' && match(i)));
  const artists = $derived.by(() => {
    const seen = new Map<string, Item>();
    for (const a of file?.items ?? []) if (a.type === 'ALBUM' && a.artist && !seen.has(a.artist)) seen.set(a.artist, a);
    return [...seen.entries()].map(([name, al]) => ({ name, art: al.poster, color: al.dominantColor }));
  });
  const discover = $derived(file?.categories.find((c) => c.id === 'mu-discover'));
  const moods = $derived(file?.categories.find((c) => c.id === 'mu-moods'));
  const hero = $derived.by(() => {
    const ids = discover?.heroIds ?? [];
    return ids.map((id) => byId.get(id)).find((i): i is Item => !!i && match(i)) ?? albums[0];
  });
  const top = $derived.by(() => {
    const r = discover?.rows.find((x) => x.style === 'TOP10');
    return (r?.itemIds ?? []).map((id) => byId.get(id)).filter((i): i is Item => !!i && match(i));
  });

  function albumTracks(al: Item): Track[] {
    return (al.trackIds ?? []).map((id) => byId.get(id)).map((i) => (i ? trackOf(i, img) : null)).filter((t): t is Track => !!t);
  }
  function rowTracks(ids: string[]): Track[] {
    return ids.map((id) => byId.get(id)).map((i) => (i ? trackOf(i, img) : null)).filter((t): t is Track => !!t);
  }
</script>

{#if error}
  <div class="state" role="alert">
    <p>Couldn't load Music.</p>
    <button class="btn btn-ghost" onclick={() => reload++}>Retry</button>
  </div>
{:else if !file}
  <div aria-busy="true" aria-label="Loading"><SkeletonHero /><SkeletonPosterRow /><SkeletonPosterRow /></div>
{:else}
  {#if hero && !tracksView}
    <section class="hero" style:--c={hero.dominantColor} style:--a={hero.accentColor}>
      <div class="hbg" style:background-image="url({img(hero.poster)})" aria-hidden="true"></div>
      <img class="hart" src={img(hero.poster)} alt="" width="200" height="200" />
      <div class="htxt">
        <span class="kicker">Featured album</span>
        <h1><a href={detailsHref(hero)}>{hero.title}</a></h1>
        <p>{hero.artist} · {hero.year} · {hero.trackIds?.length ?? 0} tracks</p>
        <div class="acts">
          <button class="btn btn-primary" onclick={() => hero && music.playQueue(albumTracks(hero), 0)}>▶ Play</button>
          <a class="btn btn-ghost" href={detailsHref(hero)}>Open album</a>
        </div>
      </div>
    </section>
  {:else}
    <h1 class="title">All tracks</h1>
  {/if}

  <div class="h-scroll chips" role="group" aria-label="Genres">
    <button class="chip" aria-pressed={genre === null} onclick={() => (genre = null)}>All</button>
    {#each file.genres ?? [] as g (g)}
      <button class="chip" aria-pressed={genre === g} onclick={() => (genre = genre === g ? null : g)}>{g}</button>
    {/each}
  </div>
  {#if artist}
    <p class="filter">Artist: <b>{artist}</b> <button class="chip" onclick={() => (artist = null)}>✕ Clear</button></p>
  {/if}

  {#if tracksView}
    {#if moods && !genre && !artist}
      <h2 class="section-title">Moods</h2>
      <div class="h-scroll">
        {#each moods.rows as r (r.id)}
          {@const first = byId.get(r.itemIds[0])}
          <button class="mood" style:--c={first?.dominantColor ?? '#300'} onclick={() => music.playQueue(rowTracks(r.itemIds), 0)}>
            <b>{r.title}</b><small>{r.itemIds.length} tracks · ▶</small>
          </button>
        {/each}
      </div>
    {/if}
    <h2 class="section-title">{tracks.length} tracks</h2>
    <TrackList items={tracks} />
  {:else}
    <h2 class="section-title">Albums</h2>
    {#if albums.length}
      <div class="h-scroll">
        {#each albums as al (al.id)}
          <a class="album" href={detailsHref(al)}>
            <img src={img(al.poster)} alt="" width="160" height="160" loading="lazy" style:background={al.dominantColor} />
            <b>{al.title}</b><small>{al.artist}</small>
          </a>
        {/each}
      </div>
    {:else}
      <p class="state">No albums match.</p>
    {/if}

    {#if top.length}
      <h2 class="section-title">Top tracks</h2>
      <TrackList items={top} />
    {/if}

    {#if !artist}
      <h2 class="section-title">Artists</h2>
      <div class="h-scroll">
        {#each artists as a (a.name)}
          <button class="artist" onclick={() => (artist = a.name)}>
            <img src={img(a.art)} alt="" width="96" height="96" loading="lazy" style:background={a.color} />
            <span>{a.name}</span>
          </button>
        {/each}
      </div>
    {/if}
  {/if}
{/if}

<style>
  .state {
    padding: 40px 16px;
    text-align: center;
    color: var(--text-secondary);
  }
  .title {
    margin: 8px 16px 0;
  }
  .hero {
    position: relative;
    display: flex;
    gap: 20px;
    align-items: center;
    margin: 4px 16px 6px;
    padding: 22px;
    border-radius: 28px;
    overflow: hidden;
    isolation: isolate;
    background: linear-gradient(135deg, color-mix(in srgb, var(--c) 85%, #000), #000 90%);
  }
  .hbg {
    position: absolute;
    inset: -40px;
    z-index: -1;
    background-size: cover;
    background-position: center;
    filter: blur(40px) saturate(1.3);
    opacity: 0.45;
  }
  :global([data-tier='low']) .hbg {
    display: none;
  }
  .hart {
    width: clamp(110px, 30vw, 200px);
    height: auto;
    aspect-ratio: 1;
    border-radius: 18px;
    box-shadow: 0 20px 40px -16px rgb(0 0 0 / 0.8);
    object-fit: cover;
  }
  .kicker {
    font-size: 0.72rem;
    letter-spacing: 0.1em;
    text-transform: uppercase;
    color: var(--a);
    font-weight: 800;
  }
  h1 {
    margin: 4px 0;
    font-size: clamp(1.3rem, 4vw, 2.2rem);
  }
  h1 a {
    text-decoration: none;
  }
  .htxt p {
    margin: 0 0 12px;
    color: var(--text-secondary);
  }
  .acts {
    display: flex;
    gap: 10px;
    flex-wrap: wrap;
  }
  .chips {
    margin-top: 6px;
  }
  .filter {
    margin: 0 16px;
    display: flex;
    gap: 8px;
    align-items: center;
  }
  .album {
    flex: none;
    width: 150px;
    display: grid;
    gap: 4px;
    text-decoration: none;
    scroll-snap-align: start;
  }
  .album img {
    width: 150px;
    height: 150px;
    border-radius: 14px;
    object-fit: cover;
  }
  .album b,
  .album small {
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .album small {
    color: var(--text-secondary);
    font-size: 0.78rem;
  }
  .artist {
    flex: none;
    display: grid;
    justify-items: center;
    gap: 6px;
    width: 104px;
    border: 0;
    background: none;
    cursor: pointer;
    font-size: 0.8rem;
    font-weight: 650;
  }
  .artist img {
    width: 96px;
    height: 96px;
    border-radius: 50%;
    object-fit: cover;
  }
  .mood {
    flex: none;
    width: 170px;
    height: 100px;
    display: grid;
    align-content: end;
    gap: 2px;
    padding: 14px;
    border: 0;
    border-radius: 18px;
    text-align: left;
    cursor: pointer;
    background: linear-gradient(135deg, var(--c), color-mix(in srgb, var(--c) 30%, #000));
  }
  .mood small {
    opacity: 0.8;
  }
</style>
