<script lang="ts">
  import { activeProfileId } from '$lib/data/library.svelte';
  import { engineFor, history } from '$lib/data/search-client';
  import type { SearchUpdate } from '$lib/data/search-engine';
  import { historyScope } from '$lib/data/search-history';
  import { normalize } from '$lib/data/search';
  import { isVideoSpace, type Item, type Space } from '$lib/data/types';
  import { SkeletonBox } from '$lib/design/skeleton';
  import Poster from './Poster.svelte';

  /** No space → global search (Home/Anime/Drama only). With space → in-space search. */
  let { space }: { space?: Space } = $props();

  const LABEL: Record<Space, string> = {
    HOME: 'Movies & Series',
    ANIME: 'Anime',
    DRAMA: 'Drama',
    TV: 'Live TV & Sports',
    MUSIC: 'Music',
    COMICS: 'Comics',
    ADULT: 'Adult'
  };
  type TypeFilter = 'ALL' | 'MOVIE' | 'SERIES';
  const FILTERS: [TypeFilter, string][] = [
    ['ALL', 'All'],
    ['MOVIE', 'Movies'],
    ['SERIES', 'Series']
  ];

  const profile = activeProfileId();
  // svelte-ignore state_referenced_locally
  const scopeKey = historyScope(space);
  // svelte-ignore state_referenced_locally
  const { engine, ready } = engineFor(space);

  let q = $state('');
  let update = $state.raw<SearchUpdate | null>(null);
  let indexReady = $state(false);
  let recent = $state<string[]>([]);
  let trending = $state.raw<Item[]>([]);
  let typeFilter = $state<TypeFilter>('ALL');
  let input = $state<HTMLInputElement>();

  $effect(() => {
    input?.focus();
  });

  ready.then(() => {
    indexReady = true;
    trending = pickTrending();
  });
  history.list(profile, scopeKey).then((h) => (recent = h));

  function pickTrending(): Item[] {
    // Highest-rated items of the scope (the engine only ever holds in-scope items).
    return engine
      .all()
      .sort((a, b) => b.rating - a.rating)
      .slice(0, 12);
  }

  // Every keystroke: instant local results; remote merges in after the engine's debounce.
  $effect(() => {
    const query = q;
    void indexReady;
    if (!query.trim()) {
      engine.cancel();
      update = null;
      return;
    }
    void engine.search(query, (u) => (update = u));
  });

  $effect(() => () => engine.cancel());

  const results = $derived.by(() => {
    const r = update?.results ?? [];
    return typeFilter === 'ALL' ? r : r.filter((i) => i.type === typeFilter);
  });
  const groups = $derived.by(() => {
    if (space) return results.length ? [{ space, items: results }] : [];
    const order: Space[] = ['HOME', 'ANIME', 'DRAMA'];
    return order.map((s) => ({ space: s, items: results.filter((i) => i.space === s) })).filter((g) => g.items.length);
  });
  const suggestions = $derived.by(() => {
    const nq = normalize(q);
    if (!nq) return [] as string[];
    const out: string[] = [];
    for (const h of recent) if (normalize(h).startsWith(nq) && normalize(h) !== nq) out.push(h);
    for (const i of update?.local ?? []) {
      if (out.length >= 6) break;
      if (normalize(i.title).startsWith(nq) && !out.some((x) => x.toLowerCase() === i.title.toLowerCase())) out.push(i.title);
    }
    return out.slice(0, 6);
  });
  const busy = $derived(!!q.trim() && (!update || !update.localReady || (!update.results.length && update.remotePending) || !indexReady));

  async function remember(query = q) {
    // 18+ searches are never remembered (no recents for the adult space).
    if (!query.trim() || space === 'ADULT') return;
    recent = await history.add(profile, scopeKey, query);
  }
  async function forget(h: string) {
    recent = await history.remove(profile, scopeKey, h);
  }
  async function clearAll() {
    await history.clear(profile, scopeKey);
    recent = [];
  }
  function use(text: string) {
    q = text;
    void remember(text);
    input?.focus();
  }
</script>

