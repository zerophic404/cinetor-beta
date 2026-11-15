<script lang="ts">
  import { page } from '$app/state';
  import SpaceView from '$lib/components/SpaceView.svelte';
  import LiveTv from '$lib/tv/LiveTv.svelte';
  import MusicHome from '$lib/music/MusicHome.svelte';
  import AdultLock from '$lib/adult/AdultLock.svelte';
  import { isVideoSpace, spaceFromSlug } from '$lib/data/types';

  const space = $derived(spaceFromSlug(page.params.space ?? '') ?? 'HOME');
  const titles: Record<string, string> = { ANIME: 'Anime', DRAMA: 'Drama', TV: 'Live TV & Sports', MUSIC: 'Music', COMICS: 'Comics', ADULT: 'Adult' };
</script>

<svelte:head><title>{titles[space] ?? 'Cinetor'} · Cinetor</title></svelte:head>

{#if space === 'ADULT'}
  <AdultLock>
    <SpaceView {space} cat={page.url.searchParams.get('cat')} showCategoryChips={false} />
  </AdultLock>
{:else if space === 'TV'}
  <LiveTv cat={page.url.searchParams.get('cat')} />
{:else if space === 'MUSIC'}
  <MusicHome cat={page.url.searchParams.get('cat')} />
{:else}
  <SpaceView {space} cat={page.url.searchParams.get('cat')} showCategoryChips={isVideoSpace(space)} />
{/if}
