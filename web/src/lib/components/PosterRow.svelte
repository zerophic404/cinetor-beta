<script lang="ts" module>
  import type { Item as RowItem, Row as RowDef, RowMore } from '$lib/data/types';

  export interface SeeAllPayload {
    row: RowDef;
    items: RowItem[];
    more?: RowMore;
  }
</script>

<script lang="ts">
  import { catalog } from '$lib/data/app-catalog';
  import type { Item, Row, RowStyle, Space } from '$lib/data/types';
  import { hashStr } from '$lib/data/mock-sources';
  import { dedupeItems, mergeUniqueItems } from '$lib/ui/space-model';
  import Poster, { type CardVariant } from './Poster.svelte';

  interface Props {
    row?: Row;
    /** Shorthand row heading when `row` object is omitted (e.g. `<PosterRow title="For You" items={related} />`). */
    title?: string;
    style?: RowStyle;
    items: Item[];
    eager?: boolean;
    /** Owning space / category: paged gateway rows fetch more items when scrolled near the end. */
    space?: Space;
    categoryId?: string;
    /** Watch progress ratios (0..1) per item ID. */
    progressMap?: ReadonlyMap<string, number>;
    /** Opens the full-category `See all` drawer/modal grid view. */
    onSeeAll?: (payload: SeeAllPayload) => void;
  }
  let { row, title, style: styleProp, items, eager = false, space, categoryId, progressMap, onSeeAll }: Props = $props();

  const resolvedRow = $derived<Row>(
    row ?? {
      id: (title ?? 'row').toLowerCase().replace(/[^a-z0-9]+/g, '-'),
      title: title ?? '',
      style: styleProp ?? 'POSTER',
      itemIds: []
    }
  );

  // TOP10 (`Trending Now`) renders clean unnumbered standard poster cards in v2.1+.
  const variant = $derived<CardVariant>(
    resolvedRow.style === 'LANDSCAPE' || resolvedRow.style === 'CONTINUE'
      ? 'landscape'
      : resolvedRow.style === 'CIRCLE'
        ? 'circle'
        : resolvedRow.style === 'CHANNEL'
          ? 'channel'
          : 'poster'
  );

  const progressFallback = (id: string) => 0.15 + (hashStr(id) % 70) / 100;
  const progressFor = (id: string) =>
    progressMap?.get(id) ?? (resolvedRow.style === 'CONTINUE' ? progressFallback(id) : undefined);

  // ---- Infinite horizontal row pagination ----
  let extra = $state.raw<Item[]>([]);
  let more = $state.raw<Row['more']>(undefined);
  let busy = $state(false);
  let scrollerEl = $state<HTMLElement>();

  $effect(() => {
    more = resolvedRow.more;
    extra = [];
    busy = false;
  });

  const baseItems = $derived(dedupeItems(items));
  const all = $derived.by(() => (extra.length ? mergeUniqueItems(baseItems, extra) : baseItems));

  const showSeeAll = $derived(
    Boolean(onSeeAll) &&
      resolvedRow.style !== 'CONTINUE' &&
      resolvedRow.style !== 'CHANNEL' &&
      Boolean(more?.hasMore || all.length >= 4)
  );
  const canPageMore = $derived(Boolean(more?.hasMore && space));

  async function loadMore() {
    const m = more;
    if (busy || !space || !m?.hasMore) return;
    busy = true;
    try {
      const r = await catalog.moreRow(space, m, categoryId);
      if (!r || !r.items.length) {
        more = { ...m, hasMore: Boolean(r?.more?.hasMore && r.items.length > 0) };
      } else {
        extra = mergeUniqueItems(extra, r.items);
        more = r.more;
      }
    } catch {
      /* keep hasMore: next scroll/focus retries */
    } finally {
      busy = false;
    }
  }

  function handleScroll(e: Event) {
    if (!canPageMore || busy) return;
    const node = e.currentTarget as HTMLElement;
    if (node.scrollLeft + node.clientWidth >= node.scrollWidth - 720) {
      void loadMore();
    }
  }

  function handleFocusIn(e: FocusEvent) {
    if (!canPageMore || busy || !scrollerEl) return;
    const target = e.target;
    if (!(target instanceof HTMLElement)) return;
    const card = target.closest('.card');
    if (!card) return;
    const cards = [...scrollerEl.querySelectorAll('.card')];
    const idx = cards.indexOf(card);
    if (idx >= 0 && idx >= cards.length - 4) {
      void loadMore();
    }
  }

  /** Sentinel at row end: fetches next page well before the user reaches the boundary. */
  function sentinel(node: HTMLElement) {
    if (typeof IntersectionObserver === 'undefined') return;
    const io = new IntersectionObserver(
      (es) => es.some((e) => e.isIntersecting) && void loadMore(),
      { root: node.parentElement, rootMargin: '0px 900px 0px 0px' }
    );
    io.observe(node);
    return { destroy: () => io.disconnect() };
  }
