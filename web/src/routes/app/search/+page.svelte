<script lang="ts">
  import { page } from '$app/state';
  import SearchView from '$lib/components/SearchView.svelte';
  import type { Space } from '$lib/data/types';

  type SearchScope = 'ALL' | 'TV';

  let scope = $state<SearchScope>(page.url.searchParams.get('scope') === 'tv' ? 'TV' : 'ALL');
  const activeSpace = $derived<Space | undefined>(scope === 'TV' ? 'TV' : undefined);
</script>

<svelte:head><title>Search · Cinetor</title></svelte:head>

<div class="search-shell">
  <div class="scope-bar" role="radiogroup" aria-label="Search catalog">
    <button
      type="button"
      class="chip"
      class:active={scope === 'ALL'}
      role="radio"
      aria-checked={scope === 'ALL'}
      onclick={() => (scope = 'ALL')}
    >
      Movies, Anime &amp; Drama
    </button>
    <button
      type="button"
      class="chip"
      class:active={scope === 'TV'}
      role="radio"
      aria-checked={scope === 'TV'}
      onclick={() => (scope = 'TV')}
    >
      Live TV &amp; Sports
    </button>
  </div>

  {#key activeSpace}
    <SearchView space={activeSpace} />
  {/key}
</div>

<style>
  .search-shell {
    display: grid;
    gap: 10px;
  }
  .scope-bar {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    padding: 0 16px;
    max-width: 720px;
    margin: 0 auto;
    width: 100%;
  }
</style>
