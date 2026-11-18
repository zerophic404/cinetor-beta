<script lang="ts">
  // This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer,
  // scrape or hack anything without Mythbrix's explicit permission.
  import { onMount } from 'svelte';
  import { page } from '$app/state';
  import { base } from '$app/paths';
  import { catalog } from '$lib/data/app-catalog';
  import { colorOf, ensureColor } from '$lib/data/live-color.svelte';
  import { activeProfileId, myList } from '$lib/data/library.svelte';
  import { isVideoSpace, type Episode, type Item, type Source } from '$lib/data/types';
  import { SkeletonBox, SkeletonDetails, SkeletonEpisodeList } from '$lib/design/skeleton';
  import { tint } from '$lib/design/tint.svelte';
  import Poster from '$lib/components/Poster.svelte';
  import { imgFor } from '$lib/ui/img';
  import { handedOff, SHARED_NAME, watchHref } from '$lib/ui/handoff';
  import { spaceHref } from '$lib/nav';
  import { mediaFor, prepareMedia, preloader, resume } from '$lib/player/controller';
  import BatchSeriesDownloadSheet from '$lib/download/BatchSeriesDownloadSheet.svelte';
  import DownloadButton from '$lib/download/DownloadButton.svelte';
  import { extractTitleBadges, formatSeriesSubtitle } from '$lib/download/batch';
  import { downloads } from '$lib/download/manager.svelte';
  import { spaceForId } from '$lib/data/catalog';
  import { adult } from '$lib/adult/store.svelte';
  import AdultLock from '$lib/adult/AdultLock.svelte';
  import { settings } from '$lib/settings/store.svelte';

  const readiness = preloader.readiness;

  const id = $derived(page.url.searchParams.get('id') ?? '');
  /** 18+ titles stay behind the PIN gate (no metadata, no preload) until unlocked this session. */
  const adultLocked = $derived(spaceForId(id) === 'ADULT' && !adult.unlocked);

  /** Instant header (clicked card / TMDB list cache) while full details resolve. */
  let preview = $state.raw<Item | undefined>(undefined);
  let item = $state.raw<Item | undefined>(undefined);
  let status = $state<'loading' | 'ready' | 'missing' | 'error'>('loading');
  let seasonN = $state<number | null>(null);
  let episodes = $state.raw<Episode[] | null>(null);
  let related = $state.raw<Item[] | null>(null);
  let expanded = $state(false);
  let shared = $state('');
  let batchOpen = $state(false);
  let resolvedVariants = $state.raw<Source[]>([]);

  onMount(() => void downloads.init());

  $effect(() => {
    const want = id;
    let alive = true;
    preview = handedOff(want) ?? catalog.peek(want);
    item = undefined;
    status = 'loading';
    seasonN = null;
    episodes = null;
    related = null;
    expanded = false;
    batchOpen = false;
    resolvedVariants = [];
    if (!want) {
      status = 'missing';
      return;
    }
    catalog.hintSlug(want, page.url.searchParams.get('slug'));
    catalog.item(want).then(
      (it) => {
        if (!alive) return;
        item = it;
        status = it ? 'ready' : 'missing';
        if (it?.seasons?.length) seasonN = it.seasons[0].n;
        if (it)
          catalog.moreLikeThis(it).then(
            (r) => alive && (related = r),
            () => alive && (related = [])
          );
      },
      () => alive && (status = 'error')
    );
    return () => {
      alive = false;
    };
  });

  // Lazy seasons (TMDB) — gateway/mock seasons are inline.
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

  const badges = $derived(shown ? extractTitleBadges(shown, resolvedVariants) : { has4k: false, ratingLabel: null, dubs: [], storyGenres: [] });

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

  // Instant play: warm the shared <video> with the Play target (resume episode > first episode).
  let target = $state.raw<Episode | undefined>(undefined);
  let playKey = $state('');
  let resumePos = $state(0);
  $effect(() => {
    const it = item;
    const eps = episodes;
    if (adultLocked || !settings.s.preloadDetails || !it || (it.seasons?.length && eps === null)) return;
    let alive = true;
    let key = '';
    (async () => {
      const last = it.seasons?.length ? await resume.lastEpisode(activeProfileId(), it.id) : undefined;
      if (!alive) return;
      const ep = (last && eps?.find((e) => e.id === last)) || eps?.[0];
      target = ep;
      // Downloaded → warm the offline copy instead of the network stream.
      const m = await prepareMedia(it, ep);
      if (!alive) return;
      resolvedVariants = m.variants ?? (m.source ? [m.source] : []);
      key = m.key;
      playKey = m.key;
      resumePos = await resume.startFor(activeProfileId(), it.id, ep?.id);
      if (!alive) return;
      void preloader.preload(m);
    })();
    return () => {
      alive = false;
      const k = key;
      if (k) setTimeout(() => preloader.cancel(k), 3000);
    };
  });

  const playEp = $derived(target ?? firstEpisode);
  const isSeriesWithSeasons = $derived(item?.type === 'SERIES' && (item.seasons?.length ?? 0) > 0);
  const dlMedia = $derived(item && !isSeriesWithSeasons ? mediaFor(item, playEp) : undefined);
  const ready = $derived($readiness[playKey]);

  const seriesDlCount = $derived.by(() => {
    const it = item;
    if (!it || !isSeriesWithSeasons) return 0;
    return downloads.list.filter((r) => r.itemId === it.id && r.status !== 'canceled').length;
  });

  function episodeMedia(it: Item, ep: Episode) {
    const m = mediaFor(it, ep);
    return {
      ...m,
      subtitle: formatSeriesSubtitle(seasonN ?? undefined, ep.n, ep.title)
    };
  }

  async function share() {
    if (!shown) return;
    const url = location.href;
    try {
      if (navigator.share) await navigator.share({ title: shown.title, url });
      else {
        await navigator.clipboard.writeText(url);
        shared = 'Link copied';
        setTimeout(() => (shared = ''), 1800);
      }
    } catch {
      /* cancelled */
    }
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
  {@const rawBack = shown.backdrop}
  {@const rawPoster = shown.poster}
  {@const back = imgFor(rawBack, 'hero')}
  {@const poster = imgFor(rawPoster, 'poster')}
  <article class="details" style:--item={color}>
    <div class="backdrop" aria-hidden="true">
      <img
        referrerpolicy="no-referrer"
        sizes={back.sizes}
        srcset={back.srcset}
        src={back.src}
        alt=""
        decoding="async"
        fetchpriority="high"
        onerror={(e) => {
          const el = e.currentTarget;
          if (el.srcset && /^https?:\/\//i.test(rawBack)) {
            el.removeAttribute('srcset');
            el.removeAttribute('sizes');
            el.src = rawBack;
          }
        }}
      />
      <div class="wash"></div>
    </div>

    <header class="head">
      <div class="poster" style:view-transition-name={SHARED_NAME}>
        <img
          referrerpolicy="no-referrer"
          sizes={poster.sizes}
          srcset={poster.srcset}
          src={poster.src}
          alt="{shown.title} poster"
          decoding="async"
          width="300"
          height="450"
          onerror={(e) => {
            const el = e.currentTarget;
            if (el.srcset && /^https?:\/\//i.test(rawPoster)) {
              el.removeAttribute('srcset');
              el.removeAttribute('sizes');
              el.src = rawPoster;
            }
          }}
        />
      </div>
      <div class="info">
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
      </div>
    </header>

    <div class="actions">
      <a class="btn btn-primary play" href={watchHref(shown.id, playEp?.id)}>
        <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M8 5.5v13l10.5-6.5z" /></svg>
        {resumePos > 0 ? 'Resume' : 'Play'}{playEp ? ` E${playEp.n}` : ''}
        {#if ready === 'ready'}
          <span class="ready" title="Ready — starts instantly"></span>
        {:else if ready === 'buffering' || ready === 'resolving'}
          <span class="ready warming" title="Preparing…"></span>
        {/if}
      </a>

      {#if isSeriesWithSeasons && item}
        <button type="button" class="btn glass glass-pill batch-dl" onclick={() => (batchOpen = true)}>
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
            <path d="M12 3v11m0 0l-4-4m4 4l4-4M4 17.5h16M7 21h10" />
          </svg>
          <span>Download Episodes</span>
          {#if seriesDlCount > 0}
            <span class="batch-count">{seriesDlCount}</span>
          {/if}
        </button>
      {:else if dlMedia}
        <DownloadButton media={dlMedia} />
      {:else if status === 'loading'}
        <SkeletonBox width="148px" height="44px" radius="var(--radius-pill)" />
      {/if}

      <button class="btn btn-ghost" aria-pressed={myList.has(shown)} onclick={() => shown && myList.toggle(shown)}>
        {#if myList.has(shown)}
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" aria-hidden="true"><path d="M5 12.5l4.5 4.5L19 7.5" /></svg>
          My List
        {:else}
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" aria-hidden="true"><path d="M12 5v14M5 12h14" /></svg>
          My List
        {/if}
      </button>

      <button class="btn btn-ghost icon" onclick={share} aria-label="Share">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M12 15V4m0 0L8 8m4-4l4 4M5 13v6h14v-6" /></svg>
      </button>
      {#if shared}<span class="toast" role="status">{shared}</span>{/if}
    </div>

    {#if shown.synopsis}
      <p class="syn" class:expanded>{shown.synopsis}</p>
      {#if shown.synopsis.length > 180}
        <button class="more" onclick={() => (expanded = !expanded)} aria-expanded={expanded}>{expanded ? 'Show less' : 'Show more'}</button>
      {/if}
    {/if}

    {#if status === 'loading'}
      <div class="credits-sk"><SkeletonBox width="60%" height="14px" /><SkeletonBox width="40%" height="14px" /></div>
    {:else if item && (item.cast?.length || item.studio)}
      <dl class="credits">
        {#if item.cast?.length}<dt>Cast</dt><dd>{item.cast.slice(0, 6).join(', ')}</dd>{/if}
        {#if item.studio}<dt>{item.type === 'MOVIE' ? 'Studio' : 'Network'}</dt><dd>{item.studio}</dd>{/if}
      </dl>
    {/if}

    {#if status === 'loading' && shown.type === 'SERIES'}
      <h2 class="section-title">Episodes</h2>
      <SkeletonEpisodeList count={4} />
    {:else if item?.seasons?.length}
      <section aria-label="Episodes">
        <div class="ep-head">
          <h2 class="section-title">Episodes</h2>
          {#if isSeriesWithSeasons}
            <button type="button" class="chip batch-inline" onclick={() => (batchOpen = true)}>
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                <path d="M12 4v10m0 0l-3.5-3.5M12 14l3.5-3.5M5 18.5h14" />
              </svg>
              Batch Download
            </button>
          {/if}
        </div>
        {#if item.seasons.length > 1}
          <div class="h-scroll" role="group" aria-label="Seasons">
            {#each item.seasons as s (s.n)}
              <button class="chip" aria-pressed={s.n === seasonN} onclick={() => (seasonN = s.n)}>
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
              <li class="ep-card">
                <a href={watchHref(shown.id, ep.id)} class="ep">
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
                      onerror={(e) => {
                        const el = e.currentTarget;
                        if (el.srcset && /^https?:\/\//i.test(ep.still)) {
                          el.removeAttribute('srcset');
                          el.removeAttribute('sizes');
                          el.src = ep.still;
                        }
                      }}
                    />
                    <span class="still-play" aria-hidden="true">
                      <svg width="18" height="18" viewBox="0 0 24 24"><path fill="currentColor" d="M8 5.5v13l10.5-6.5z" /></svg>
                    </span>
                    {#if ep.durationSec}<span class="dur">{fmt(ep.durationSec)}</span>{/if}
                  </div>
                  <div class="ep-txt">
                    <h3><span class="ep-n">E{String(ep.n).padStart(2, '0')}</span> {ep.title}</h3>
                    {#if ep.synopsis}<p>{ep.synopsis}</p>{/if}
                  </div>
                </a>
                <DownloadButton media={episodeMedia(item, ep)} compact />
              </li>
            {/each}
          </ol>
        {/if}
      </section>
    {/if}

    {#if related === null && status !== 'missing'}
      <h2 class="section-title">More Like This</h2>
      <div class="grid">{#each Array(6) as _, i (i)}<SkeletonBox aspect="2 / 3" radius="var(--radius-card)" />{/each}</div>
    {:else if related?.length}
      <section aria-label="More like this">
        <h2 class="section-title">More Like This</h2>
        <div class="grid">
          {#each related as r (r.id)}<Poster item={r} />{/each}
        </div>
      </section>
    {/if}

    {#if !isVideoSpace(shown.space)}
      <p class="muted iso">Stays inside this space — never shown in global search or history.</p>
    {/if}
  </article>

  {#if isSeriesWithSeasons && item}
    <BatchSeriesDownloadSheet bind:open={batchOpen} {item} initialSeason={seasonN} />
  {/if}
{/if}

<style>
  .details {
    position: relative;
    isolation: isolate;
    margin-top: calc(-12px - var(--safe-t));
    padding-top: clamp(120px, 34vw, 290px);
  }
  .backdrop {
    position: absolute;
    inset: 0 0 auto;
    height: clamp(260px, 62vw, 560px);
    z-index: -1;
    overflow: hidden;
    background: color-mix(in srgb, var(--item, var(--tint)) 40%, var(--bg));
  }
  .backdrop img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    opacity: 0.78;
  }
  .wash {
    position: absolute;
    inset: 0;
    background:
      linear-gradient(0deg, var(--bg) 4%, color-mix(in srgb, var(--bg) 58%, transparent) 52%, transparent),
      linear-gradient(0deg, color-mix(in srgb, var(--item, var(--tint)) 34%, transparent), transparent 72%);
  }
  :global([data-blur='all']) .backdrop img {
    filter: blur(2px) saturate(1.08);
    transform: scale(1.03);
  }
  .head {
    display: flex;
    gap: 16px;
    align-items: flex-end;
    padding: 0 16px;
  }
  .poster {
    flex: none;
    width: clamp(108px, 28vw, 200px);
    aspect-ratio: 2 / 3;
    border-radius: var(--radius-card);
    overflow: hidden;
    box-shadow:
      0 20px 42px -18px var(--item, var(--tint)),
      inset 0 0 0 1px var(--glass-stroke);
    background: var(--item, var(--surface));
  }
  .poster img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
  .info {
    min-width: 0;
  }
  h1 {
    margin: 0 0 4px;
    font-size: clamp(1.45rem, 5vw, 2.65rem);
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
    margin: 6px 0 0;
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
    position: relative;
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    padding: 18px 16px 4px;
  }
  .play {
    min-width: 150px;
  }
  .batch-dl {
    border-radius: var(--radius-pill);
    padding: 0 18px;
    background:
      linear-gradient(150deg, color-mix(in srgb, var(--accent) 16%, transparent), transparent 65%),
      var(--glass-fill);
    box-shadow:
      0 8px 24px -12px rgb(0 0 0 / 0.55),
      inset 0 0 0 1px color-mix(in srgb, var(--accent) 36%, var(--glass-stroke));
  }
  .batch-count {
    padding: 2px 7px;
    border-radius: var(--radius-pill);
    background: color-mix(in srgb, var(--accent) 22%, transparent);
    font-size: 0.72rem;
    font-weight: 800;
    font-variant-numeric: tabular-nums;
  }
  .ready {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: var(--success);
    box-shadow: 0 0 8px var(--success);
  }
  .ready.warming {
    background: color-mix(in srgb, var(--on-accent) 55%, transparent);
    box-shadow: none;
    animation: pulse 1s ease-in-out infinite alternate;
  }
  @keyframes pulse {
    to {
      opacity: 0.3;
    }
  }
  .icon {
    width: 44px;
    padding: 0;
  }
  .toast {
    align-self: center;
    font-size: 0.8rem;
    color: var(--text-secondary);
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
  .credits {
    margin: 14px 16px 0;
    display: grid;
    grid-template-columns: auto 1fr;
    gap: 4px 12px;
    font-size: 0.82rem;
  }
  .credits dt {
    color: var(--text-tertiary);
  }
  .credits dd {
    margin: 0;
    color: var(--text-secondary);
  }
  .credits-sk {
    margin: 14px 16px 0;
    display: grid;
    gap: 8px;
  }
  .ep-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding-right: 16px;
  }
  .batch-inline {
    gap: 6px;
    margin-top: 10px;
    font-size: 0.8rem;
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
  .ep {
    flex: 1;
    min-width: 0;
    display: grid;
    grid-template-columns: clamp(118px, 34vw, 188px) 1fr;
    gap: 12px;
    align-items: center;
    text-decoration: none;
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
  .grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(108px, 1fr));
    gap: 14px 12px;
    padding: 4px 16px;
  }
  .grid :global(.card) {
    width: auto;
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