</script>

{#if all.length > 0}
  <section class="row" aria-label={resolvedRow.title}>
    {#if resolvedRow.title}
      <div class="row-head">
        <span class="accent-bar" aria-hidden="true"></span>
        <h2 class="section-title row-title">{resolvedRow.title}</h2>
        {#if showSeeAll}
          <button
            type="button"
            class="see-all"
            aria-label="See all {resolvedRow.title}"
            onclick={() => onSeeAll?.({ row: resolvedRow, items: all, more })}
          >
            <span>See all</span>
            <svg width="14" height="14" viewBox="0 0 24 24" aria-hidden="true">
              <path
                fill="none"
                stroke="currentColor"
                stroke-width="2.2"
                stroke-linecap="round"
                stroke-linejoin="round"
                d="M9 6l6 6-6 6"
              />
            </svg>
          </button>
        {/if}
      </div>
    {/if}

    <div
      bind:this={scrollerEl}
      class="h-scroll"
      onscroll={handleScroll}
      onfocusin={handleFocusIn}
    >
      {#each all as item, i (item.id)}
        <Poster
          {item}
          {variant}
          progress={progressFor(item.id)}
          eager={eager && i < 4}
        />
      {/each}

      {#if busy}
        <div class="row-sk skeleton" class:wide={variant === 'landscape'} aria-hidden="true"></div>
      {/if}

      {#if canPageMore}
        <span class="more" use:sentinel aria-hidden="true"></span>
      {/if}
    </div>
  </section>
{/if}

<style>
  .row {
    content-visibility: auto;
    contain-intrinsic-size: auto 320px;
    margin-top: 20px;
  }

  /* Movira-style header: 3px x 16px vertical red accent bar + bold heading + See all */
  .row-head {
    display: flex;
    align-items: center;
    gap: 9px;
    margin: 0 16px 8px;
  }
  .accent-bar {
    flex: none;
    width: 3px;
    height: 16px;
    border-radius: 2px;
    background: var(--accent);
    box-shadow: 0 0 10px color-mix(in srgb, var(--accent) 50%, transparent);
  }
  .row-title {
    flex: 1;
    min-width: 0;
    margin: 0;
    font-size: 1.05rem;
    font-weight: 750;
    letter-spacing: -0.01em;
    color: var(--text-primary);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .see-all {
    flex: none;
    display: inline-flex;
    align-items: center;
    gap: 3px;
    min-height: 32px;
    padding: 4px 10px 4px 12px;
    border: 0;
    border-radius: var(--radius-pill);
    background: transparent;
    color: var(--text-secondary);
    font-size: 0.82rem;
    font-weight: 650;
    cursor: pointer;
    transition:
      background calc(var(--dur-short) * var(--dur-scale)) var(--ease-standard),
      color calc(var(--dur-short) * var(--dur-scale)) var(--ease-standard),
      transform calc(var(--dur-short) * var(--dur-scale)) var(--ease-emphasized);
  }
  .see-all:hover,
  .see-all:focus-visible {
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
    color: var(--text-primary);
  }
  .see-all:active {
    transform: scale(0.96);
  }

  .row-sk {
    flex: none;
    width: clamp(112px, 28vw, 168px);
    aspect-ratio: 2 / 3;
    border-radius: var(--radius-card);
  }
  .row-sk.wide {
    width: clamp(220px, 60vw, 320px);
    aspect-ratio: 16 / 9;
  }

  .more {
    flex: none;
    width: 1px;
    align-self: stretch;
  }
</style>
