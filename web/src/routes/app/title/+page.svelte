<script lang="ts">
  // This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer,
  // scrape or hack anything without Mythbrix's explicit permission.
  import { onDestroy, onMount } from 'svelte';
  import { page } from '$app/state';
  import { base } from '$app/paths';
  import { catalog } from '$lib/data/app-catalog';
  import { gwEpisodeId, isGatewayId, streamPlaceholder, subjectIdOf } from '$lib/data/gateway';
  import { colorOf, ensureColor } from '$lib/data/live-color.svelte';
  import { activeProfileId, myList } from '$lib/data/library.svelte';
  import { isVideoSpace, type Episode, type Item, type Row, type Source } from '$lib/data/types';
  import { SkeletonBox, SkeletonDetails, SkeletonEpisodeList, SkeletonPosterRow } from '$lib/design/skeleton';
  import { tint } from '$lib/design/tint.svelte';
  import Credits from '$lib/components/Credits.svelte';
  import PosterRow from '$lib/components/PosterRow.svelte';
  import { imgFor } from '$lib/ui/img';
  import { handedOff, rememberHandoffSlug, resolveHandoffSlug, SHARED_NAME } from '$lib/ui/handoff';
  import { spaceHref } from '$lib/nav';
  import { mediaFor, nextUp, prepareMedia, preloader, resume, session } from '$lib/player/controller';
  import Player from '$lib/player/Player.svelte';
  import type { Media } from '$lib/player/sources';
  import BatchSeriesDownloadSheet from '$lib/download/BatchSeriesDownloadSheet.svelte';
  import DownloadButton from '$lib/download/DownloadButton.svelte';
  import DownloadDetailsSheet from '$lib/download/DownloadDetailsSheet.svelte';
  import DownloadOptionsSheet from '$lib/download/DownloadOptionsSheet.svelte';
  import { extractTitleBadges, formatSeriesSubtitle } from '$lib/download/batch';
  import { canDownload, downloads } from '$lib/download/manager.svelte';
  import { percent } from '$lib/download/math';
  import { spaceForId } from '$lib/data/catalog';
  import { adult } from '$lib/adult/store.svelte';
  import AdultLock from '$lib/adult/AdultLock.svelte';
  import { settings } from '$lib/settings/store.svelte';

  const readiness = preloader.readiness;
  const sess = session.state;

  const id = $derived(page.url.searchParams.get('id') ?? '');
  const slugQuery = $derived(page.url.searchParams.get('slug'));
  const epQuery = $derived(page.url.searchParams.get('ep'));
  const sQuery = $derived(Number(page.url.searchParams.get('s')) || null);
  const eQuery = $derived(Number(page.url.searchParams.get('e')) || null);
  const autoPlayQuery = $derived(page.url.searchParams.get('play') === '1');

  /** 18+ titles stay behind the PIN gate (no metadata, no preload) until unlocked this session. */
  const adultLocked = $derived(spaceForId(id) === 'ADULT' && !adult.unlocked);

  let preview = $state.raw<Item | undefined>(undefined);
  let item = $state.raw<Item | undefined>(undefined);
  let status = $state<'loading' | 'ready' | 'missing' | 'error'>('loading');
  let seasonN = $state<number | null>(null);
  let episodes = $state.raw<Episode[] | null>(null);
  let related = $state.raw<Item[] | null>(null);
  let expanded = $state(false);
  let resolvedVariants = $state.raw<Source[]>([]);

  // Download sheets (single unified Download action button)
  let batchOpen = $state(false);
  let movieOptionsOpen = $state(false);
  let movieDetailsOpen = $state(false);

  // In-place inline player state
  let playingInline = $state(false);
  let activeSeason = $state<number | null>(null);
  let activeEpisode = $state.raw<Episode | undefined>(undefined);
  let activeMedia = $state.raw<Media | undefined>(undefined);
  let resolvingInline = $state(false);
  let heroStageEl = $state<HTMLElement>();

  // Pre-warmed target tracking
  let target = $state.raw<Episode | undefined>(undefined);
  let playKey = $state('');
  let resumePos = $state(0);
  let mountedId = '';

  onMount(() => void downloads.init());

  onDestroy(() => {
    nextUp.set(null);
    const snap = session.snapshot;
    if (playingInline && snap.media && snap.media.item.id === mountedId && snap.mode !== 'hidden') {
      session.minimize();
    }
  });

  function parseEpNums(epId?: string | null): { se?: number; ep?: number } {
    if (!epId) return {};
    const m = /[-:]s(\d+)e(\d+)$/i.exec(epId);
    if (!m) return {};
    return { se: Number(m[1]) || undefined, ep: Number(m[2]) || undefined };
  }

  function slugToSearchWords(slug?: string | null): string {
    if (!slug) return '';
    return slug
      .replace(/^tmdb-(?:tv|movie)-\d+$/i, '')
      .replace(/-[a-z0-9]{8,}$/i, '')
      .replace(/[-_]+/g, ' ')
      .trim();
  }

  function buildProvisionalTarget(
    wantId: string,
    seed: Item | undefined,
    slug: string | undefined,
    seHint: number | null,
    epHint: number | null,
    epIdHint: string | null
  ): { item: Item; episode?: Episode; season: number | null } | null {
    if (isGatewayId(wantId)) {
      const subId = subjectIdOf(wantId);
      if (!subId) return null;
      const effectiveSlug = slug || seed?.slug || (subId.startsWith('tmdb-') ? subId : '');
      const parsed = parseEpNums(epIdHint);
      const isSeries = Boolean(epIdHint || seHint || epHint || seed?.type === 'SERIES' || seed?.seasons?.length);
      const se = isSeries ? (parsed.se ?? seHint ?? 1) : 0;
      const epNum = isSeries ? (parsed.ep ?? epHint ?? 1) : 0;
      const src = streamPlaceholder(subId, effectiveSlug, se, epNum);
      const baseItem: Item = seed
        ? {
            ...seed,
            slug: effectiveSlug || seed.slug,
            sources: !isSeries ? (seed.sources?.length ? seed.sources : [src]) : seed.sources
          }
        : {
            id: wantId,
            space: (spaceForId(wantId) ?? 'HOME'),
            categoryId: '',
            type: isSeries ? 'SERIES' : 'MOVIE',
            title: slugToSearchWords(effectiveSlug) || 'Loading…',
            year: 0,
            rating: 0,
            maturity: '',
            genres: [],
            synopsis: '',
            poster: '',
            backdrop: '',
            dominantColor: '#09090B',
            accentColor: '#E11D2E',
            slug: effectiveSlug || undefined,
            sources: isSeries ? [] : [src]
          };
      if (!isSeries) return { item: baseItem, season: null };
      const provisionalEp: Episode = {
        id: epIdHint || gwEpisodeId(wantId, se, epNum),
        n: epNum,
        title: `Episode ${epNum}`,
        durationSec: 0,
        still: seed?.backdrop || seed?.poster || '',
        sources: [src],
        subtitles: []
      };
      return { item: baseItem, episode: provisionalEp, season: se };
    }
    if (seed?.sources?.length) {
      return { item: seed, season: null };
    }
    return null;
  }

  function ensurePlayableSummary(seed: Item, slug?: string): Item {
    if (seed.sources?.length || seed.seasons?.length) return seed;
    const prov = buildProvisionalTarget(seed.id, seed, slug, null, null, null);
    return prov ? prov.item : seed;
  }

  function seasonOfEpisode(it: Item | undefined, ep: Episode | undefined): number | null {
    if (!ep) return null;
    const parsed = parseEpNums(ep.id);
    if (parsed.se) return parsed.se;
    const found = it?.seasons?.find((s) => s.episodes.some((e) => e.id === ep.id));
    return found?.n ?? seasonN;
  }

  async function resolveNextInline(it: Item, curSeason: number | null, curEp: Episode | undefined) {
    if (!curEp || !it.seasons?.length) {
      nextUp.set(null);
      return;
    }
    const sNum = curSeason ?? seasonOfEpisode(it, curEp) ?? it.seasons[0].n;
    const curS = it.seasons.find((s) => s.n === sNum);
    const sEps = curS?.episodes.length ? curS.episodes : await catalog.season(it, sNum).catch(() => [] as Episode[]);
    const idx = sEps.findIndex((e) => e.id === curEp.id || e.n === curEp.n);
    if (idx >= 0 && idx + 1 < sEps.length) {
      const nx = sEps[idx + 1];
      nextUp.set({
        title: `E${nx.n} · ${nx.title}`,
        go: () => {
          seasonN = sNum;
          void openMedia({ item: it, season: sNum, episode: nx });
        }
      });
      return;
    }
    const nextSeason = it.seasons.find((s) => s.n > sNum);
    if (nextSeason) {
      const nextEps = nextSeason.episodes.length ? nextSeason.episodes : await catalog.season(it, nextSeason.n).catch(() => [] as Episode[]);
      if (nextEps.length) {
        const nx = nextEps[0];
        nextUp.set({
          title: `S${nextSeason.n} E${nx.n} · ${nx.title}`,
          go: () => {
            seasonN = nextSeason.n;
            void openMedia({ item: it, season: nextSeason.n, episode: nx });
          }
        });
        return;
      }
    }
    nextUp.set(null);
  }

  async function openMedia(opts: { item: Item; season?: number | null; episode?: Episode }) {
    const { item: targetItem, season = null, episode } = opts;
    playingInline = true;
    activeSeason = season;
    activeEpisode = episode;
    heroStageEl?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });

    const wantedKey = episode ? `${targetItem.id}#${episode.id}` : targetItem.id;
    const snap = session.snapshot;
    if (snap.media?.key === wantedKey && snap.mode !== 'hidden') {
      activeMedia = snap.media;
      resolvingInline = false;
      session.expand();
      void resolveNextInline(targetItem, season, episode);
      return;
    }

    resolvingInline = true;
    try {
      const prepared = await prepareMedia(targetItem, episode);
      const sub = episode ? formatSeriesSubtitle(season ?? undefined, episode.n, episode.title) : undefined;
      const m: Media = sub ? { ...prepared, item: targetItem, episode, subtitle: sub } : { ...prepared, item: targetItem, episode };
      resolvedVariants = m.variants ?? (m.source ? [m.source] : resolvedVariants);
      activeMedia = m;
      playKey = m.key;
      void resolveNextInline(targetItem, season, episode);
      void session.play(m);
    } finally {
      resolvingInline = false;
    }
  }

  function exitInline() {
    playingInline = false;
    activeMedia = undefined;
    nextUp.set(null);
    void session.stop();
  }

  // Load title details + concurrent T=0ms shimmer stream pre-warming
  $effect(() => {
    const want = id;
    let alive = true;
    const ctl = new AbortController();
    let warmedKey = '';

    // Switching from a previously playing inline title on `/app/title?id=A` to `/app/title?id=B`
    if (mountedId && mountedId !== want && playingInline) {
      const snap = session.snapshot;
      if (snap.media?.item.id === mountedId && !autoPlayQuery) {
        session.minimize();
      }
      playingInline = false;
      activeMedia = undefined;
      activeEpisode = undefined;
      activeSeason = null;
      nextUp.set(null);
    }
    mountedId = want;

    const seed = handedOff(want) ?? catalog.peek(want);
    const slug = resolveHandoffSlug(want, slugQuery ?? seed?.slug);
    preview = seed;
    item = undefined;
    status = 'loading';
    seasonN = null;
    episodes = null;
    related = null;
    expanded = false;
    batchOpen = false;
    movieOptionsOpen = false;
    movieDetailsOpen = false;
    resolvedVariants = [];
    target = undefined;

    if (!want) {
      status = 'missing';
      return;
    }
    if (adultLocked) return;

    catalog.hintSlug(want, slug);

    // Adopt active session if this exact title is already playing (e.g. expanded from MiniPlayer).
    const existingSnap = session.snapshot;
    if (existingSnap.media?.item.id === want && existingSnap.mode !== 'hidden') {
      playingInline = true;
      activeMedia = existingSnap.media;
      activeEpisode = existingSnap.media.episode;
      activeSeason = seasonOfEpisode(seed, existingSnap.media.episode);
      session.expand();
    }

    // 1) CONCURRENT SHIMMER PRE-WARM (runs immediately at T=0ms alongside catalog.item!)
    (async () => {
      const resumeEpId = !epQuery && !sQuery && !eQuery ? await resume.lastEpisode(activeProfileId(), want) : undefined;
      if (!alive) return;
      const prov = buildProvisionalTarget(want, seed, slug, sQuery, eQuery, epQuery ?? resumeEpId ?? null);
      if (!prov) return;
      const m = await prepareMedia(prov.item, prov.episode, ctl.signal);
      if (!alive || ctl.signal.aborted) return;
      warmedKey = m.key;
      playKey = m.key;
      resolvedVariants = m.variants ?? (m.source ? [m.source] : []);
      resumePos = await resume.startFor(activeProfileId(), want, prov.episode?.id);
      if (!alive || ctl.signal.aborted) return;

      if (autoPlayQuery && !playingInline) {
        playingInline = true;
        activeSeason = prov.season;
        activeEpisode = prov.episode;
        activeMedia = m;
        void session.play(m);
      } else if (settings.s.preloadDetails && !playingInline) {
        void preloader.preload(m);
      }
    })();

    // 2) CONCURRENT FULL DETAILS FETCH + MULTI-LAYER FALLBACK (never "Title not found" if summary/search/stub exists)
    (async () => {
      let resolvedItem = await catalog.item(want).catch(() => undefined);
      if (!alive) return;

      if (!resolvedItem) {
        const stub = await downloads.offlineStub(want, epQuery ?? undefined).catch(() => undefined);
        if (!alive) return;
        if (stub) resolvedItem = stub.item;
      }

      if (!resolvedItem) {
        const q = seed?.title || slugToSearchWords(slug);
        if (q) {
          const hits = await catalog.remoteSearch(q, ctl.signal).catch(() => [] as Item[]);
          if (!alive) return;
          const normQ = q.toLowerCase();
          const hit =
            hits.find((h) => h.id === want || (slug && h.slug === slug)) ??
            hits.find((h) => h.title.toLowerCase() === normQ) ??
            hits[0];
          if (hit) {
            resolvedItem = (await catalog.item(hit.id).catch(() => undefined)) ?? hit;
            if (!alive) return;
          }
        }
      }

      if (!resolvedItem && seed) {
        resolvedItem = ensurePlayableSummary(seed, slug);
      }

      if (!resolvedItem) {
        status = typeof navigator !== 'undefined' && navigator.onLine === false ? 'error' : 'missing';
        return;
      }

      if (resolvedItem.slug) rememberHandoffSlug(resolvedItem.id, resolvedItem.slug);
      item = resolvedItem;
      status = 'ready';

      if (resolvedItem.seasons?.length) {
        const parsed = parseEpNums(epQuery);
        const wantSeason =
          parsed.se ??
          sQuery ??
          (epQuery ? resolvedItem.seasons.find((s) => s.episodes.some((e) => e.id === epQuery))?.n : undefined) ??
          resolvedItem.seasons[0].n;
        seasonN = resolvedItem.seasons.some((s) => s.n === wantSeason) ? wantSeason : resolvedItem.seasons[0].n;
      }

      catalog.moreLikeThis(resolvedItem).then(
        (r) => alive && (related = r),
        () => alive && (related = [])
      );
    })();

    return () => {
      alive = false;
      ctl.abort();
      const k = warmedKey;
      if (k) setTimeout(() => preloader.cancel(k), 3000);
    };
  });

  // Load active season episodes
  $effect(() => {
    const it = item;
    const n = seasonN;
    if (!it || n === null) return;
    let alive = true;
    episodes = null;
    catalog.season(it, n).then(
      (eps) => alive && (episodes = eps),
      () => alive && (episodes = [])
    );
    return () => {
      alive = false;
    };
  });

  // Sync resolved item/episodes with preloader or active inline playback (?play=1)
  $effect(() => {
    const it = item;
    const eps = episodes;
    if (adultLocked || !it || (it.seasons?.length && eps === null)) return;
    let alive = true;
    let key = '';
    (async () => {
      const parsed = parseEpNums(epQuery);
      const last = !epQuery && !eQuery && it.seasons?.length ? await resume.lastEpisode(activeProfileId(), it.id) : undefined;
      if (!alive) return;
      const chosenEp =
        (epQuery && eps?.find((e) => e.id === epQuery)) ||
        ((parsed.ep ?? eQuery) && eps?.find((e) => e.n === (parsed.ep ?? eQuery))) ||
        (last && eps?.find((e) => e.id === last)) ||
        eps?.[0];
      target = chosenEp;
      resumePos = await resume.startFor(activeProfileId(), it.id, chosenEp?.id);
      if (!alive) return;

      if (autoPlayQuery && !playingInline) {
        await openMedia({ item: it, season: seasonN, episode: chosenEp });
        return;
      }
      if (playingInline && activeMedia && activeMedia.item.id === it.id) {
        // Upgrade provisional shimmer media metadata with full title/episode subtitle & next-up
        const fullEp = activeEpisode ? (eps?.find((e) => e.id === activeEpisode?.id || e.n === activeEpisode?.n) ?? activeEpisode) : chosenEp;
        activeEpisode = fullEp;
        activeSeason = seasonN;
        void resolveNextInline(it, seasonN, fullEp);
        return;
      }
      if (!settings.s.preloadDetails || playingInline) return;
      const m = await prepareMedia(it, chosenEp);
      if (!alive) return;
      resolvedVariants = m.variants ?? (m.source ? [m.source] : resolvedVariants);
      key = m.key;
      playKey = m.key;
      void preloader.preload(m);
    })();
    return () => {
      alive = false;
      const k = key;
      if (k) setTimeout(() => preloader.cancel(k), 3000);
    };
  });

  const shown = $derived(item ?? preview);
  const color = $derived(shown ? colorOf(shown) : null);

  $effect(() => {
    ensureColor(shown);
    tint.follow(color);
  });

  const fmt = (sec?: number) => {
    if (!sec) return '';
    const m = Math.round(sec / 60);
    return m >= 60 ? `${Math.floor(m / 60)}h ${m % 60}m` : `${m}m`;
  };

  const badges = $derived.by(() => {
    if (!shown) return { has4k: false, ratingLabel: null, dubs: [] as string[], storyGenres: [] as string[] };
    const baseBadges = extractTitleBadges(shown, resolvedVariants);
    const extraDubs = (shown as Item & { dubs?: readonly { languageName?: string }[] }).dubs;
    if (!extraDubs?.length) return baseBadges;
    const mergedDubs = [...baseBadges.dubs];
    for (const d of extraDubs) {
      const name = d.languageName?.trim();
      if (name && name !== 'Original' && !mergedDubs.includes(name)) mergedDubs.push(name);
    }
    return { ...baseBadges, dubs: mergedDubs };
  });

  const facts = $derived.by(() => {
    const it = shown;
    if (!it) return [] as string[];
    const out: string[] = [];
    if (it.year) out.push(String(it.year));
    if (it.maturity) out.push(it.maturity);
    if (it.seasons?.length) {
      out.push(`${it.seasons.length} ${it.seasons.length > 1 ? 'Seasons' : 'Season'}`);
    } else if (it.durationSec) {
      out.push(fmt(it.durationSec));
    }
    if (it.country) out.push(it.country);
    return out;
  });

  const firstEpisode = $derived(episodes?.[0]);
  const playEp = $derived(activeEpisode ?? target ?? firstEpisode);
  const isSeries = $derived(shown?.type === 'SERIES' || (item?.seasons?.length ?? 0) > 0);
  const dlMedia = $derived(item && !isSeries ? mediaFor(item, undefined) : undefined);
  const movieDlRecord = $derived(dlMedia ? downloads.get(dlMedia.key) : undefined);
  const movieDlPct = $derived(
    movieDlRecord
      ? movieDlRecord.kind === 'adaptive'
        ? (movieDlRecord.fraction ?? 0) * 100
        : percent(movieDlRecord.doneBytes, movieDlRecord.totalBytes || movieDlRecord.estimatedBytes)
      : 0
  );

  /** Green dot (#22C55E) lights ONLY when stream is ready (never shows "Ready" or "Warming..." text). */
  const streamReady = $derived($readiness[playKey] === 'ready' || (playingInline && !!activeMedia && !$sess.loading));

  const forYouRow = $derived<Row | null>(
    related?.length ? { id: 'for-you', title: 'For You', style: 'POSTER', itemIds: related.map((r) => r.id) } : null
  );

  function triggerPrimaryPlay() {
    const it = item ?? (shown ? ensurePlayableSummary(shown, resolveHandoffSlug(shown.id, slugQuery)) : undefined);
    if (!it) return;
    void openMedia({ item: it, season: seasonN, episode: isSeries ? playEp : undefined });
  }

  function triggerDownload() {
    if (isSeries) {
      if (item) batchOpen = true;
      return;
    }
    if (!dlMedia || !canDownload(dlMedia)) return;
    if (!movieDlRecord || movieDlRecord.status === 'canceled') movieOptionsOpen = true;
    else movieDetailsOpen = true;
  }

  function episodeMedia(it: Item, ep: Episode) {
    const m = mediaFor(it, ep);
    return {
      ...m,
      subtitle: formatSeriesSubtitle(seasonN ?? undefined, ep.n, ep.title)
    };
  }

  function fallbackImgToRaw(e: Event, raw: string | undefined) {
    if (!raw || !/^https?:\/\//i.test(raw)) return;
    const el = e.currentTarget;
    if (!(el instanceof HTMLImageElement) || !el.srcset) return;
    el.removeAttribute('srcset');
    el.removeAttribute('sizes');
    el.src = raw;
  }
</script>

<svelte:head><title>{shown?.title ?? 'Details'} · Cinetor</title></svelte:head>

{#if adultLocked}
  <AdultLock><span></span></AdultLock>
{:else if status === 'missing' && !shown}
  <section class="state glass">
    <h1>Title not found</h1>
    <p>It may have been removed, or the link is incomplete.</p>
    <a class="btn btn-primary" href="{base}/app">Back to Home</a>
  </section>
{:else if status === 'error' && !shown}
  <section class="state glass" role="alert">
    <h1>Couldn't load this title</h1>
    <a class="btn btn-ghost" href={page.url.href}>Retry</a>
  </section>
{:else if !shown}
  <div aria-busy="true" aria-label="Loading details">
    <SkeletonDetails />
    <div class="eps-sk"><SkeletonEpisodeList count={4} /></div>
  </div>
{:else}
  {@const rawHeroArt = shown.backdrop || shown.poster}
  {@const heroArt = imgFor(rawHeroArt, 'hero')}
  <article class="details" style:--item={color}>
    <!-- Top 16:9 Media Stage: transforms IN PLACE from artwork thumbnail into the live inline Player -->
    <div bind:this={heroStageEl} class="hero-stage" class:is-playing={playingInline}>
      {#if playingInline && activeMedia}
        <Player
          media={activeMedia}
          item={shown}
          season={activeSeason}
          episode={activeEpisode}
          onBack={exitInline}
          inline={true}
          onExitInline={exitInline}
        />
      {:else if playingInline || resolvingInline}
        <div class="stage-loading" aria-busy="true" aria-label="Starting playback">
          <img
            class="stage-art dim"
            referrerpolicy="no-referrer"
            sizes={heroArt.sizes}
            srcset={heroArt.srcset}
            src={heroArt.src}
            alt=""
            decoding="async"
            onerror={(e) => fallbackImgToRaw(e, rawHeroArt)}
          />
          <span class="stage-spin" aria-hidden="true"></span>
        </div>
      {:else}
        <button
          type="button"
          class="stage-preview"
          style:view-transition-name={SHARED_NAME}
          aria-label="Play {shown.title}"
          onclick={triggerPrimaryPlay}
        >
          <img
            class="stage-art"
            referrerpolicy="no-referrer"
            sizes={heroArt.sizes}
            srcset={heroArt.srcset}
            src={heroArt.src}
            alt="{shown.title} artwork"
            decoding="async"
            fetchpriority="high"
            onerror={(e) => fallbackImgToRaw(e, rawHeroArt)}
          />
          <span class="stage-scrim" aria-hidden="true"></span>
          <span class="stage-play glass" aria-hidden="true">
            <svg width="28" height="28" viewBox="0 0 24 24"><path fill="currentColor" d="M8 5.2v13.6L19 12z" /></svg>
          </span>
        </button>
      {/if}
    </div>

    <!-- Header Metadata -->
    <header class="head">
      <h1>{shown.title}</h1>
      {#if shown.altTitle}<p class="alt">{shown.altTitle}</p>{/if}

      <div class="badge-row">
        <a class="pill pill-space" href={spaceHref(shown.space)}>
          {shown.space === 'HOME' ? (shown.type === 'MOVIE' ? 'Movie' : 'Series') : shown.space}
        </a>
        {#if badges.ratingLabel}
          <span class="pill pill-rating" aria-label="Rating {badges.ratingLabel}">
            <svg class="star" width="12" height="12" viewBox="0 0 24 24" aria-hidden="true">
              <path fill="currentColor" d="M12 2.5l2.9 6 6.6.9-4.8 4.6 1.2 6.5L12 17.4 6.1 20.5l1.2-6.5L2.5 9.4l6.6-.9z" />
            </svg>
            {badges.ratingLabel}
          </span>
        {/if}
        {#if badges.has4k}
          <span class="pill pill-uhd">4K UHD</span>
        {/if}
        {#each badges.dubs as dub (dub)}
          <span class="pill pill-dub">{dub}</span>
        {/each}
      </div>

      {#if facts.length}
        <ul class="meta">
          {#each facts as m (m)}<li>{m}</li>{/each}
        </ul>
      {/if}

      {#if badges.storyGenres.length}
        <ul class="genres">
          {#each badges.storyGenres as g (g)}<li>{g}</li>{/each}
        </ul>
      {/if}
    </header>

    <!-- Strictly 3 Action Buttons: Play (green dot ONLY when ready) · Download · My List -->
    <div class="actions">
      <button type="button" class="btn btn-primary play" onclick={triggerPrimaryPlay}>
        <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden="true">
          <path fill="currentColor" d="M8 5.5v13l10.5-6.5z" />
        </svg>
        <span>{resumePos > 0 && !playingInline ? 'Resume' : 'Play'}{isSeries && playEp ? ` E${playEp.n}` : ''}</span>
        {#if streamReady}
          <span class="ready-dot" aria-hidden="true"></span>
        {/if}
      </button>

      <button
        type="button"
        class="btn btn-ghost dl-action"
        disabled={!isSeries && (!dlMedia || !canDownload(dlMedia))}
        onclick={triggerDownload}
      >
        {#if !isSeries && movieDlRecord?.status === 'completed'}
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" aria-hidden="true">
            <path d="M5 12.5l4.5 4.5L19 7.5" />
          </svg>
          <span>Downloaded</span>
        {:else if !isSeries && movieDlRecord && (movieDlRecord.status === 'running' || movieDlRecord.status === 'queued' || movieDlRecord.status === 'paused')}
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
            <path d="M12 4v11m0 0l-4.5-4.5M12 15l4.5-4.5M5 19.5h14" />
          </svg>
          <span>{movieDlRecord.status === 'queued' ? 'Queued' : `${Math.floor(movieDlPct)}%`}</span>
        {:else}
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
            <path d="M12 4v11m0 0l-4.5-4.5M12 15l4.5-4.5M5 19.5h14" />
          </svg>
          <span>Download</span>
        {/if}
      </button>

      <button type="button" class="btn btn-ghost" aria-pressed={myList.has(shown)} onclick={() => shown && myList.toggle(shown)}>
        {#if myList.has(shown)}
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" aria-hidden="true">
            <path d="M5 12.5l4.5 4.5L19 7.5" />
          </svg>
          <span>My List</span>
        {:else}
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" aria-hidden="true">
            <path d="M12 5v14M5 12h14" />
          </svg>
          <span>My List</span>
        {/if}
      </button>
    </div>

    {#if shown.synopsis}
      <p class="syn" class:expanded>{shown.synopsis}</p>
      {#if shown.synopsis.length > 180}
        <button type="button" class="more" onclick={() => (expanded = !expanded)} aria-expanded={expanded}>
          {expanded ? 'Show less' : 'Show more'}
        </button>
      {/if}
    {/if}

    {#if item?.studio}
      <p class="studio-line">
        <span>{item.type === 'MOVIE' ? 'Studio' : 'Network'}</span>
        <b>{item.studio}</b>
      </p>
    {/if}

    <!-- Seasons & Episodes (inline switching + active episode highlight) -->
    {#if status === 'loading' && shown.type === 'SERIES'}
      <h2 class="section-title">Episodes</h2>
      <SkeletonEpisodeList count={4} />
    {:else if item?.seasons?.length}
      <section aria-label="Seasons and Episodes">
        <h2 class="section-title">Episodes</h2>
        {#if item.seasons.length > 1}
          <div class="h-scroll" role="group" aria-label="Seasons">
            {#each item.seasons as s (s.n)}
              <button type="button" class="chip" aria-pressed={s.n === seasonN} onclick={() => (seasonN = s.n)}>
                {s.title ?? (s.n === 0 ? 'Specials' : `Season ${s.n}`)}
              </button>
            {/each}
          </div>
        {/if}
        {#if episodes === null}
          <SkeletonEpisodeList count={Math.min(5, item.seasons.find((s) => s.n === seasonN)?.episodeCount ?? 5)} />
        {:else if !episodes.length}
          <p class="muted">No episode info yet.</p>
        {:else}
          <ol class="eps">
            {#each episodes as ep (ep.id)}
              {@const still = imgFor(ep.still, 'still')}
              {@const isPlayingEp = (playingInline && activeEpisode?.id === ep.id) || $sess.media?.episode?.id === ep.id}
              <li class="ep-card" class:playing={isPlayingEp}>
                <button
                  type="button"
                  class="ep"
                  aria-current={isPlayingEp ? 'true' : undefined}
                  onclick={() => item && void openMedia({ item, season: seasonN, episode: ep })}
                >
                  <div class="still">
                    <img
                      referrerpolicy="no-referrer"
                      sizes={still.sizes}
                      srcset={still.srcset}
                      src={still.src}
                      alt=""
                      loading="lazy"
                      decoding="async"
                      width="320"
                      height="180"
                      onerror={(e) => fallbackImgToRaw(e, ep.still)}
                    />
                    <span class="still-play" aria-hidden="true">
                      <svg width="18" height="18" viewBox="0 0 24 24"><path fill="currentColor" d="M8 5.5v13l10.5-6.5z" /></svg>
                    </span>
                    {#if isPlayingEp}<span class="now-pill">Playing</span>{/if}
                    {#if ep.durationSec}<span class="dur">{fmt(ep.durationSec)}</span>{/if}
                  </div>
                  <div class="ep-txt">
                    <h3><span class="ep-n">E{String(ep.n).padStart(2, '0')}</span> {ep.title}</h3>
                    {#if ep.synopsis}<p>{ep.synopsis}</p>{/if}
                  </div>
                </button>
                <DownloadButton media={episodeMedia(item, ep)} compact />
              </li>
            {/each}
          </ol>
        {/if}
      </section>
    {/if}

    <!-- Cast (clickable Cast Filmography Sheet via Credits.svelte) -->
    {#if status === 'loading'}
      <div class="credits-sk"><SkeletonBox width="60%" height="14px" /><SkeletonBox width="40%" height="14px" /></div>
    {:else if shown.cast?.length}
      <section class="cast-section" aria-label="Cast">
        <h2 class="section-title">Cast</h2>
        <Credits cast={shown.cast} />
      </section>
    {/if}

    <!-- For You (PosterRow with titles underneath every thumbnail) -->
    {#if related === null && status !== 'missing'}
      <SkeletonPosterRow />
    {:else if forYouRow && related?.length}
      <PosterRow row={forYouRow} items={related} />
    {/if}

    {#if !isVideoSpace(shown.space)}
      <p class="muted iso">Stays inside this space — never shown in global search or history.</p>
    {/if}
  </article>

  {#if isSeries && item}
    <BatchSeriesDownloadSheet bind:open={batchOpen} {item} initialSeason={seasonN} />
  {/if}
  {#if !isSeries && dlMedia}
    <DownloadOptionsSheet bind:open={movieOptionsOpen} media={dlMedia} onstarted={() => (movieDetailsOpen = false)} />
  {/if}
  {#if !isSeries && movieDlRecord}
    <DownloadDetailsSheet bind:open={movieDetailsOpen} id={movieDlRecord.id} />
  {/if}
{/if}

<style>
  .details {
    max-width: 1160px;
    margin: 0 auto;
    padding-bottom: 32px;
  }

  /* 16:9 Top Media Stage: zero layout shift between artwork thumbnail and inline video player */
  .hero-stage {
    position: relative;
    width: 100%;
    aspect-ratio: 16 / 9;
    background: #000;
    overflow: hidden;
  }
  .hero-stage.is-playing {
    position: sticky;
    top: calc(var(--header-h, 0px) + var(--safe-t, 0px) + var(--titlebar-h, 0px));
    z-index: 25;
    box-shadow: 0 18px 42px -16px rgb(0 0 0 / 0.82);
  }
  @media (min-width: 900px) {
    .hero-stage {
      margin: 12px 16px 0;
      width: calc(100% - 32px);
      border-radius: var(--radius-lg);
      box-shadow:
        0 24px 56px -22px color-mix(in srgb, var(--item, var(--tint)) 55%, rgb(0 0 0 / 0.85)),
        inset 0 0 0 1px var(--glass-stroke);
    }
    .hero-stage.is-playing {
      position: relative;
    }
  }

  .stage-preview {
    position: relative;
    display: block;
    width: 100%;
    height: 100%;
    padding: 0;
    border: 0;
    background: color-mix(in srgb, var(--item, var(--tint)) 34%, var(--surface));
    cursor: pointer;
    overflow: hidden;
  }
  .stage-art {
    width: 100%;
    height: 100%;
    object-fit: cover;
    transition: transform calc(var(--dur-medium) * var(--dur-scale)) var(--ease-emphasized);
  }
  .stage-art.dim {
    opacity: 0.42;
  }
  .stage-preview:hover .stage-art,
  .stage-preview:focus-visible .stage-art {
    transform: scale(1.02);
  }
  .stage-scrim {
    position: absolute;
    inset: 0;
    background:
      linear-gradient(0deg, color-mix(in srgb, var(--bg) 92%, transparent) 0%, transparent 48%),
      radial-gradient(circle at 50% 50%, transparent 25%, rgb(0 0 0 / 0.36) 100%);
    pointer-events: none;
  }
  .stage-play {
    position: absolute;
    inset: 0;
    margin: auto;
    width: 64px;
    height: 64px;
    border-radius: 50%;
    display: grid;
    place-items: center;
    color: #ffffff;
    background: rgb(0 0 0 / 0.54);
    box-shadow:
      0 12px 32px -8px rgb(0 0 0 / 0.72),
      inset 0 0 0 1px rgb(255 255 255 / 0.26);
    transition: transform calc(var(--dur-short) * var(--dur-scale)) var(--ease-emphasized);
  }
  .stage-preview:hover .stage-play,
  .stage-preview:focus-visible .stage-play {
    transform: scale(1.08);
  }

  .stage-loading {
    position: relative;
    width: 100%;
    height: 100%;
    display: grid;
    place-items: center;
    background: #000;
  }
  .stage-loading .stage-art {
    position: absolute;
    inset: 0;
  }
  .stage-spin {
    position: relative;
    z-index: 2;
    width: 42px;
    height: 42px;
    border-radius: 50%;
    border: 3px solid rgb(255 255 255 / 0.22);
    border-top-color: var(--accent);
    animation: spin 0.8s linear infinite;
  }
  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }

  .head {
    padding: 16px 16px 0;
  }
  h1 {
    margin: 0 0 4px;
    font-size: clamp(1.45rem, 4.6vw, 2.5rem);
    line-height: 1.08;
    letter-spacing: -0.02em;
    font-weight: 850;
    text-wrap: balance;
  }
  .alt {
    margin: 0 0 8px;
    color: var(--text-secondary);
    font-size: 0.85rem;
  }
  .badge-row {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 6px;
    margin: 8px 0 0;
  }
  .pill {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    min-height: 24px;
    padding: 2px 9px;
    border-radius: var(--radius-pill);
    font-size: 0.72rem;
    font-weight: 750;
    line-height: 1;
    letter-spacing: 0.02em;
    font-variant-numeric: tabular-nums;
  }
  .pill-space {
    text-decoration: none;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    color: color-mix(in srgb, var(--accent) 82%, var(--text-primary));
    background: color-mix(in srgb, var(--accent) 14%, var(--glass-fill));
    box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--accent) 34%, var(--glass-stroke));
  }
  .pill-rating {
    color: var(--text-primary);
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
  }
  .star {
    color: var(--warning);
  }
  .pill-uhd {
    border-radius: 6px;
    padding: 2px 7px;
    color: var(--text-primary);
    background: transparent;
    box-shadow: inset 0 0 0 1.25px color-mix(in srgb, var(--text-primary) 55%, var(--glass-stroke));
    letter-spacing: 0.05em;
  }
  .pill-dub {
    color: var(--text-primary);
    background: color-mix(in srgb, var(--tint) 16%, var(--glass-fill));
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
  }
  .meta,
  .genres {
    list-style: none;
    margin: 8px 0 0;
    padding: 0;
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    font-variant-numeric: tabular-nums;
  }
  .meta li {
    font-size: 0.8rem;
    color: var(--text-secondary);
  }
  .meta li + li::before {
    content: '·';
    margin-right: 6px;
  }
  .genres li {
    padding: 3px 10px;
    border-radius: var(--radius-pill);
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
    font-size: 0.74rem;
    color: var(--text-secondary);
  }

  .actions {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    padding: 16px 16px 4px;
  }
  .play {
    min-width: 148px;
  }
  .ready-dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: #22c55e;
    box-shadow: 0 0 8px rgb(34 197 94 / 0.85);
    flex: none;
  }
  .dl-action:disabled {
    opacity: 0.55;
    cursor: not-allowed;
  }

  .syn {
    margin: 12px 16px 0;
    max-width: 72ch;
    color: color-mix(in srgb, var(--text-primary) 88%, transparent);
    display: -webkit-box;
    -webkit-line-clamp: 3;
    line-clamp: 3;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }
  .syn.expanded {
    display: block;
  }
  .more {
    margin: 4px 16px 0;
    padding: 2px 0;
    border: 0;
    background: none;
    color: var(--accent);
    font-size: 0.84rem;
    font-weight: 700;
    cursor: pointer;
  }
  .studio-line {
    margin: 12px 16px 0;
    display: flex;
    gap: 8px;
    font-size: 0.82rem;
    color: var(--text-secondary);
  }
  .studio-line span {
    color: var(--text-tertiary);
  }
  .studio-line b {
    font-weight: 650;
  }

  .credits-sk {
    margin: 14px 16px 0;
    display: grid;
    gap: 8px;
  }
  .cast-section {
    margin-top: 8px;
  }

  .eps {
    list-style: none;
    margin: 0;
    padding: 4px 16px;
    display: grid;
    gap: 10px;
  }
  .ep-card {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 8px;
    border-radius: var(--radius-lg);
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
    transition: background calc(var(--dur-short) * var(--dur-scale)) var(--ease-standard);
  }
  .ep-card:hover {
    background: color-mix(in srgb, var(--tint) 6%, var(--glass-fill));
  }
  .ep-card.playing {
    background: color-mix(in srgb, var(--accent) 10%, var(--glass-fill));
    box-shadow: inset 0 0 0 1.5px color-mix(in srgb, var(--accent) 65%, var(--glass-stroke));
  }
  .ep {
    flex: 1;
    min-width: 0;
    padding: 0;
    border: 0;
    background: transparent;
    text-align: left;
    cursor: pointer;
    display: grid;
    grid-template-columns: clamp(118px, 34vw, 188px) 1fr;
    gap: 12px;
    align-items: center;
    border-radius: var(--radius-md);
  }
  .ep:hover .still,
  .ep:focus-visible .still {
    box-shadow: 0 0 0 2px var(--tint);
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
  .still-play {
    position: absolute;
    inset: 0;
    margin: auto;
    width: 34px;
    height: 34px;
    border-radius: 50%;
    display: grid;
    place-items: center;
    background: rgb(0 0 0 / 0.58);
    color: #ffffff;
    opacity: 0.85;
    transition: transform calc(var(--dur-short) * var(--dur-scale)) var(--ease-emphasized);
  }
  .ep:hover .still-play,
  .ep:focus-visible .still-play {
    transform: scale(1.08);
    opacity: 1;
  }
  .now-pill {
    position: absolute;
    left: 6px;
    bottom: 6px;
    padding: 2px 7px;
    border-radius: 6px;
    background: var(--accent);
    color: var(--on-accent);
    font-size: 0.68rem;
    font-weight: 800;
  }
  .dur {
    position: absolute;
    right: 6px;
    bottom: 6px;
    padding: 1px 6px;
    border-radius: 6px;
    background: rgb(0 0 0 / 0.72);
    color: #ffffff;
    font-size: 0.7rem;
    font-variant-numeric: tabular-nums;
  }
  .ep-txt h3 {
    margin: 0 0 4px;
    font-size: 0.92rem;
    display: flex;
    align-items: baseline;
    gap: 6px;
  }
  .ep-n {
    font-size: 0.76rem;
    font-weight: 800;
    font-variant-numeric: tabular-nums;
    color: var(--accent);
  }
  .ep-txt p {
    margin: 0;
    font-size: 0.8rem;
    color: var(--text-secondary);
    display: -webkit-box;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }
  .eps-sk {
    margin-top: 18px;
  }
  .muted {
    margin: 8px 16px;
    color: var(--text-secondary);
  }
  .iso {
    margin-top: 24px;
    font-size: 0.75rem;
  }
  .state {
    margin: 40px 16px;
    padding: 28px;
    text-align: center;
  }
  .state p {
    color: var(--text-secondary);
  }
</style>
