<script lang="ts">
  import { catalog } from '$lib/data/app-catalog';
  import { activeProfileId } from '$lib/data/library.svelte';
  import { isVideoSpace, type Item, type Row, type RowMore, type Space } from '$lib/data/types';
  import { SkeletonEpgGrid, SkeletonHero, SkeletonPosterRow } from '$lib/design/skeleton';
  import { resume } from '$lib/player/controller';
  import {
    buildSpaceModel,
    categoryGenres,
    dedupeItems,
    filterRows,
    genreItems,
    heroItems,
    mergeUniqueItems,
    pickCategory,
    safeSpaceError,
    type SpaceModel
  } from '$lib/ui/space-model';
  import Hero from './Hero.svelte';
  import PosterRow, { type SeeAllPayload } from './PosterRow.svelte';
  import Poster from './Poster.svelte';

  interface Props {
    space: Space;
    /** Selected category id (from ?cat=). Defaults to the first category. */
    cat?: string | null;
    /** Render category tabs in-page (video spaces; isolated spaces use their nav tabs). */
    showCategoryChips?: boolean;
  }
  let { space, cat = null, showCategoryChips = true }: Props = $props();

  interface SeeAllView {
    row: Row;
    items: Item[];
    more?: RowMore;
    loading: boolean;
    failed: boolean;
  }

  let model = $state.raw<SpaceModel | null>(null);
  let loadError = $state<string | null>(null);
  let localCat = $state<string | null>(null);
  let genre = $state<string | null>(null);
  let reload = $state(0);

  let progressMap = $state.raw<ReadonlyMap<string, number>>(new Map());
  let resumeIds = $state.raw<string[]>([]);

  let seeAll = $state.raw<SeeAllView | null>(null);
  const seeAllOpen = $derived(Boolean(seeAll));
  let dialogEl = $state<HTMLDialogElement>();
  let openerEl: HTMLElement | null = null;

  $effect(() => {
    const s = space;
    void reload;
    let alive = true;
    model = null;
    loadError = null;
    genre = null;
    localCat = null;
    seeAll = null;
    catalog.file(s).then(
      (f) => {
        if (alive) model = buildSpaceModel(f);
      },
      (e: unknown) => {
        if (alive) loadError = safeSpaceError(e instanceof Error ? e.message : String(e));
      }
    );
    return () => {
      alive = false;
    };
  });

  // Load profile watch progress once per space mount so posters show 2%..95% bars without N+1 IDB reads.
  $effect(() => {
    const s = space;
    void reload;
    if (!isVideoSpace(s)) {
      progressMap = new Map();
      resumeIds = [];
      return;
    }
    let alive = true;
    resume
      .continueWatching(activeProfileId(), 50)
      .then((entries) => {
        if (!alive) return;
        const map = new Map<string, number>();
        const ids: string[] = [];
        for (const e of entries) {
          if (e.dur > 0) map.set(e.itemId, e.pos / e.dur);
          if (e.space === s) ids.push(e.itemId);
        }
        progressMap = map;
        resumeIds = ids;
      })
      .catch(() => undefined);
    return () => {
      alive = false;
    };
  });

  // Nav tab (?cat=) wins over the in-page selection.
  $effect(() => {
    void cat;
    localCat = null;
    genre = null;
    seeAll = null;
  });

  const category = $derived(model ? pickCategory(model, localCat ?? cat) : undefined);
  const genres = $derived(model ? categoryGenres(model, category) : []);
  const hero = $derived(model ? heroItems(model, category, genre) : []);
  const rows = $derived(model ? filterRows(model, category, genre) : []);
  const extraLocal = $derived(model && genre && rows.length === 0 ? genreItems(model, genre) : []);

  const continueRow = $derived.by(() => {
    if (!model || genre || !resumeIds.length) return null;
    if (rows.some((r) => r.row.style === 'CONTINUE')) return null;
    const resolved = dedupeItems(
      resumeIds.map((id) => model!.byId.get(id) ?? catalog.peek(id)).filter((i): i is Item => Boolean(i))
    );
    if (!resolved.length) return null;
    const rowDef: Row = {
      id: `continue-${space.toLowerCase()}`,
      title: 'Continue Watching',
      style: 'CONTINUE',
      itemIds: resolved.map((i) => i.id)
    };
    return { row: rowDef, items: resolved };
  });

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
    catalog
      .genrePage(space, c, g)
      .then(
        (r) => {
          if (alive && r) gGrid = { key: `${c}|${g}`, items: dedupeItems(r.items), more: r.more };
        },
        () => undefined
      )
      .finally(() => alive && (gBusy = false));
    return () => {
      alive = false;
    };
  });

  const serverGenre = $derived(Boolean(gGrid && gGrid.items.length > 0));
  const extra = $derived(serverGenre ? gGrid!.items : extraLocal);
  let gMoreBusy = false;

  async function gridMore() {
    const g = gGrid;
    if (!g || gMoreBusy || !g.more.hasMore) return;
    gMoreBusy = true;
    try {
      const r = await catalog.moreRow(space, g.more, category?.id);
      if (r && gGrid?.key === g.key) {
        gGrid = { ...g, items: mergeUniqueItems(g.items, r.items), more: r.more };
      } else if (!r && gGrid?.key === g.key) {
        gGrid = { ...g, more: { ...g.more, hasMore: false } };
      }
    } catch {
      /* retry on next intersection */
    } finally {
      gMoreBusy = false;
    }
  }

  function gridSentinel(node: HTMLElement) {
    if (typeof IntersectionObserver === 'undefined') return;
    const io = new IntersectionObserver(
      (es) => es.some((e) => e.isIntersecting) && void gridMore(),
      { rootMargin: '0px 0px 900px 0px' }
    );
    io.observe(node);
    return { destroy: () => io.disconnect() };
  }

  // ---- See all Drawer / Modal with Infinite Scroll ----
  function openSeeAll(payload: SeeAllPayload) {
    openerEl = typeof document !== 'undefined' && document.activeElement instanceof HTMLElement ? document.activeElement : null;
    seeAll = {
      row: payload.row,
      items: dedupeItems(payload.items),
      more: payload.more,
      loading: false,
      failed: false
    };
    if (payload.more?.hasMore && payload.items.length < 18) {
      void loadMoreSeeAll();
    }
  }

  function closeSeeAll() {
    if (dialogEl?.open) dialogEl.close();
    seeAll = null;
    const target = openerEl;
    openerEl = null;
    if (target?.isConnected) {
      setTimeout(() => target.focus({ preventScroll: true }), 0);
    }
  }

  function handleDialogClose() {
    seeAll = null;
    const target = openerEl;
    openerEl = null;
    if (target?.isConnected) {
      setTimeout(() => target.focus({ preventScroll: true }), 0);
    }
  }

  $effect(() => {
    const dlg = dialogEl;
    if (seeAllOpen && dlg && !dlg.open) {
      try {
        dlg.showModal();
      } catch {
        /* ignore if already open */
      }
    } else if (!seeAllOpen && dlg?.open) {
      dlg.close();
    }
  });

  async function loadMoreSeeAll() {
    const cur = seeAll;
    if (!cur || cur.loading || !cur.more?.hasMore) return;
    const rowId = cur.row.id;
    const cursor = cur.more;
    seeAll = { ...cur, loading: true, failed: false };
    try {
      const r = await catalog.moreRow(space, cursor, category?.id);
      if (!seeAll || seeAll.row.id !== rowId) return;
      if (!r || !r.items.length) {
        seeAll = { ...seeAll, more: { ...cursor, hasMore: false }, loading: false };
      } else {
        seeAll = {
          ...seeAll,
          items: mergeUniqueItems(seeAll.items, r.items),
          more: r.more,
          loading: false
        };
      }
    } catch {
      if (seeAll && seeAll.row.id === rowId) {
        seeAll = { ...seeAll, loading: false, failed: true };
      }
    }
  }

  function handleDrawerScroll(e: Event) {
    if (!seeAll?.more?.hasMore || seeAll.loading) return;
    const node = e.currentTarget as HTMLElement;
    if (node.scrollTop + node.clientHeight >= node.scrollHeight - 640) {
      void loadMoreSeeAll();
    }
  }

  function drawerSentinel(node: HTMLElement) {
    if (typeof IntersectionObserver === 'undefined') return;
    const root = node.closest('.drawer-body');
    const io = new IntersectionObserver(
      (es) => es.some((e) => e.isIntersecting) && void loadMoreSeeAll(),
      { root, rootMargin: '0px 0px 800px 0px' }
    );
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

{#if loadError}
  <div class="status-box" role="alert">
    <p><b>Couldn't load this space.</b></p>
    <p class="why">
      {typeof navigator !== 'undefined' && navigator.onLine === false
        ? "You're offline — titles you opened before stay cached; connect to load more."
        : loadError}
    </p>
    <button type="button" class="btn btn-ghost" onclick={() => reload++}>Retry</button>
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
    <div class="h-scroll filter-bar tabs" role="tablist" aria-label="Categories">
      {#each model.tabs as t, i (t.id)}
        <button
          type="button"
          class="chip movira-pill"
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
    <Hero items={hero} paused={Boolean(seeAll)} />
  {/key}

  {#if genres.length > 1}
    <div class="h-scroll filter-bar genres" role="group" aria-label="Genres">
      <button
        type="button"
        class="chip movira-pill"
        aria-pressed={genre === null}
        onclick={() => (genre = null)}>All</button
      >
      {#each genres as g (g)}
        <button
          type="button"
          class="chip movira-pill"
          aria-pressed={genre === g}
          onclick={() => (genre = genre === g ? null : g)}>{g}</button
        >
      {/each}
    </div>
  {/if}

  {#if !serverGenre}
    {#if continueRow}
      <PosterRow
        row={continueRow.row}
        items={continueRow.items}
        eager
        {space}
        categoryId={category.id}
        {progressMap}
      />
    {/if}

    {#each rows as r, i (r.row.id)}
      <PosterRow
        row={r.row}
        items={r.items}
        eager={i === 0 && !continueRow}
        {space}
        categoryId={category.id}
        {progressMap}
        onSeeAll={openSeeAll}
      />
    {/each}
  {/if}

  {#if extra.length}
    <div class="grid-head">
      <span class="accent-bar" aria-hidden="true"></span>
      <h2 class="section-title grid-title">{genre}{serverGenre ? '' : ` · ${extra.length}`}</h2>
    </div>
    <div class="grid">
      {#each extra as item (item.id)}
        <Poster {item} progress={progressMap.get(item.id)} />
      {/each}
    </div>
    {#if serverGenre && gGrid?.more.hasMore}
      <div class="sentinel" use:gridSentinel aria-hidden="true"></div>
    {/if}
  {:else if genre && gBusy}
    <div class="pad"><SkeletonPosterRow /></div>
  {:else if genre && !rows.length}
    <p class="status-box">Nothing in {genre} here yet.</p>
  {/if}

  {#if catalog.originOf(space) === 'tmdb'}
    <p class="attr">Metadata from TMDB. This product uses the TMDB API but is not endorsed or certified by TMDB.</p>
  {/if}
{/if}

<!-- See all Drawer / Modal Grid View with Infinite Scroll -->
<dialog
  bind:this={dialogEl}
  class="see-all-dialog glass glass-sheet"
  aria-labelledby="see-all-heading"
  onclose={handleDialogClose}
  onclick={(e) => {
    if (e.target === dialogEl) closeSeeAll();
  }}
>
  {#if seeAll}
    <div class="drawer-shell">
      <header class="drawer-head">
        <div class="drawer-title-group">
          <span class="accent-bar" aria-hidden="true"></span>
          <h2 id="see-all-heading">{seeAll.row.title}</h2>
          <span class="count-pill">{seeAll.items.length}{seeAll.more?.hasMore ? '+' : ''}</span>
        </div>
        <button
          type="button"
          class="drawer-close"
          aria-label="Close {seeAll.row.title}"
          onclick={closeSeeAll}
        >
          <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true">
            <path
              fill="none"
              stroke="currentColor"
              stroke-width="2.2"
              stroke-linecap="round"
              d="M6 6l12 12M18 6L6 18"
            />
          </svg>
        </button>
      </header>

      <div class="drawer-body" onscroll={handleDrawerScroll}>
        <div class="grid drawer-grid" class:landscape-grid={seeAll.row.style === 'LANDSCAPE'}>
          {#each seeAll.items as item (item.id)}
            <Poster
              {item}
              variant={seeAll.row.style === 'LANDSCAPE' ? 'landscape' : 'poster'}
              progress={progressMap.get(item.id)}
            />
          {/each}
        </div>

        {#if seeAll.more?.hasMore}
          <div class="drawer-more">
            <div class="sentinel" use:drawerSentinel aria-hidden="true"></div>
            <button
              type="button"
              class="btn btn-ghost load-more-btn"
              disabled={seeAll.loading}
              onclick={() => void loadMoreSeeAll()}
            >
              {#if seeAll.loading}
                Loading more…
              {:else if seeAll.failed}
                Couldn't load more · Tap to retry
              {:else}
                Load more
              {/if}
            </button>
          </div>
        {/if}
      </div>
    </div>
  {/if}
</dialog>

<style>
  /* Movira-style Pill Category & Genre Filter Bar:
     translucent glass outline when idle, solid Cinema Red accent when active. */
  .filter-bar {
    padding-top: 6px;
    padding-bottom: 10px;
    gap: 8px;
  }
  .genres {
    margin-top: 14px;
  }

  .movira-pill {
    min-height: 36px;
    padding: 0 16px;
    border-radius: var(--radius-pill);
    border: 0;
    background: color-mix(in srgb, var(--text-primary) 6%, var(--glass-fill));
    box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--text-primary) 24%, var(--glass-stroke));
    color: var(--text-primary);
    font-size: 0.86rem;
    font-weight: 560;
    letter-spacing: 0.005em;
    transition:
      background calc(var(--dur-short) * var(--dur-scale)) var(--ease-standard),
      color calc(var(--dur-short) * var(--dur-scale)) var(--ease-standard),
      box-shadow calc(var(--dur-short) * var(--dur-scale)) var(--ease-standard),
      transform calc(var(--dur-short) * var(--dur-scale)) var(--ease-emphasized);
  }
  .movira-pill:hover {
    background: color-mix(in srgb, var(--text-primary) 11%, var(--glass-fill));
    box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--text-primary) 34%, var(--glass-stroke));
  }
  .movira-pill:active {
    transform: scale(0.96);
  }
  .movira-pill[aria-selected='true'],
  .movira-pill[aria-pressed='true'] {
    background: var(--accent);
    color: var(--on-accent);
    font-weight: 700;
    box-shadow: 0 6px 20px -6px var(--accent-glow);
  }
  .movira-pill[aria-selected='true']:hover,
  .movira-pill[aria-pressed='true']:hover {
    background: color-mix(in srgb, var(--accent) 90%, #ffffff);
  }

  .chips-sk {
    height: 48px;
  }
  .pad {
    margin-top: 18px;
  }

  .grid-head {
    display: flex;
    align-items: center;
    gap: 9px;
    margin: 20px 16px 12px;
  }
  .accent-bar {
    flex: none;
    width: 3px;
    height: 16px;
    border-radius: 2px;
    background: var(--accent);
    box-shadow: 0 0 10px color-mix(in srgb, var(--accent) 50%, transparent);
  }
  .grid-title {
    margin: 0;
    font-size: 1.05rem;
    font-weight: 750;
    color: var(--text-primary);
  }

  .grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(clamp(112px, 26vw, 160px), 1fr));
    gap: 16px 12px;
    padding: 0 16px;
  }
  .grid :global(.card) {
    width: auto;
    content-visibility: auto;
    contain-intrinsic-size: auto 240px;
  }

  .status-box {
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
  .attr {
    margin: 28px 16px 0;
    font-size: 0.72rem;
    color: var(--text-tertiary);
  }

  /* ==========================================================================
     See All Drawer / Modal Sheet
     ========================================================================== */
  .see-all-dialog {
    padding: 0;
    border: 0;
    color: var(--text-primary);
    width: 100vw;
    max-width: 100vw;
    height: 92dvh;
    max-height: 92dvh;
    margin: 8dvh 0 0;
    border-radius: 24px 24px 0 0;
    overflow: hidden;
    background: color-mix(in srgb, var(--surface) 88%, transparent);
  }
  @media (min-width: 768px) {
    .see-all-dialog {
      width: min(1080px, 92vw);
      max-width: 1080px;
      height: min(86dvh, 860px);
      max-height: 860px;
      margin: auto;
      border-radius: var(--radius-xl);
    }
  }
  .see-all-dialog::backdrop {
    background: rgb(4 4 8 / 0.72);
    -webkit-backdrop-filter: blur(8px);
    backdrop-filter: blur(8px);
  }
  :global([data-blur='none']) .see-all-dialog::backdrop {
    -webkit-backdrop-filter: none;
    backdrop-filter: none;
    background: rgb(4 4 8 / 0.86);
  }

  .drawer-shell {
    display: flex;
    flex-direction: column;
    width: 100%;
    height: 100%;
  }
  .drawer-head {
    flex: none;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 16px 18px 14px;
    border-bottom: 1px solid var(--divider);
  }
  .drawer-title-group {
    display: flex;
    align-items: center;
    gap: 10px;
    min-width: 0;
  }
  .drawer-title-group h2 {
    margin: 0;
    font-size: 1.12rem;
    font-weight: 800;
    letter-spacing: -0.015em;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .count-pill {
    flex: none;
    padding: 2px 8px;
    border-radius: var(--radius-pill);
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
    color: var(--text-secondary);
    font-size: 0.72rem;
    font-weight: 700;
    font-variant-numeric: tabular-nums;
  }
  .drawer-close {
    flex: none;
    width: 40px;
    height: 40px;
    padding: 0;
    border: 0;
    border-radius: 50%;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
    color: var(--text-primary);
    cursor: pointer;
    transition:
      background calc(var(--dur-short) * var(--dur-scale)) var(--ease-standard),
      transform calc(var(--dur-short) * var(--dur-scale)) var(--ease-emphasized);
  }
  .drawer-close:hover {
    background: color-mix(in srgb, var(--text-primary) 12%, var(--glass-fill));
  }
  .drawer-close:active {
    transform: scale(0.94);
  }

  .drawer-body {
    flex: 1;
    overflow-y: auto;
    overscroll-behavior: contain;
    padding: 18px 0 calc(28px + var(--safe-b, 0px));
    scrollbar-width: thin;
    scrollbar-color: var(--glass-stroke) transparent;
  }
  .drawer-grid {
    padding: 0 18px;
  }
  .drawer-grid.landscape-grid {
    grid-template-columns: repeat(auto-fill, minmax(clamp(200px, 44vw, 280px), 1fr));
  }
  .drawer-more {
    display: flex;
    flex-direction: column;
    align-items: center;
    padding: 22px 18px 8px;
  }
  .load-more-btn {
    min-width: 160px;
  }
</style>
