<script lang="ts">
  // This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer,
  // scrape or hack anything without Mythbrix's explicit permission.
  import { onDestroy } from 'svelte';
  import { page } from '$app/state';
  import { base } from '$app/paths';
  import { goto } from '$app/navigation';
  import { catalog } from '$lib/data/app-catalog';
  import type { Item } from '$lib/data/types';
  import { SkeletonBox } from '$lib/design/skeleton';
  import { detailsHref, handedOff, resolveHandoffSlug, titleHref, watchHref } from '$lib/ui/handoff';
  import { nextUp, prepareMedia, session } from '$lib/player/controller';
  import type { Media } from '$lib/player/sources';
  import Player from '$lib/player/Player.svelte';
  import { repo, img } from '$lib/data/repo';
  import { channelsOf, currentProgram, zapChannel } from '$lib/tv/epg';
  import { live } from '$lib/tv/live.svelte';
  import { spaceForId } from '$lib/data/catalog';
  import { adult } from '$lib/adult/store.svelte';
  import AdultLock from '$lib/adult/AdultLock.svelte';

  const id = $derived(page.url.searchParams.get('channel') ?? page.url.searchParams.get('id') ?? '');
  const epId = $derived(page.url.searchParams.get('ep'));
  const sParam = $derived(Number(page.url.searchParams.get('s')) || undefined);
  const eParam = $derived(Number(page.url.searchParams.get('e')) || undefined);
  const explicitLive = $derived(page.url.searchParams.get('live') === '1' || page.url.searchParams.has('channel'));
  const adultLocked = $derived(spaceForId(id) === 'ADULT' && !adult.unlocked);

  let item = $state.raw<Item | undefined>(undefined);
  let media = $state.raw<Media | undefined>(undefined);
  let status = $state<'loading' | 'ready' | 'missing' | 'error'>('loading');

  $effect(() => {
    const want = id;
    const ep = epId;
    let alive = true;
    status = 'loading';
    if (!want) {
      status = 'missing';
      return;
    }
    if (adultLocked) return;

    const slug = resolveHandoffSlug(want, page.url.searchParams.get('slug'));
    catalog.hintSlug(want, slug);

    const seed = handedOff(want) ?? catalog.peek(want);
    const isTvSpace = explicitLive || spaceForId(want) === 'TV' || seed?.type === 'CHANNEL' || seed?.live === true;

    // Non-live VOD links redirect cleanly to `/app/title?...&play=1` (preserving slug + episode)
    // so VOD always plays inline on the Title Details page and never hits "Title not found".
    if (!isTvSpace) {
      void goto(titleHref(seed ?? want, { slug, ep, s: sParam, e: eParam, play: true }), { replaceState: true });
      return () => {
        alive = false;
      };
    }

    (async () => {
      let found =
        (await repo.item(want, 'TV').catch(() => undefined)) ??
        (await catalog.item(want, 'TV').catch(() => undefined)) ??
        seed;
      if (!alive) return;
      if (!found) {
        const tvItems = await repo.items('TV').catch(() => [] as Item[]);
        if (!alive) return;
        found = tvItems.find((x) => x.id === want);
      }
      if (!found) {
        status = navigator.onLine === false ? 'error' : 'missing';
        return;
      }
      if (!found.live && found.type !== 'CHANNEL' && found.space !== 'TV') {
        void goto(titleHref(found, { slug: slug ?? found.slug, ep, s: sParam, e: eParam, play: true }), { replaceState: true });
        return;
      }
      item = found;
      const m = await prepareMedia(found, undefined);
      if (!alive) return;
      media = m;
      status = 'ready';
      nextUp.set(null);
      void session.play(m);
    })();

    return () => {
      alive = false;
    };
  });

  // ---- Live TV: channel list for zapping + quick list ----
  let channels = $state.raw<Item[]>([]);
  const isChannel = $derived(!!item?.live && item.type === 'CHANNEL');
  $effect(() => {
    if (!isChannel || channels.length) return;
    repo.items('TV').then(
      (l) => (channels = channelsOf(l)),
      () => {}
    );
  });
  $effect(() => (isChannel ? live.tick() : undefined));

  function zap(dir: 1 | -1) {
    const next = zapChannel(channels, item?.id, dir);
    if (next && next.id !== item?.id) goto(watchHref(next, { live: true }), { replaceState: true });
  }

  const onAir = $derived(item && isChannel ? currentProgram(item.programs, live.base, live.now) : undefined);
  const liveInfo = $derived(
    item && isChannel
      ? `${item.channelNumber ?? ''} · ${item.title}${onAir ? ` · ${onAir.program.title}` : ''}`
      : item?.live
        ? (item.league ?? '')
        : ''
  );

  onDestroy(() => {
    nextUp.set(null);
    session.minimize();
  });

  function back() {
    if (history.length > 1) history.back();
    else if (item && item.space === 'TV') goto(`${base}/app/tv`);
    else if (item) goto(detailsHref(item));
    else goto(`${base}/app`);
  }
</script>

<svelte:head><title>{media?.title ?? item?.title ?? 'Live TV'} · Cinetor</title></svelte:head>

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
        <p>{status === 'missing' ? 'Channel unavailable right now.' : "Couldn't load this stream."}</p>
        <button class="btn btn-ghost" onclick={back}>Back</button>
      </div>
    {/if}
  </div>

  <div class="below">
    {#if item && media}
      <h1>{item.title}</h1>
      {#if media.subtitle}
        <p class="sub">{media.subtitle}</p>
      {/if}
      {#if isChannel}
        {#if onAir}<p class="sub">Now: <b>{onAir.program.title}</b></p>{/if}
        <h2 class="section-title qtitle">Channels <small>PgUp / PgDn · swipe ↑↓ to zap</small></h2>
        <ul class="quick" aria-label="Quick channel list">
          {#each channels as ch (ch.id)}
            {@const cur = currentProgram(ch.programs, live.base, live.now)}
            <li>
              <a href={watchHref(ch, { live: true })} data-sveltekit-replacestate aria-current={ch.id === item.id ? 'true' : undefined}>
                {#if ch.logo}<img src={img(ch.logo)} alt="" width="40" height="40" loading="lazy" />{/if}
                <span><b>{ch.channelNumber} {ch.title}</b><small>{cur?.program.title ?? ''}</small></span>
                {#if live.isFav(ch.id)}<i aria-label="Favorite">★</i>{/if}
              </a>
            </li>
          {/each}
        </ul>
      {/if}
    {:else if status === 'loading'}
      <div class="sk"><SkeletonBox width="60%" height="22px" /></div>
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
    color: var(--warning);
    font-style: normal;
  }
</style>
