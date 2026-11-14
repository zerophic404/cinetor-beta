<script lang="ts">
  import { page } from '$app/state';
  import SearchView from '$lib/components/SearchView.svelte';
  import AdultLock from '$lib/adult/AdultLock.svelte';
  import { spaceFromSlug } from '$lib/data/types';

  const space = $derived(spaceFromSlug(page.params.space ?? '') ?? 'HOME');
</script>

<svelte:head><title>Search · Cinetor</title></svelte:head>

{#key space}
  {#if space === 'ADULT'}
    <AdultLock><SearchView {space} /></AdultLock>
  {:else}
    <SearchView {space} />
  {/if}
{/key}
