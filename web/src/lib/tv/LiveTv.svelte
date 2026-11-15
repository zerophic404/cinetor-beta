<script lang="ts">
  import { onMount } from 'svelte';
  import { repo, img } from '$lib/data/repo';
  import type { CatalogFile, Item } from '$lib/data/types';
  import { SkeletonEpgGrid } from '$lib/design/skeleton';
  import { watchHref } from '$lib/ui/handoff';
  import { FAVORITES, channelGroups, channelsOf, currentProgram, filterChannels, genreChips, nextProgram, programProgress, clockLabel } from './epg';
  import { live } from './live.svelte';
  import EpgGuide from './EpgGuide.svelte';
  import Sports from './Sports.svelte';

  let { cat = null }: { cat?: string | null } = $props();

  let file = $state.raw<CatalogFile | null>(null);
  let error = $state<string | null>(null);
  let group = $state<string | null>(null);
  let genre = $state<string | null>(null);
  let view = $state<'grid' | 'guide'>('grid');
  let reload = $state(0);

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

  const sports = $derived(cat === 'tv-sports');
  const channels = $derived(file ? channelsOf(file.items) : []);
  const groups = $derived(channelGroups(channels));
  const inGroup = $derived(filterChannels(channels, { group, genre: null, favorites: live.favorites, baseSec: live.base, nowSec: live.now }));
  const genres = $derived(genreChips(inGroup, file?.genres ?? [], live.base, live.now));
  const shown = $derived(filterChannels(channels, { group, genre, favorites: live.favorites, baseSec: live.base, nowSec: live.now }));

  function pickGroup(g: string | null) {
    group = g;
    genre = null;
  }
</script>

