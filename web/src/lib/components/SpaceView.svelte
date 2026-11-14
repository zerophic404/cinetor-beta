<script lang="ts">
  import { repo } from '$lib/data/repo';
  import type { CatalogFile, Item, Space } from '$lib/data/types';
  import { SkeletonEpgGrid, SkeletonHero, SkeletonPosterRow } from '$lib/design/skeleton';
  import Hero from './Hero.svelte';
  import PosterRow from './PosterRow.svelte';
  import Poster from './Poster.svelte';

  interface Props {
    space: Space;
    /** Selected category id (from ?cat=). Defaults to the first category. */
    cat?: string | null;
    /** Render category chips in-page (video spaces; isolated spaces use their nav tabs). */
    showCategoryChips?: boolean;
  }
  let { space, cat = null, showCategoryChips = true }: Props = $props();

  let file = $state<CatalogFile | null>(null);
  let error = $state<string | null>(null);
  let localCat = $state<string | null>(null);
  let genre = $state<string | null>(null);

  $effect(() => {
    const s = space;
    let alive = true;
    file = null;
    error = null;
    genre = null;
    localCat = null;
    repo.file(s).then(
      (f) => alive && (file = f),
      (e: unknown) => alive && (error = e instanceof Error ? e.message : String(e))
    );
    return () => {
      alive = false;
    };
  });

  const byId = $derived(new Map((file?.items ?? []).map((i) => [i.id, i] as const)));
  const category = $derived.by(() => {
    if (!file) return null;
    const want = localCat ?? cat;
    return file.categories.find((c) => c.id === want) ?? file.categories[0];
  });
  const pick = (ids: string[]) => ids.map((id) => byId.get(id)).filter((i): i is Item => !!i);
  const heroItems = $derived(category ? pick(category.heroIds) : []);
  const genreItems = $derived(file && genre ? file.items.filter((i) => i.genres.includes(genre!)) : []);
</script>

{#if error}
  <div class="state" role="alert">
    <p>Couldn't load this space.</p>
    <button class="btn btn-ghost" onclick={() => { const s = space; error = null; repo.file(s).then((f) => (file = f)); }}>Retry</button>
  </div>
{:else if !file || !category}
  <div class="loading" aria-busy="true" aria-label="Loading">
    <div class="chips-sk"><SkeletonPosterRow count={0} /></div>
    <SkeletonHero />
    {#if space === 'TV'}
      <div class="pad"><SkeletonEpgGrid channels={4} /></div>
    {/if}
    <SkeletonPosterRow />
    <SkeletonPosterRow landscape />
    <SkeletonPosterRow />
  </div>
{:else}
  {#if showCategoryChips && file.categories.length > 1}
    <nav class="h-scroll chips" aria-label="Categories">
      {#each file.categories as c (c.id)}
        <button class="chip" aria-pressed={c.id === category.id} onclick={() => { localCat = c.id; genre = null; }}>{c.title}</button>
      {/each}
    </nav>
  {/if}

  {#key category.id}
    <Hero items={heroItems} />
  {/key}

  {#if file.genres?.length}
    <nav class="h-scroll chips genres" aria-label="Genres">
      <button class="chip" aria-pressed={genre === null} onclick={() => (genre = null)}>All</button>
      {#each file.genres as g (g)}
        <button class="chip" aria-pressed={genre === g} onclick={() => (genre = genre === g ? null : g)}>{g}</button>
      {/each}
    </nav>
  {/if}

  {#if genre}
    <h2 class="section-title">{genre} · {genreItems.length}</h2>
    <div class="grid">
      {#each genreItems as item (item.id)}<Poster {item} />{/each}
    </div>
  {:else}
    {#each category.rows as row, i (row.id)}
      <PosterRow {row} items={pick(row.itemIds)} eager={i === 0} />
    {/each}
  {/if}
{/if}

<style>
  .chips {
    padding-top: 6px;
  }
  .genres {
    margin-top: 14px;
  }
  .chips-sk {
    height: 8px;
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
</style>
