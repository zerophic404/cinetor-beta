<script lang="ts">
  import type { Item, Row } from '$lib/data/types';
  import Poster from './Poster.svelte';

  let { row, items, eager = false }: { row: Row; items: Item[]; eager?: boolean } = $props();
  const landscape = $derived(row.style === 'LANDSCAPE' || row.style === 'CONTINUE');
</script>

<section class="row" aria-label={row.title}>
  <h2 class="section-title">{row.title}</h2>
  <div class="h-scroll">
    {#each items as item, i (item.id)}
      <Poster {item} {landscape} rank={row.style === 'TOP10' ? i + 1 : undefined} eager={eager && i < 4} />
    {/each}
  </div>
</section>

<style>
  .row {
    content-visibility: auto;
    contain-intrinsic-size: auto 280px;
  }
</style>
