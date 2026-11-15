<script lang="ts">
  // This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer,
  // scrape or hack anything without Mythbrix's explicit permission.
  import { onDestroy } from 'svelte';
  import { page } from '$app/state';
  import { goto } from '$app/navigation';
  import { catalog } from '$lib/data/app-catalog';
  import type { Episode, Item, Season } from '$lib/data/types';
  import { SkeletonBox, SkeletonEpisodeList } from '$lib/design/skeleton';
  import { detailsHref, watchHref } from '$lib/ui/handoff';
  import { imgFor } from '$lib/ui/img';
  import { nextUp, prepareMedia, session } from '$lib/player/controller';
  import { nextEpisode, type Media } from '$lib/player/sources';
  import Player from '$lib/player/Player.svelte';
  import { downloads } from '$lib/download/manager.svelte';
  import { repo, img } from '$lib/data/repo';
  import { channelsOf, currentProgram, zapChannel } from '$lib/tv/epg';
  import { live } from '$lib/tv/live.svelte';
  import { spaceForId } from '$lib/data/catalog';
  import { adult } from '$lib/adult/store.svelte';
  import AdultLock from '$lib/adult/AdultLock.svelte';

  const id = $derived(page.url.searchParams.get('id') ?? '');
  const epId = $derived(page.url.searchParams.get('ep'));
  const adultLocked = $derived(spaceForId(id) === 'ADULT' && !adult.unlocked);

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
    if (adultLocked) return;
    catalog.hintSlug(want, page.url.searchParams.get('slug'));
    catalog
      .item(want)
      .catch(() => undefined)
      .then(async (found) => {
        if (!alive) return;
        let it = found;
        let stubEp: Episode | undefined;
        if (!it) {
          // Offline / metadata unavailable: play a completed download from its stored record.
          const stub = await downloads.offlineStub(want, ep ?? undefined);
          if (!alive) return;
          if (stub) {
            it = stub.item;
            stubEp = stub.episode;
          }
        }
        if (!it) {
          status = navigator.onLine === false ? 'error' : 'missing';
          return;
        }
        const itm: Item = it;
        let episode: Episode | undefined = stubEp;
        let loaded: Season[] = [];
        if (!stubEp && it.seasons?.length) {
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
        // Downloaded → play the offline copy (shaka offline URI / OPFS blob URL).
        const m = await prepareMedia(it, episode);
        if (!alive) return;
        media = m;
        status = 'ready';
        const nx = nextEpisode(loaded, episode?.id);
        nextUp.set(nx ? { title: `E${nx.n} · ${nx.title}`, go: () => goto(watchHref(itm.id, nx.id), { replaceState: true }) } : null);
        void session.play(m);
      });
    return () => {
      alive = false;
    };
  });

  // ---- Live TV: channel list for zapping + quick list ----
  let channels = $state.raw<Item[]>([]);
  const isChannel = $derived(!!item?.live && item.type === 'CHANNEL');
  $effect(() => {
    if (!isChannel || channels.length) return;
    repo.items('TV').then((l) => (channels = channelsOf(l)), () => {});
  });
  $effect(() => (isChannel ? live.tick() : undefined));
  function zap(dir: 1 | -1) {
    const next = zapChannel(channels, item?.id, dir);
    if (next && next.id !== item?.id) goto(watchHref(next.id), { replaceState: true });
  }
  const onAir = $derived(item && isChannel ? currentProgram(item.programs, live.base, live.now) : undefined);
  const liveInfo = $derived(item && isChannel ? `${item.channelNumber ?? ''} · ${item.title}${onAir ? ` · ${onAir.program.title}` : ''}` : (item?.live ? (item.league ?? '') : ''));

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
    {#if adultLocked}
      <AdultLock><span></span></AdultLock>
    {:else if media}
      <Player {media} onBack={back} live={!!item?.live} {liveInfo} onZap={isChannel ? zap : undefined} />
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
      {#if !item.live}
        <div class="links">
          <a class="btn btn-ghost" href={detailsHref(item)}>Details</a>
        </div>
      {/if}
      {#if isChannel}
        {#if onAir}<p class="sub">Now: <b>{onAir.program.title}</b></p>{/if}
        <h2 class="section-title qtitle">Channels <small>PgUp / PgDn · swipe ↑↓ to zap</small></h2>
        <ul class="quick" aria-label="Quick channel list">
          {#each channels as ch (ch.id)}
            {@const cur = currentProgram(ch.programs, live.base, live.now)}
            <li>
              <a href={watchHref(ch.id)} data-sveltekit-replacestate aria-current={ch.id === item.id ? 'true' : undefined}>
                {#if ch.logo}<img src={img(ch.logo)} alt="" width="40" height="40" loading="lazy" />{/if}
                <span><b>{ch.channelNumber} {ch.title}</b><small>{cur?.program.title ?? ''}</small></span>
                {#if live.isFav(ch.id)}<i aria-label="Favorite">★</i>{/if}
              </a>
            </li>
          {/each}
        </ul>
      {/if}
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
  .qtitle {
    margin-left: 0;
  }
  .qtitle small {
    font-size: 0.72rem;
    font-weight: 500;
    color: var(--text-tertiary);
    margin-left: 8px;
  }
  .quick {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
    gap: 8px;
    max-height: 46vh;
    overflow-y: auto;
  }
  .quick a {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 8px;
    border-radius: 14px;
    text-decoration: none;
    background: var(--glass-fill);
  }
  .quick a[aria-current='true'] {
    box-shadow: inset 0 0 0 2px var(--accent);
  }
  .quick img {
    width: 40px;
    height: 40px;
    border-radius: 10px;
    object-fit: cover;
  }
  .quick span {
    display: grid;
    min-width: 0;
    flex: 1;
  }
  .quick b,
  .quick small {
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .quick b {
    font-size: 0.85rem;
  }
  .quick small {
    color: var(--text-secondary);
    font-size: 0.75rem;
  }
  .quick i {
    color: #ffc93c;
    font-style: normal;
  }
</style>