<div class="search">
  <form
    class="field glass glass-bar"
    role="search"
    onsubmit={(e) => {
      e.preventDefault();
      void remember();
      input?.blur();
    }}
  >
    <label class="sr-only" for="search-q">Search {space ? LABEL[space] : 'movies, series, anime, drama'}</label>
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><circle cx="10.5" cy="10.5" r="6.5" /><path d="M20 20l-4.6-4.6" /></svg>
    <input
      id="search-q"
      bind:this={input}
      bind:value={q}
      type="search"
      enterkeyhint="search"
      autocomplete="off"
      spellcheck="false"
      placeholder={space ? `Search ${LABEL[space]}` : 'Movies, series, anime, drama…'}
    />
    {#if update?.remotePending}<span class="spin" aria-label="Searching online"></span>{/if}
  </form>

  {#if !q.trim()}
    {#if recent.length}
      <section aria-label="Recent searches" class="recent">
        <div class="head">
          <h2 class="section-title">Recent searches</h2>
          <button class="link" onclick={clearAll}>Clear all</button>
        </div>
        <ul>
          {#each recent.slice(0, 10) as h (h)}
            <li>
              <button class="rq" onclick={() => use(h)}>
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><circle cx="12" cy="12" r="8" /><path d="M12 8v4l3 2" /></svg>
                {h}
              </button>
              <button class="x" aria-label="Remove {h}" onclick={() => forget(h)}>×</button>
            </li>
          {/each}
        </ul>
      </section>
    {/if}
    <p class="hint">
      {space ? `Searches only inside ${LABEL[space]}.` : 'Global search covers Home, Anime and Drama.'}
      History stays on this device{space && !isVideoSpace(space) ? ', separate from global search' : ''}.
    </p>
    {#if trending.length}
      <h2 class="section-title">Top picks</h2>
      <div class="grid">
        {#each trending as item (item.id)}<Poster {item} />{/each}
      </div>
    {:else if !indexReady}
      <div class="grid" aria-busy="true">
        {#each Array(6) as _, i (i)}<SkeletonBox aspect="2 / 3" radius="var(--radius-card)" />{/each}
      </div>
    {/if}
  {:else}
    {#if suggestions.length}
      <ul class="sugg" aria-label="Suggestions">
        {#each suggestions as s (s)}
          <li><button onclick={() => use(s)}>{s}</button></li>
        {/each}
      </ul>
    {/if}

    {#if !space || isVideoSpace(space)}
      <div class="h-scroll filters" role="group" aria-label="Type">
        {#each FILTERS as [v, label] (v)}
          <button class="chip" aria-pressed={typeFilter === v} onclick={() => (typeFilter = v)}>{label}</button>
        {/each}
      </div>
    {/if}

    {#if busy && !results.length}
      <div class="grid" aria-busy="true" aria-label="Searching">
        {#each Array(9) as _, i (i)}<SkeletonBox aspect="2 / 3" radius="var(--radius-card)" />{/each}
      </div>
    {:else if !results.length}
      <p class="hint">No results for “{q}”.</p>
    {/if}

    {#each groups as g (g.space)}
      <section aria-label={LABEL[g.space]}>
        <h2 class="section-title">{LABEL[g.space]} · {g.items.length}</h2>
        <div class="grid" onclickcapture={() => remember()} role="presentation">
          {#each g.items as item (item.id)}<Poster {item} />{/each}
        </div>
      </section>
    {/each}
  {/if}
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
  .spin {
    width: 16px;
    height: 16px;
    border-radius: 50%;
    border: 2px solid var(--glass-stroke);
    border-top-color: var(--accent);
    animation: spin 0.8s linear infinite;
  }
  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
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
  .hint {
    padding: 8px 16px;
    margin: 0;
    color: var(--text-secondary);
    font-size: 0.85rem;
  }
  .head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    padding-right: 16px;
  }
  .link {
    border: 0;
    background: none;
    color: var(--text-secondary);
    font-weight: 600;
    cursor: pointer;
  }
  .recent ul,
  .sugg {
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
    display: flex;
    align-items: center;
    gap: 10px;
    min-height: 44px;
    border: 0;
    background: none;
    text-align: left;
    cursor: pointer;
  }
  .x {
    width: 40px;
    height: 40px;
    border: 0;
    background: none;
    color: var(--text-secondary);
    font-size: 1.3rem;
    cursor: pointer;
  }
  .sugg {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    margin-bottom: 4px;
  }
  .sugg button {
    min-height: 32px;
    padding: 0 12px;
    border: 0;
    border-radius: var(--radius-pill);
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
    cursor: pointer;
    font-size: 0.85rem;
  }
  .filters {
    padding-top: 6px;
  }
</style>
