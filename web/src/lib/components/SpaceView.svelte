<script lang="ts">
  import { catalog } from '$lib/data/app-catalog';
  import type { Item, RowMore, Space } from '$lib/data/types';
  import { SkeletonEpgGrid, SkeletonHero, SkeletonPosterRow } from '$lib/design/skeleton';
  import { buildSpaceModel, categoryGenres, filterRows, genreItems, heroItems, pickCategory, type SpaceModel } from '$lib/ui/space-model';
  import Hero from './Hero.svelte';
  import PosterRow from './PosterRow.svelte';
  import Poster from './Poster.svelte';

  interface Props {
    space: Space;
    /** Selected category id (from ?cat=). Defaults to the first category. */
    cat?: string | null;
    /** Render category tabs in-page (video spaces; isolated spaces use their nav tabs). */
    showCategoryChips?: boolean;
  }
  let { space, cat = null, showCategoryChips = true }: Props = $props();

  let model = $state.raw<SpaceModel | null>(null);
  let error = $state<string | null>(null);
  let localCat = $state<string | null>(null);
  let genre = $state<string | null>(null);
  let reload = $state(0);

  $effect(() => {
    const s = space;
    void reload;
    let alive = true;
    model = null;
    error = null;
    genre = null;
    localCat = null;
    catalog.file(s).then(
      (f) => {
        if (alive) model = buildSpaceModel(f);
      },
      (e: unknown) => {
        if (alive) error = e instanceof Error ? e.message : String(e);
      }
    );
    return () => {
      alive = false;
    };
  });

  // Nav tab (?cat=) wins over the in-page selection.
  $effect(() => {
    void cat;
    localCat = null;
    genre = null;
  });

  const category = $derived(model ? pickCategory(model, localCat ?? cat) : undefined);
  const genres = $derived(model ? categoryGenres(model, category) : []);
  const hero = $derived(model ? heroItems(model, category, genre) : []);
  const rows = $derived(model ? filterRows(model, category, genre) : []);
  const extraLocal = $derived(model && genre && rows.length === 0 ? genreItems(model, genre) : []);

  // Gateway spaces: genre chip = server-side `genre=` list (paged grid) instead of client filtering.
  let gGrid = $state.raw<{ key: string; items: Item[]; more: RowMore } | null>(null);
  let gBusy = $state(false);
  $effect(() => {
    const g = genre;
    const c = category?.id;
    gGrid = null;
    if (!g || !c || !catalog.usesGateway(space)) return;
    let alive = true;
    gBusy = true;
    catalog.genrePage(space, c, g).then(
      (r) => {
        if (alive && r) gGrid = { key: `${c}|${g}`, items: r.items, more: r.more };
      },
      () => undefined
    ).finally(() => alive && (gBusy = false));
    return () => {
      alive = false;
    };
  });
  const serverGenre = $derived(!!gGrid && gGrid.items.length > 0);
  const extra = $derived(serverGenre ? gGrid!.items : extraLocal);
  let gMoreBusy = false;
  async function gridMore() {
    const g = gGrid;
    if (!g || gMoreBusy || !g.more.hasMore) return;
    gMoreBusy = true;
    try {
      const r = await catalog.moreRow(space, g.more, category?.id);
      if (r && gGrid?.key === g.key) {
        const seen = new Set(g.items.map((i) => i.id));
        gGrid = { ...g, items: [...g.items, ...r.items.filter((i) => !seen.has(i.id))], more: r.more };
      } else if (!r && gGrid?.key === g.key) gGrid = { ...g, more: { ...g.more, hasMore: false } };
    } catch {
      /* retry on next intersection */
    } finally {
      gMoreBusy = false;
    }
  }
  function gridSentinel(node: HTMLElement) {
    if (typeof IntersectionObserver === 'undefined') return;
    const io = new IntersectionObserver((es) => es.some((e) => e.isIntersecting) && void gridMore(), { rootMargin: '0px 0px 900px 0px' });
    io.observe(node);
    return { destroy: () => io.disconnect() };
  }

  function selectTab(id: string) {
    localCat = id;
    genre = null;
  }
  function tabKey(e: KeyboardEvent, i: number) {
    if (!model) return;
    const tabs = model.tabs;
    let j = -1;
    if (e.key === 'ArrowRight') j = (i + 1) % tabs.length;
    else if (e.key === 'ArrowLeft') j = (i - 1 + tabs.length) % tabs.length;
    if (j < 0) return;
    e.preventDefault();
    selectTab(tabs[j].id);
    (e.currentTarget as HTMLElement).parentElement?.querySelectorAll<HTMLElement>('[role=tab]')[j]?.focus();
  }
