<script lang="ts">
  import { page } from '$app/state';
  import { goto } from '$app/navigation';
  import { base } from '$app/paths';
  import SpaceView from '$lib/components/SpaceView.svelte';
  import LiveTv from '$lib/tv/LiveTv.svelte';
  import MusicHome from '$lib/music/MusicHome.svelte';
  import AdultLock from '$lib/adult/AdultLock.svelte';
  import { adult } from '$lib/adult/store.svelte';
  import { isVideoSpace, spaceFromSlug } from '$lib/data/types';

  const space = $derived(spaceFromSlug(page.params.space ?? '') ?? 'HOME');
  const titles: Record<string, string> = {
    ANIME: 'Anime',
    DRAMA: 'Drama',
    TV: 'TV · Live & Sports',
    MUSIC: 'Music',
    COMICS: 'Comics',
    ADULT: 'Adult'
  };

  $effect(() => {
    if (space === 'ADULT' && !adult.enabled) {
      void goto(`${base}/app`, { replaceState: true });
    }
  });
</script>

<svelte:head><title>{space === 'ADULT' && !adult.enabled ? 'Cinetor' : `${titles[space] ?? 'Cinetor'} · Cinetor`}</title></svelte:head>

{#if space === 'ADULT'}
  {#if adult.enabled}
    <div class="titled-space">
      <AdultLock>
        <SpaceView {space} cat={page.url.searchParams.get('cat')} showCategoryChips={false} />
      </AdultLock>
    </div>
  {/if}
{:else if space === 'TV'}
  <LiveTv cat={page.url.searchParams.get('cat')} />
{:else if space === 'MUSIC'}
  <MusicHome cat={page.url.searchParams.get('cat')} />
{:else}
  <div class="titled-space">
    <SpaceView {space} cat={page.url.searchParams.get('cat')} showCategoryChips={isVideoSpace(space) || space === 'COMICS'} />
  </div>
{/if}

<style>
  .titled-space :global(.card .title) {
    display: -webkit-box;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    -webkit-box-orient: vertical;
    white-space: normal;
    overflow: hidden;
    color: var(--text-primary);
    font-size: 0.84rem;
    font-weight: 680;
    line-height: 1.28;
    opacity: 1;
    visibility: visible;
  }
</style>
