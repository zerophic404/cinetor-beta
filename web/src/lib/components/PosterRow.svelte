<script lang="ts">
  import { catalog } from '$lib/data/app-catalog';
  import type { Item, Row, Space } from '$lib/data/types';
  import { hashStr } from '$lib/data/mock-sources';
  import Poster, { type CardVariant } from './Poster.svelte';

  interface Props {
    row: Row;
    items: Item[];
    eager?: boolean;
    /** Owning space / category: paged gateway rows fetch more items when scrolled to the end. */
    space?: Space;
    categoryId?: string;
  }
  let { row, items, eager = false, space, categoryId }: Props = $props();

  const variant = $derived<CardVariant>(
    row.style === 'LANDSCAPE' || row.style === 'CONTINUE'
      ? 'landscape'
      : row.style === 'CIRCLE'
        ? 'circle'
        : row.style === 'CHANNEL'
          ? 'channel'
          : 'poster'
  );
  // Continue-watching progress is a stable placeholder until playback history exists (Phase 3 / 8.3).
  const progressOf = (id: string) => 0.15 + (hashStr(id) % 70) / 100;

  // ---- infinite row (gateway paging) ----
  let extra = $state.raw<Item[]>([]);
  let more = $state.raw<Row['more']>(undefined);
  let loading = false;
  $effect(() => {
    more = row.more;
    extra = [];
  });
  const all = $derived.by(() => {
    if (!extra.length) return items;
    const seen = new Set(items.map((i) => i.id));
    return [...items, ...extra.filter((i) => !seen.has(i.id))];
  });

  async function loadMore() {
    const m = more;
    if (loading || !space || !m?.hasMore) return;
    loading = true;
    try {
      const r = await catalog.moreRow(space, m, categoryId);
      if (!r) more = { ...m, hasMore: false };
      else {
        extra = [...extra, ...r.items];
        more = r.more;
      }
    } catch {
      /* keep hasMore: the next scroll retries */
    } finally {
      loading = false;
    }
  }

  /** Sentinel at the row end: fetch the next page well before the user reaches it. */
  function sentinel(node: HTMLElement) {
    if (typeof IntersectionObserver === 'undefined') return;
    const io = new IntersectionObserver((es) => es.some((e) => e.isIntersecting) && void loadMore(), { root: node.parentElement, rootMargin: '0px 800px 0px 0px' });
    io.observe(node);
    return { destroy: () => io.disconnect() };
  }
</script>

<section class="row" aria-label={row.title}>
  <h2 class="section-title">{row.title}</h2>
  <div class="h-scroll">
    {#each all as item, i (item.id)}
      <Poster
        {item}
        {variant}
        rank={row.style === 'TOP10' ? i + 1 : undefined}
        progress={row.style === 'CONTINUE' ? progressOf(item.id) : undefined}
        eager={eager && i < 4}
      />
    {/each}
    {#if more?.hasMore && space}<span class="more" use:sentinel aria-hidden="true"></span>{/if}
  </div>
</section>

<style>
  .row {
    content-visibility: auto;
    contain-intrinsic-size: auto 300px;
  }
  .more {
    flex: none;
    width: 1px;
    align-self: stretch;
  }
</style>
