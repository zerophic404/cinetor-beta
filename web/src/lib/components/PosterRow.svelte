<script lang="ts">
  import type { Item, Row } from '$lib/data/types';
  import { hashStr } from '$lib/data/mock-sources';
  import Poster, { type CardVariant } from './Poster.svelte';

  let { row, items, eager = false }: { row: Row; items: Item[]; eager?: boolean } = $props();

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
</script>

<section class="row" aria-label={row.title}>
  <h2 class="section-title">{row.title}</h2>
  <div class="h-scroll">
    {#each items as item, i (item.id)}
      <Poster
        {item}
        {variant}
        rank={row.style === 'TOP10' ? i + 1 : undefined}
        progress={row.style === 'CONTINUE' ? progressOf(item.id) : undefined}
        eager={eager && i < 4}
      />
    {/each}
  </div>
</section>

<style>
  .row {
    content-visibility: auto;
    contain-intrinsic-size: auto 300px;
  }
</style>
