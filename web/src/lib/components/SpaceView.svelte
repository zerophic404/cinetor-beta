<script lang="ts">
  import { catalog } from '$lib/data/app-catalog';
  import type { Space } from '$lib/data/types';
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
  const extra = $derived(model && genre && rows.length === 0 ? genreItems(model, genre) : []);

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
    <p>Couldn't load this space.</p>
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

  {#each rows as r, i (r.row.id)}
    <PosterRow row={r.row} items={r.items} eager={i === 0} />
  {/each}

  {#if extra.length}
    <h2 class="section-title">{genre} · {extra.length}</h2>
    <div class="grid">
      {#each extra as item (item.id)}<Poster {item} />{/each}
    </div>
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
  .attr {
    margin: 28px 16px 0;
    font-size: 0.72rem;
    color: var(--text-tertiary);
  }
</style>
