<script lang="ts">
  import { onDestroy } from 'svelte';
  import { page } from '$app/state';
  import { goto } from '$app/navigation';
  import { catalog } from '$lib/data/app-catalog';
  import type { Episode, Item, Season } from '$lib/data/types';
  import { SkeletonBox, SkeletonEpisodeList } from '$lib/design/skeleton';
  import { detailsHref, watchHref } from '$lib/ui/handoff';
  import { imgFor } from '$lib/ui/img';
  import { mediaFor, nextUp, session } from '$lib/player/controller';
  import { nextEpisode, type Media } from '$lib/player/sources';
  import Player from '$lib/player/Player.svelte';

  const id = $derived(page.url.searchParams.get('id') ?? '');
  const epId = $derived(page.url.searchParams.get('ep'));

  let item = $state.raw<Item | undefined>(undefined);
  let media = $state.raw<Media | undefined>(undefined);
  let seasons = $state.raw<Season[]>([]);
  let seasonN = $state<number | null>(null);
  let status = $state<'loading' | 'ready' | 'missing' | 'error'>('loading');

  async function episodesOf(it: Item, n: number): Promise<Episode[]> {
    const s = it.seasons?.find((x) => x.n === n);
    if (!s) return [];
    return s.episodes.length ? s.episodes : catalog.season(it, n).catch(() => []);
  }

  $effect(() => {
    const want = id;
    const ep = epId;
    let alive = true;
    status = 'loading';
    catalog.item(want).then(
      async (it) => {
        if (!alive) return;
        if (!it) {
          status = 'missing';
          return;
        }
        const itm: Item = it;
        let episode: Episode | undefined;
        let loaded: Season[] = [];
        if (it.seasons?.length) {
          const s = (ep && (it.seasons.find((x) => x.episodes.some((e) => e.id === ep)) ?? it.seasons.find((x) => ep.includes(`-s${x.n}e`)))) || it.seasons[0];
          const eps = await episodesOf(it, s.n);
          if (!alive) return;
          loaded = [{ ...s, episodes: eps }];
          episode = (ep ? eps.find((e) => e.id === ep) : undefined) ?? eps[0];
          seasonN = s.n;
          // Next season preloaded when this is the last episode (cross-season "next").
          const nextS = it.seasons.find((x) => x.n > s.n);
          if (nextS && episode && eps[eps.length - 1]?.id === episode.id) {
            const nEps = await episodesOf(it, nextS.n);
            if (!alive) return;
            loaded.push({ ...nextS, episodes: nEps });
          }
        }
        item = it;
        seasons = loaded;
        const m = mediaFor(it, episode);
        media = m;
        status = 'ready';
        const nx = nextEpisode(loaded, episode?.id);
        nextUp.set(nx ? { title: `E${nx.n} · ${nx.title}`, go: () => goto(watchHref(itm.id, nx.id), { replaceState: true }) } : null);
        void session.play(m);
      },
      () => alive && (status = 'error')
    );
    return () => {
      alive = false;
    };
  });

  onDestroy(() => {
    nextUp.set(null);
    session.minimize();
  });

  function back() {
    if (history.length > 1) history.back();
    else if (item) goto(detailsHref(item));
    else goto(detailsHref({ id }));
  }

  const current = $derived(seasons.find((s) => s.n === seasonN));
  const fmt = (sec?: number) => (sec ? `${Math.round(sec / 60)}m` : '');
</script>

<svelte:head><title>{media?.title ?? item?.title ?? 'Play'} · Cinetor</title></svelte:head>