</script>

{#if error}
  <div class="state" role="alert">
    <p><b>Couldn't load this space.</b></p>
    <p class="why">{typeof navigator !== 'undefined' && navigator.onLine === false ? "You're offline — titles you opened before stay cached; connect to load more." : error}</p>
    <button class="btn btn-ghost" onclick={() => reload++}>Retry</button>
  </div>
{:else if !model || !category}
  <div class="loading" aria-busy="true" aria-label="Loading">
    <div class="chips-sk"></div>
    <SkeletonHero />
    {#if space === 'TV'}
      <div class="pad"><SkeletonEpgGrid channels={4} /></div>
    {/if}
    <SkeletonPosterRow />
    <SkeletonPosterRow landscape />
    <SkeletonPosterRow />
  </div>
{:else}
  {#if showCategoryChips && model.tabs.length > 1}
    <div class="h-scroll chips tabs" role="tablist" aria-label="Categories">
      {#each model.tabs as t, i (t.id)}
        <button
          class="chip"
          role="tab"
          aria-selected={t.id === category.id}
          tabindex={t.id === category.id ? 0 : -1}
          onclick={() => selectTab(t.id)}
          onkeydown={(e) => tabKey(e, i)}>{t.label}</button
        >
      {/each}
    </div>
  {/if}

  {#key `${category.id}|${genre ?? ''}`}
    <Hero items={hero} />
  {/key}

  {#if genres.length > 1}
    <div class="h-scroll chips genres" role="group" aria-label="Genres">
      <button class="chip" aria-pressed={genre === null} onclick={() => (genre = null)}>All</button>
      {#each genres as g (g)}
        <button class="chip" aria-pressed={genre === g} onclick={() => (genre = genre === g ? null : g)}>{g}</button>
      {/each}
    </div>
  {/if}

  {#if !serverGenre}
    {#each rows as r, i (r.row.id)}
      <PosterRow row={r.row} items={r.items} eager={i === 0} {space} categoryId={category.id} />
    {/each}
  {/if}

  {#if extra.length}
    <h2 class="section-title">{genre}{serverGenre ? '' : ` · ${extra.length}`}</h2>
    <div class="grid">
      {#each extra as item (item.id)}<Poster {item} />{/each}
    </div>
    {#if serverGenre && gGrid?.more.hasMore}<div class="sentinel" use:gridSentinel aria-hidden="true"></div>{/if}
  {:else if genre && gBusy}
    <div class="pad"><SkeletonPosterRow /></div>
  {:else if genre && !rows.length}
    <p class="state">Nothing in {genre} here yet.</p>
  {/if}

  {#if catalog.originOf(space) === 'tmdb'}
    <p class="attr">Metadata from TMDB. This product uses the TMDB API but is not endorsed or certified by TMDB.</p>
  {/if}
{/if}

<style>
  .chips {
    padding-top: 6px;
  }
  .tabs .chip {
    font-weight: 700;
  }
  .tabs .chip[aria-selected='true'] {
    color: var(--on-accent);
    background: linear-gradient(135deg, var(--accent), var(--accent-end));
    box-shadow: 0 6px 18px -8px var(--accent-glow);
  }
  .genres {
    margin-top: 14px;
  }
  .chips-sk {
    height: 50px;
  }
  .pad {
    margin-top: 18px;
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
  .state {
    padding: 48px 16px;
    text-align: center;
    color: var(--text-secondary);
  }
  .why {
    font-size: 0.85rem;
    margin: 6px auto 14px;
    max-width: 420px;
  }
  .sentinel {
    height: 1px;
  }
  .grid :global(.card) {
    content-visibility: auto;
    contain-intrinsic-size: auto 240px;
  }
  .attr {
    margin: 28px 16px 0;
    font-size: 0.72rem;
    color: var(--text-tertiary);
  }
</style>
