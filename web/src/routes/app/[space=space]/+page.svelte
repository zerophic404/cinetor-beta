<script lang="ts">
  import { page } from '$app/state';
  import { base } from '$app/paths';
  import SpaceView from '$lib/components/SpaceView.svelte';
  import { ADULT_ENABLED } from '$lib/constants';
  import { isVideoSpace, spaceFromSlug } from '$lib/data/types';

  const space = $derived(spaceFromSlug(page.params.space ?? '') ?? 'HOME');
  const titles: Record<string, string> = { ANIME: 'Anime', DRAMA: 'Drama', TV: 'Live TV & Sports', MUSIC: 'Music', COMICS: 'Comics', ADULT: 'Adult' };
</script>

<svelte:head><title>{titles[space] ?? 'Cinetor'} · Cinetor</title></svelte:head>

{#if space === 'ADULT' && !ADULT_ENABLED}
  <section class="locked glass">
    <h1>18+ space is off</h1>
    <p>Enable it in Settings with an 18+ confirmation and a 4-digit PIN.</p>
    <a class="btn btn-primary" href="{base}/app/me">Open settings</a>
  </section>
{:else}
  <SpaceView {space} cat={page.url.searchParams.get('cat')} showCategoryChips={isVideoSpace(space)} />
{/if}

<style>
  .locked {
    margin: 40px 16px;
    padding: 28px;
    text-align: center;
  }
  .locked p {
    color: var(--text-secondary);
  }
</style>
