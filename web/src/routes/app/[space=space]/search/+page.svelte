<script lang="ts">
  import { page } from '$app/state';
  import { goto } from '$app/navigation';
  import { base } from '$app/paths';
  import SearchView from '$lib/components/SearchView.svelte';
  import Poster from '$lib/components/Poster.svelte';
  import { SkeletonBox } from '$lib/design/skeleton';
  import AdultLock from '$lib/adult/AdultLock.svelte';
  import { adult } from '$lib/adult/store.svelte';
  import { catalog, comicsSource } from '$lib/data/app-catalog';
  import { activeProfileId } from '$lib/data/library.svelte';
  import { history } from '$lib/data/search-client';
  import { historyScope } from '$lib/data/search-history';
  import { COMIC_CATS, type ComicCatId } from '$lib/comics/mangadex';
  import { spaceFromSlug, type Item } from '$lib/data/types';

  const space = $derived(spaceFromSlug(page.params.space ?? '') ?? 'HOME');
  const profileId = activeProfileId();
  const comicsScope = historyScope('COMICS');

  let comicQuery = $state('');
  let comicOrigin = $state<ComicCatId | 'ALL'>('ALL');
  let comicBusy = $state(false);
  let comicPool = $state.raw<Item[]>([]);
  let comicResults = $state.raw<Item[]>([]);
  let comicRecents = $state<string[]>([]);
  let comicInput = $state<HTMLInputElement>();

  $effect(() => {
    if (space === 'ADULT' && !adult.enabled) {
      void goto(`${base}/app`, { replaceState: true });
    }
  });

  $effect(() => {
    if (space !== 'COMICS') return;
    let alive = true;
    comicInput?.focus();
    history.list(profileId, comicsScope).then((r) => alive && (comicRecents = r));
    catalog
      .pool(['COMICS'])
      .then((items) => {
        if (alive) comicPool = items;
      })
      .catch(() => undefined);
    return () => {
      alive = false;
    };
  });

  $effect(() => {
    if (space !== 'COMICS') return;
    const q = comicQuery.trim();
    const cat = comicOrigin;
    if (!q) {
      comicResults = [];
      comicBusy = false;
      return;
    }
    const ctl = new AbortController();
    comicBusy = true;
    const timer = setTimeout(async () => {
      try {
        const remote = await comicsSource
          .search(q, ctl.signal, cat === 'ALL' ? undefined : cat)
          .catch(() => [] as Item[]);
        if (ctl.signal.aborted) return;
        const lower = q.toLowerCase();
        const localHits = comicPool.filter(
          (it) =>
            (cat === 'ALL' || it.categoryId === cat) &&
            (it.title.toLowerCase().includes(lower) || (it.altTitle?.toLowerCase().includes(lower) ?? false))
        );
        const seen = new Set<string>();
        const merged: Item[] = [];
        for (const it of [...remote, ...localHits]) {
          if (seen.has(it.id)) continue;
          seen.add(it.id);
          merged.push(it);
        }
        comicResults = merged;
      } finally {
        if (!ctl.signal.aborted) comicBusy = false;
      }
    }, 240);
    return () => {
      clearTimeout(timer);
      ctl.abort();
    };
  });

  const featuredComics = $derived.by(() => {
    const filtered = comicOrigin === 'ALL' ? comicPool : comicPool.filter((i) => i.categoryId === comicOrigin);
    return [...filtered].sort((a, b) => b.rating - a.rating).slice(0, 18);
  });

  async function rememberComicQuery(text = comicQuery) {
    const clean = text.trim();
    if (!clean) return;
    comicRecents = await history.add(profileId, comicsScope, clean);
  }

  async function removeComicRecent(entry: string) {
    comicRecents = await history.remove(profileId, comicsScope, entry);
  }

  async function clearComicRecents() {
    await history.clear(profileId, comicsScope);
    comicRecents = [];
  }

  function applyComicSuggestion(entry: string) {
    comicQuery = entry;
    void rememberComicQuery(entry);
    comicInput?.focus();
  }
</script>

<svelte:head><title>{space === 'ADULT' && !adult.enabled ? 'Cinetor' : 'Search · Cinetor'}</title></svelte:head>

