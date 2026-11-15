<script lang="ts">
  import { catalog } from '$lib/data/app-catalog';
  import { repo } from '$lib/data/repo';
  import { myList } from '$lib/data/library.svelte';
  import type { Item } from '$lib/data/types';
  import { resume } from '$lib/player/controller';
  import type { ResumeEntry } from '$lib/player/resume';
  import { profiles, clearProfileHistory } from '$lib/profile/store.svelte';
  import { SkeletonPosterRow } from '$lib/design/skeleton';
  import Poster from '$lib/components/Poster.svelte';
  import { watchHref } from '$lib/ui/handoff';
  import { imgFor } from '$lib/ui/img';

  let tab = $state<'watchlist' | 'history'>('watchlist');
  let watchlist = $state.raw<Item[] | null>(null);
  let albums = $state.raw<Item[]>([]);
  let comics = $state.raw<Item[]>([]);
  let history = $state.raw<{ e: ResumeEntry; item?: Item }[] | null>(null);
  let reload = $state(0);

  async function lookup(ids: string[], space?: 'MUSIC' | 'COMICS'): Promise<Item[]> {
    const items = await Promise.all(ids.map((id) => (space ? repo.item(id, space) : catalog.item(id)).catch(() => undefined)));
    return items.filter((i): i is Item => !!i);
  }

  $effect(() => {
    void reload;
    let alive = true;
    // Video watchlist (global scope = Home/Anime/Drama only); isolated spaces keep their own lists.
    lookup(myList.ids('video')).then((l) => alive && (watchlist = l));
    lookup(myList.ids('music'), 'MUSIC').then((l) => alive && (albums = l));
    lookup(myList.ids('comics'), 'COMICS').then((l) => alive && (comics = l));
    resume.continueWatching(profiles.activeId, 40).then(async (entries) => {
      const rows = await Promise.all(entries.map(async (e) => ({ e, item: await catalog.item(e.itemId, e.space).catch(() => undefined) })));
      if (alive) history = rows;
    });
    return () => {
      alive = false;
    };
  });

  async function clearHistory() {
    if (!confirm('Clear watch history and search recents for this profile?')) return;
    await clearProfileHistory(profiles.activeId);
    reload++;
  }
  const pct = (e: ResumeEntry) => (e.dur > 0 ? Math.min(100, (e.pos / e.dur) * 100) : 0);
  const left = (e: ResumeEntry) => `${Math.max(1, Math.round((e.dur - e.pos) / 60))} min left`;
</script>

<svelte:head><title>Library · Cinetor</title></svelte:head>

<header class="head">
  <h1>Library</h1>
  <div class="h-scroll tabs" role="tablist" aria-label="Library">
    <button class="chip" role="tab" aria-selected={tab === 'watchlist'} class:active={tab === 'watchlist'} onclick={() => (tab = 'watchlist')}>Watchlist</button>
    <button class="chip" role="tab" aria-selected={tab === 'history'} class:active={tab === 'history'} onclick={() => (tab = 'history')}>Continue watching</button>
  </div>
</header>

{#if tab === 'watchlist'}
  {#if watchlist === null}
    <SkeletonPosterRow />
  {:else if !watchlist.length && !albums.length && !comics.length}
    <p class="empty">Nothing saved yet. Tap “My List” on any movie or series.</p>
  {:else}
    {#if watchlist.length}
      <h2 class="section-title">Movies & shows · {watchlist.length}</h2>
      <div class="grid">{#each watchlist as item (item.id)}<Poster {item} />{/each}</div>
    {/if}
    {#if albums.length}
      <h2 class="section-title">Saved albums · {albums.length}</h2>
      <div class="grid">{#each albums as item (item.id)}<Poster {item} />{/each}</div>
    {/if}
    {#if comics.length}
      <h2 class="section-title">Bookmarked comics · {comics.length}</h2>
      <div class="grid">{#each comics as item (item.id)}<Poster {item} />{/each}</div>
    {/if}
  {/if}
{:else if history === null}
  <SkeletonPosterRow landscape />
{:else if !history.length}
  <p class="empty">No history. Movies, series, anime and drama you start show up here.</p>
{:else}
  <ul class="hist">
    {#each history as { e, item } (e.itemId)}
      {@const still = item ? imgFor(item.backdrop, 'backdrop') : null}
      <li>
        <a href={watchHref(e.itemId, e.episodeId)} class="row glass">
          <span class="thumb">
            {#if still}<img src={still.src} srcset={still.srcset} sizes="160px" alt="" loading="lazy" width="160" height="90" />{/if}
            <i style:width="{pct(e)}%"></i>
          </span>
          <span class="txt"><b>{item?.title ?? e.title ?? e.itemId}</b><small>{e.episodeId ? 'Episode · ' : ''}{left(e)}</small></span>
          <span class="play" aria-hidden="true">▶</span>
        </a>
      </li>
    {/each}
  </ul>
  <div class="clear"><button class="btn btn-ghost" onclick={clearHistory}>Clear history</button></div>
{/if}

<style>
  .head {
    padding: 4px 0 0;
  }
  h1 {
    margin: 4px 16px;
  }
  .empty {
    padding: 40px 16px;
    text-align: center;
    color: var(--text-secondary);
  }
  .grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(112px, 1fr));
    gap: 14px 12px;
    padding: 0 16px;
  }
  .grid :global(.card) {
    width: auto;
  }
  .hist {
    list-style: none;
    margin: 0;
    padding: 0 16px;
    display: grid;
    gap: 10px;
  }
  .row {
    display: flex;
    align-items: center;
    gap: 14px;
    padding: 10px;
    border-radius: 18px;
    text-decoration: none;
  }
  .thumb {
    position: relative;
    flex: none;
    width: 160px;
    aspect-ratio: 16 / 9;
    border-radius: 12px;
    overflow: hidden;
    background: var(--surface);
  }
  .thumb img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
  .thumb i {
    position: absolute;
    left: 0;
    bottom: 0;
    height: 4px;
    background: var(--accent);
  }
  .txt {
    flex: 1;
    display: grid;
    min-width: 0;
  }
  .txt small {
    color: var(--text-secondary);
  }
  .play {
    color: var(--accent);
  }
  .clear {
    margin: 18px 16px;
  }
</style>
