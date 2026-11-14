<script lang="ts">
  import { page } from '$app/state';
  import { catalog } from '$lib/data/app-catalog';
  import type { Item, Source } from '$lib/data/types';
  import { SkeletonBox } from '$lib/design/skeleton';
  import { detailsHref } from '$lib/ui/handoff';

  // Stub until the full player (Task 8.3): plays progressive test streams natively.
  const id = $derived(page.url.searchParams.get('id') ?? '');
  const epId = $derived(page.url.searchParams.get('ep'));
  let item = $state.raw<Item | undefined>(undefined);
  let done = $state(false);
  let source = $state.raw<Source | undefined>(undefined);

  $effect(() => {
    const want = id;
    const ep = epId;
    let alive = true;
    done = false;
    item = undefined;
    source = undefined;
    catalog.item(want).then(async (it) => {
      if (!alive) return;
      item = it;
      let sources = it?.sources ?? [];
      if (it && ep && it.seasons) {
        const s = it.seasons.find((x) => x.episodes.some((e) => e.id === ep)) ?? it.seasons.find((x) => ep.includes(`-s${x.n}e`));
        const eps = s ? (s.episodes.length ? s.episodes : await catalog.season(it, s.n).catch(() => [])) : [];
        sources = eps.find((e) => e.id === ep)?.sources ?? sources;
      }
      if (!alive) return;
      source = sources.find((x) => x.kind === 'MP4' || x.kind === 'WEBM') ?? sources[0];
      done = true;
    }, () => alive && (done = true));
    return () => {
      alive = false;
    };
  });
</script>

<svelte:head><title>{item?.title ?? 'Play'} · Cinetor</title></svelte:head>

<section class="watch">
  {#if !done}
    <SkeletonBox aspect="16 / 9" radius="var(--radius-lg)" />
  {:else if source && (source.kind === 'MP4' || source.kind === 'WEBM')}
    <!-- svelte-ignore a11y_media_has_caption -->
    <video src={source.url} controls autoplay playsinline></video>
  {:else}
    <div class="msg glass">
      <p>The full player (HLS/DASH, gestures, subtitles, downloads) arrives in the next update.</p>
      {#if source}<p class="url">{source.kind} · {source.url}</p>{/if}
    </div>
  {/if}
  {#if item}
    <h1>{item.title}</h1>
    <a class="btn btn-ghost" href={detailsHref(item)}>Back to details</a>
  {/if}
</section>

<style>
  .watch {
    padding: 0 16px;
    display: grid;
    gap: 14px;
    max-width: 1100px;
  }
  video {
    width: 100%;
    aspect-ratio: 16 / 9;
    background: #000;
    border-radius: var(--radius-lg);
  }
  .msg {
    padding: 24px;
  }
  .url {
    font-size: 0.75rem;
    color: var(--text-tertiary);
    word-break: break-all;
  }
  h1 {
    margin: 0;
    font-size: 1.3rem;
  }
  .btn {
    justify-self: start;
  }
</style>