{#if error}
  <div class="state" role="alert">
    <p>Couldn't load Live TV.</p>
    <button class="btn btn-ghost" onclick={() => reload++}>Retry</button>
  </div>
{:else if !file}
  <div class="loading" aria-busy="true" aria-label="Loading guide">
    <div class="head-sk"></div>
    <SkeletonEpgGrid channels={7} />
  </div>
{:else if sports}
  <header class="head">
    <h1>Sports</h1>
  </header>
  <Sports items={file.items} />
{:else}
  <header class="head">
    <h1>Live TV</h1>
    <div class="seg" role="radiogroup" aria-label="View">
      <button role="radio" aria-checked={view === 'grid'} class:on={view === 'grid'} onclick={() => (view = 'grid')}>Channels</button>
      <button role="radio" aria-checked={view === 'guide'} class:on={view === 'guide'} onclick={() => (view = 'guide')}>Guide</button>
    </div>
  </header>

  <div class="h-scroll" role="group" aria-label="Channel categories">
    <button class="chip" aria-pressed={group === null} onclick={() => pickGroup(null)}>All</button>
    <button class="chip" aria-pressed={group === FAVORITES} onclick={() => pickGroup(FAVORITES)}>★ Favorites</button>
    {#each groups as g (g)}
      <button class="chip" aria-pressed={group === g} onclick={() => pickGroup(g)}>{g}</button>
    {/each}
  </div>
  {#if genres.length > 1}
    <div class="h-scroll genres" role="group" aria-label="Genres">
      {#each genres as g (g)}
        <button class="chip small" aria-pressed={genre === g} onclick={() => (genre = genre === g ? null : g)}>{g}</button>
      {/each}
    </div>
  {/if}

  {#if !shown.length}
    <p class="state">{group === FAVORITES ? 'Tap ☆ on a channel to add it to your favorites.' : 'No channels match.'}</p>
  {:else if view === 'guide'}
    <EpgGuide channels={shown} base={live.base} now={live.now} />
  {:else}
    <div class="grid">
      {#each shown as ch (ch.id)}
        {@const cur = currentProgram(ch.programs, live.base, live.now)}
        {@const nxt = nextProgram(ch.programs, live.base, live.now)}
        <div class="ch glass" style:--c={ch.accentColor}>
          <a class="hit" href={watchHref(ch.id)} aria-label="Watch {ch.title}{cur ? ` — ${cur.program.title}` : ''}">
            <span class="logo">
              {#if ch.logo}<img src={img(ch.logo)} alt="" loading="lazy" width="56" height="56" />{/if}
            </span>
            <span class="meta">
              <span class="top"><b>{ch.channelNumber}</b> {ch.title}</span>
              <span class="now">{cur?.program.title ?? 'Off air'}</span>
              {#if cur}<span class="bar"><i style:width="{programProgress(cur, live.now) * 100}%"></i></span>{/if}
              {#if nxt}<span class="next">Next {clockLabel(nxt.start)} · {nxt.program.title}</span>{/if}
            </span>
            <span class="live-tag">LIVE</span>
          </a>
          <button class="fav" aria-pressed={live.isFav(ch.id)} aria-label={live.isFav(ch.id) ? `Remove ${ch.title} from favorites` : `Add ${ch.title} to favorites`} onclick={() => live.toggleFav(ch.id)}>{live.isFav(ch.id) ? '★' : '☆'}</button>
        </div>
      {/each}
    </div>
  {/if}
{/if}

<style>
  .head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 8px 16px 4px;
  }
  h1 {
    margin: 0;
    font-size: 1.5rem;
  }
  .head-sk {
    height: 64px;
  }
  .seg {
    display: flex;
    padding: 3px;
    border-radius: 99px;
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
  }
  .seg button {
    border: 0;
    background: none;
    padding: 6px 14px;
    border-radius: 99px;
    font-weight: 650;
    cursor: pointer;
    color: var(--text-secondary);
  }
  .seg button.on {
    color: var(--on-accent);
    background: linear-gradient(135deg, var(--accent), var(--accent-end));
  }
  .genres {
    padding-top: 0;
  }
  .chip.small {
    min-height: 30px;
    font-size: 0.8rem;
    padding: 0 12px;
  }
  .state {
    padding: 40px 16px;
    text-align: center;
    color: var(--text-secondary);
  }
  .grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
    gap: 12px;
    padding: 4px 16px;
  }
  .ch {
    position: relative;
    border-radius: 20px;
  }
  .hit {
    display: flex;
    gap: 12px;
    align-items: center;
    padding: 12px 44px 12px 12px;
    text-decoration: none;
    border-radius: 20px;
  }
  .hit:hover,
  .hit:focus-visible {
    background: color-mix(in srgb, var(--c) 16%, transparent);
  }
  .logo {
    flex: none;
    width: 56px;
    height: 56px;
    border-radius: 14px;
    overflow: hidden;
    background: color-mix(in srgb, var(--c) 40%, #111);
  }
  .logo img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
  .meta {
    display: grid;
    gap: 3px;
    min-width: 0;
    flex: 1;
  }
  .top {
    font-size: 0.8rem;
    color: var(--text-secondary);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .top b {
    color: var(--accent);
  }
  .now {
    font-weight: 700;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .next {
    font-size: 0.72rem;
    color: var(--text-tertiary);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .bar {
    height: 3px;
    border-radius: 3px;
    background: rgb(255 255 255 / 0.14);
    overflow: hidden;
  }
  .bar i {
    display: block;
    height: 100%;
    background: var(--accent);
  }
  .live-tag {
    position: absolute;
    top: 10px;
    left: 10px;
    font-size: 0.55rem;
    font-weight: 800;
    padding: 1px 5px;
    border-radius: 4px;
    background: #e50914;
    color: #fff;
  }
  .fav {
    position: absolute;
    top: 50%;
    right: 8px;
    translate: 0 -50%;
    width: 36px;
    height: 36px;
    border: 0;
    border-radius: 50%;
    background: none;
    font-size: 1.2rem;
    cursor: pointer;
    color: var(--text-secondary);
  }
  .fav[aria-pressed='true'] {
    color: #ffc93c;
  }
</style>