<section class="watch">
  <div class="pwrap">
    {#if media}
      <Player {media} onBack={back} />
    {:else if status === 'loading'}
      <SkeletonBox aspect="16 / 9" radius="0" />
    {:else}
      <div class="state">
        <p>{status === 'missing' ? 'Title not found.' : "Couldn't load this title."}</p>
        <button class="btn btn-ghost" onclick={back}>Back</button>
      </div>
    {/if}
  </div>

  <div class="below">
    {#if item && media}
      <h1>{item.title}</h1>
      {#if media.subtitle}<p class="sub">{media.subtitle}</p>{/if}
      <div class="links">
        <a class="btn btn-ghost" href={detailsHref(item)}>Details</a>
      </div>
      {#if current?.episodes.length}
        <h2 class="section-title">Season {current.n}</h2>
        <ol class="eps">
          {#each current.episodes as ep (ep.id)}
            {@const still = imgFor(ep.still, 'still')}
            <li>
              <a href={watchHref(item.id, ep.id)} class="ep" aria-current={ep.id === media.episode?.id ? 'true' : undefined} data-sveltekit-replacestate>
                <div class="still">
                  <img sizes={still.sizes} srcset={still.srcset} src={still.src} alt="" loading="lazy" decoding="async" width="320" height="180" />
                  <span class="dur">{fmt(ep.durationSec)}</span>
                  {#if ep.id === media.episode?.id}<span class="now">Playing</span>{/if}
                </div>
                <div class="txt">
                  <h3>{ep.n}. {ep.title}</h3>
                  {#if ep.synopsis}<p>{ep.synopsis}</p>{/if}
                </div>
              </a>
            </li>
          {/each}
        </ol>
      {/if}
    {:else if status === 'loading'}
      <div class="sk"><SkeletonBox width="60%" height="22px" /><SkeletonEpisodeList count={3} /></div>
    {/if}
  </div>
</section>

<style>
  .watch {
    max-width: 1200px;
    margin: 0 auto;
  }
  .pwrap {
    position: sticky;
    top: 0;
    z-index: 20;
    background: #000;
  }
  @media (min-width: 900px) {
    .pwrap {
      position: relative;
      margin: 16px 16px 0;
      border-radius: var(--radius-lg);
      overflow: hidden;
    }
  }
  /* Phone rotated to landscape → immersive fullscreen layout. */
  @media (orientation: landscape) and (max-height: 520px) {
    .pwrap {
      position: fixed;
      inset: 0;
      z-index: 70;
      margin: 0;
      border-radius: 0;
    }
    .pwrap :global(.stage) {
      height: 100dvh;
      aspect-ratio: auto;
    }
    .below {
      display: none;
    }
  }
  .below {
    padding: 14px 16px 24px;
  }
  h1 {
    margin: 0;
    font-size: 1.3rem;
  }
  .sub {
    margin: 4px 0 0;
    color: var(--text-secondary);
  }
  .links {
    margin: 12px 0 4px;
  }
  .eps {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    gap: 10px;
  }
  .ep {
    display: grid;
    grid-template-columns: clamp(120px, 34vw, 190px) 1fr;
    gap: 12px;
    align-items: center;
    text-decoration: none;
    border-radius: var(--radius-md);
  }
  .ep[aria-current='true'] .still {
    box-shadow: 0 0 0 2px var(--accent);
  }
  .still {
    position: relative;
    aspect-ratio: 16 / 9;
    border-radius: var(--radius-md);
    overflow: hidden;
    background: var(--surface);
  }
  .still img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
  .dur,
  .now {
    position: absolute;
    bottom: 6px;
    padding: 1px 6px;
    border-radius: 6px;
    font-size: 0.7rem;
  }
  .dur {
    right: 6px;
    background: rgb(0 0 0 / 0.7);
  }
  .now {
    left: 6px;
    background: var(--accent);
    font-weight: 700;
  }
  .txt h3 {
    margin: 0 0 4px;
    font-size: 0.9rem;
  }
  .txt p {
    margin: 0;
    font-size: 0.8rem;
    color: var(--text-secondary);
    display: -webkit-box;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }
  .state {
    aspect-ratio: 16 / 9;
    display: grid;
    place-content: center;
    gap: 10px;
    text-align: center;
  }
  .sk {
    display: grid;
    gap: 14px;
  }
</style>
