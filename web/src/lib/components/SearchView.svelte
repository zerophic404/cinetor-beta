<script lang="ts">
  import { repo } from '$lib/data/repo';
  import { search, searchInSpace } from '$lib/data/search';
  import type { Item, Space } from '$lib/data/types';
  import { SkeletonBox } from '$lib/design/skeleton';
  import Poster from './Poster.svelte';

  /** No space → global search (Home/Anime/Drama only). With space → in-space search. */
  let { space }: { space?: Space } = $props();

  const LABEL: Record<Space, string> = { HOME: 'Movies & Series', ANIME: 'Anime', DRAMA: 'Drama', TV: 'Live TV & Sports', MUSIC: 'Music', COMICS: 'Comics', ADULT: 'Adult' };

  let q = $state('');
  let loading = $state(false);
  let groups = $state<{ space: Space; items: Item[] }[]>([]);
  let input = $state<HTMLInputElement>();

  $effect(() => {
    input?.focus();
  });

  $effect(() => {
    const query = q.trim();
    const s = space;
    let alive = true;
    if (!query) {
      groups = [];
      loading = false;
      return;
    }
    loading = true;
    const run = s
      ? searchInSpace(repo, s, query).then((items) => (items.length ? [{ space: s, items }] : []))
      : search(repo, query).then((r) => r.groups);
    run.then(
      (g) => {
        if (alive) {
          groups = g;
          loading = false;
        }
      },
      () => alive && (loading = false)
    );
    return () => {
      alive = false;
    };
  });
</script>

<div class="search">
  <label class="field glass glass-bar">
    <span class="sr-only">Search {space ? LABEL[space] : 'movies, series, anime, drama'}</span>
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><circle cx="10.5" cy="10.5" r="6.5" /><path d="M20 20l-4.6-4.6" /></svg>
    <input
      bind:this={input}
      bind:value={q}
      type="search"
      enterkeyhint="search"
      autocomplete="off"
      spellcheck="false"
      placeholder={space ? `Search ${LABEL[space]}` : 'Movies, series, anime, drama…'}
    />
  </label>

  {#if loading && !groups.length}
    <div class="grid" aria-busy="true">
      {#each Array(9) as _, i (i)}<SkeletonBox aspect="2 / 3" radius="var(--radius-card)" />{/each}
    </div>
  {:else if q.trim() && !groups.length}
    <p class="empty">No results for “{q}”.</p>
  {:else if !q.trim()}
    <p class="empty">{space ? `Searches only inside ${LABEL[space]}.` : 'Global search covers Home, Anime and Drama.'}</p>
  {/if}

  {#each groups as g (g.space)}
    <section aria-label={LABEL[g.space]}>
      <h2 class="section-title">{LABEL[g.space]} · {g.items.length}</h2>
      <div class="grid">
        {#each g.items as item (item.id)}<Poster {item} />{/each}
      </div>
    </section>
  {/each}
</div>

<style>
  .field {
    position: sticky;
    top: calc(10px + var(--safe-t));
    z-index: 5;
    display: flex;
    align-items: center;
    gap: 10px;
    margin: 0 16px 8px;
    padding: 0 16px;
    height: 52px;
    border-radius: 18px;
    color: var(--text-secondary);
  }
  .field:focus-within {
    box-shadow: 0 0 0 2px var(--accent), var(--glass-shadow);
  }
  input {
    flex: 1;
    min-width: 0;
    height: 100%;
    border: 0;
    background: none;
    color: var(--text-primary);
    font: inherit;
    font-size: 1rem;
    outline: none;
  }
  .grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(108px, 1fr));
    gap: 14px 12px;
    padding: 4px 16px;
  }
  .grid :global(.card) {
    width: auto;
  }
  .empty {
    padding: 24px 16px;
    color: var(--text-secondary);
  }
</style>