{#key space}
  {#if space === 'ADULT'}
    {#if adult.enabled}
      <div class="titled-search">
        <AdultLock><SearchView {space} /></AdultLock>
      </div>
    {/if}
  {:else if space === 'COMICS'}
    <div class="comics-search titled-search">
      <form
        class="search-bar glass glass-bar"
        role="search"
        onsubmit={(e) => {
          e.preventDefault();
          void rememberComicQuery();
          comicInput?.blur();
        }}
      >
        <label class="sr-only" for="comics-search-input">Search Manga, Manhwa and Manhua</label>
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true">
          <circle cx="10.5" cy="10.5" r="6.5" />
          <path d="m20 20-4.6-4.6" />
        </svg>
        <input
          id="comics-search-input"
          bind:this={comicInput}
          bind:value={comicQuery}
          type="search"
          enterkeyhint="search"
          autocomplete="off"
          spellcheck="false"
          placeholder="Search Manga, Manhwa, Manhua…"
        />
        {#if comicBusy}
          <span class="spin" aria-label="Searching comics"></span>
        {:else if comicQuery}
          <button type="button" class="clear-x" onclick={() => (comicQuery = '')} aria-label="Clear search">×</button>
        {/if}
      </form>

      <div class="h-scroll origin-chips" role="group" aria-label="Comic type">
        <button type="button" class="chip" class:active={comicOrigin === 'ALL'} aria-pressed={comicOrigin === 'ALL'} onclick={() => (comicOrigin = 'ALL')}>
          All
        </button>
        {#each COMIC_CATS as cat (cat.id)}
          <button
            type="button"
            class="chip"
            class:active={comicOrigin === cat.id}
            aria-pressed={comicOrigin === cat.id}
            onclick={() => (comicOrigin = cat.id)}
          >
            {cat.title}
          </button>
        {/each}
      </div>

      {#if !comicQuery.trim()}
        {#if comicRecents.length}
          <section class="recent" aria-label="Recent comic searches">
            <div class="recent-head">
              <h2 class="section-title">Recent searches</h2>
              <button type="button" class="clear-link" onclick={clearComicRecents}>Clear all</button>
            </div>
            <ul>
              {#each comicRecents.slice(0, 8) as entry (entry)}
                <li>
                  <button type="button" class="rq" onclick={() => applyComicSuggestion(entry)}>{entry}</button>
                  <button type="button" class="rx" onclick={() => removeComicRecent(entry)} aria-label="Remove {entry}">×</button>
                </li>
              {/each}
            </ul>
          </section>
        {/if}

        <p class="hint">Searches Manga, Manhwa, and Manhua exclusively — isolated from global video search.</p>

        {#if featuredComics.length}
          <h2 class="section-title">Popular to Read</h2>
          <div class="grid">
            {#each featuredComics as item (item.id)}
              <Poster {item} />
            {/each}
          </div>
        {:else}
          <div class="grid" aria-busy="true">
            {#each Array(6) as _, i (i)}
              <SkeletonBox aspect="2 / 3" radius="var(--radius-card)" />
            {/each}
          </div>
        {/if}
      {:else if comicBusy && !comicResults.length}
        <div class="grid" aria-busy="true" aria-label="Searching">
          {#each Array(9) as _, i (i)}
            <SkeletonBox aspect="2 / 3" radius="var(--radius-card)" />
          {/each}
        </div>
      {:else if !comicResults.length}
        <p class="hint">No comics found for “{comicQuery.trim()}”.</p>
      {:else}
        <h2 class="section-title">Comics · {comicResults.length}</h2>
        <div class="grid" onclickcapture={() => rememberComicQuery()} role="presentation">
          {#each comicResults as item (item.id)}
            <Poster {item} />
          {/each}
        </div>
      {/if}
    </div>
  {:else}
    <div class="titled-search">
      <SearchView {space} />
    </div>
  {/if}
{/key}

<style>
  .titled-search :global(.card .title) {
    display: -webkit-box;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    -webkit-box-orient: vertical;
    white-space: normal;
    overflow: hidden;
    color: var(--text-primary);
    font-size: 0.84rem;
    font-weight: 680;
    line-height: 1.28;
    opacity: 1;
    visibility: visible;
  }
  .comics-search {
    display: grid;
    gap: 6px;
  }
  .search-bar {
    position: sticky;
    top: calc(var(--header-h, 56px) + 10px + var(--safe-t));
    z-index: 5;
    display: flex;
    align-items: center;
    gap: 10px;
    margin: 0 16px 4px;
    padding: 0 16px;
    height: 52px;
    border-radius: 18px;
    color: var(--text-secondary);
  }
  .search-bar:focus-within {
    box-shadow:
      0 0 0 2px var(--accent),
      var(--glass-shadow);
  }
  .search-bar input {
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
  .clear-x {
    width: 32px;
    height: 32px;
    border: 0;
    border-radius: 50%;
    background: none;
    color: var(--text-secondary);
    font-size: 1.25rem;
    cursor: pointer;
  }
  .spin {
    width: 16px;
    height: 16px;
    border-radius: 50%;
    border: 2px solid var(--glass-stroke);
    border-top-color: var(--accent);
    animation: cs-spin 0.8s linear infinite;
  }
  @keyframes cs-spin {
    to {
      transform: rotate(360deg);
    }
  }
  .origin-chips {
    padding-top: 2px;
    padding-bottom: 6px;
  }
  .hint {
    margin: 0;
    padding: 8px 16px;
    color: var(--text-secondary);
    font-size: 0.85rem;
  }
  .recent-head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    padding-right: 16px;
  }
  .clear-link {
    border: 0;
    background: none;
    color: var(--text-secondary);
    font-weight: 650;
    cursor: pointer;
  }
  .recent ul {
    list-style: none;
    margin: 0;
    padding: 0 16px;
  }
  .recent li {
    display: flex;
    align-items: center;
    border-bottom: 1px solid var(--divider);
  }
  .rq {
    flex: 1;
    min-height: 42px;
    border: 0;
    background: none;
    color: var(--text-primary);
    text-align: left;
    cursor: pointer;
  }
  .rx {
    width: 38px;
    height: 38px;
    border: 0;
    background: none;
    color: var(--text-secondary);
    font-size: 1.25rem;
    cursor: pointer;
  }
  .grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(112px, 1fr));
    gap: 16px 12px;
    padding: 4px 16px 18px;
  }
  .grid :global(.card) {
    width: auto;
  }
</style>
