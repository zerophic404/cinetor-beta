<script lang="ts">
  import { page } from '$app/state';
  import { base } from '$app/paths';
  import { catalog } from '$lib/data/app-catalog';
  import { colorOf, ensureColor } from '$lib/data/live-color.svelte';
  import { myList } from '$lib/data/library.svelte';
  import { isVideoSpace, type Episode, type Item } from '$lib/data/types';
  import { SkeletonBox, SkeletonDetails, SkeletonEpisodeList } from '$lib/design/skeleton';
  import { tint } from '$lib/design/tint.svelte';
  import Poster from '$lib/components/Poster.svelte';
  import { imgFor } from '$lib/ui/img';
  import { handedOff, SHARED_NAME, watchHref } from '$lib/ui/handoff';
  import { spaceHref } from '$lib/nav';
  import { activeProfileId } from '$lib/data/library.svelte';
  import { mediaFor, preloader, resume } from '$lib/player/controller';

  const readiness = preloader.readiness;

  const id = $derived(page.url.searchParams.get('id') ?? '');

  /** Instant header (clicked card / TMDB list cache) while full details resolve. */
  let preview = $state.raw<Item | undefined>(undefined);
  let item = $state.raw<Item | undefined>(undefined);
  let status = $state<'loading' | 'ready' | 'missing' | 'error'>('loading');
  let seasonN = $state<number | null>(null);
  let episodes = $state.raw<Episode[] | null>(null);
  let related = $state.raw<Item[] | null>(null);
  let expanded = $state(false);
  let shared = $state('');

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
    if (!want) {
      status = 'missing';
      return;
    }
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

  // Lazy seasons (TMDB) — mock seasons are inline.
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

  const meta = $derived.by(() => {
    const it = shown;
    if (!it) return [] as string[];
    const out: string[] = [];
    if (it.year) out.push(String(it.year));
    if (it.maturity) out.push(it.maturity);
    if (it.rating) out.push(`★ ${it.rating.toFixed(1)}`);
    if (it.seasons?.length) out.push(`${it.seasons.length} season${it.seasons.length > 1 ? 's' : ''}`);
    else if (it.durationSec) out.push(fmt(it.durationSec));
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
    if (!it || (it.seasons?.length && eps === null)) return;
    let alive = true;
    let key = '';
    (async () => {
      const last = it.seasons?.length ? await resume.lastEpisode(activeProfileId(), it.id) : undefined;
      if (!alive) return;
      const ep = (last && eps?.find((e) => e.id === last)) || eps?.[0];
      target = ep;
      const m = mediaFor(it, ep);
      key = m.key;
      playKey = m.key;
      resumePos = await resume.startFor(activeProfileId(), it.id, ep?.id);
      if (!alive) return;
      void preloader.preload(m);
    })();
    return () => {
      alive = false;
      // Leaving details: free the warm buffer unless Play took it (take() makes this a no-op).
      const k = key;
      if (k) setTimeout(() => preloader.cancel(k), 3000);
    };
  });
  const playEp = $derived(target ?? firstEpisode);
  const ready = $derived($readiness[playKey]);

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

{#if status === 'missing' && !shown}
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
  {@const back = imgFor(shown.backdrop, 'hero')}
  {@const poster = imgFor(shown.poster, 'poster')}
  <article class="details" style:--item={color}>
    <div class="backdrop" aria-hidden="true">
      <img sizes={back.sizes} srcset={back.srcset} src={back.src} alt="" decoding="async" fetchpriority="high" />
      <div class="wash"></div>
    </div>

    <header class="head">
      <div class="poster" style:view-transition-name={SHARED_NAME}>
        <img sizes={poster.sizes} srcset={poster.srcset} src={poster.src} alt="{shown.title} poster" decoding="async" width="300" height="450" />
      </div>
      <div class="info">
        <a class="space" href={spaceHref(shown.space)}>{shown.space === 'HOME' ? (shown.type === 'MOVIE' ? 'Movie' : 'Series') : shown.space}</a>
        <h1>{shown.title}</h1>
        {#if shown.altTitle}<p class="alt">{shown.altTitle}</p>{/if}
        <ul class="meta">
          {#each meta as m (m)}<li>{m}</li>{/each}
        </ul>
        {#if shown.genres.length}
          <ul class="genres">
            {#each shown.genres as g (g)}<li>{g}</li>{/each}
          </ul>
        {/if}
      </div>
    </header>

    <div class="actions">
      <a class="btn btn-primary play" href={watchHref(shown.id, playEp?.id)}>
        <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M8 5.5v13l10.5-6.5z" /></svg>
        {resumePos > 0 ? 'Resume' : 'Play'}{playEp ? ` E${playEp.n}` : ''}
        {#if ready === 'ready'}<span class="ready" title="Ready — starts instantly"></span>{:else if ready === 'buffering' || ready === 'resolving'}<span class="ready warming" title="Preparing…"></span>{/if}
      </a>
      <button class="btn btn-ghost" disabled title="Downloads arrive with the player (Task 8.3)" aria-label="Download (coming soon)">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M12 4v11m0 0l-4.5-4.5M12 15l4.5-4.5M5 19.5h14" /></svg>
        Download
      </button>
      <button class="btn btn-ghost" aria-pressed={myList.has(shown)} onclick={() => shown && myList.toggle(shown)}>
        {myList.has(shown) ? '✓ My List' : '+ My List'}
      </button>
      <button class="btn btn-ghost icon" onclick={share} aria-label="Share">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M12 15V4m0 0L8 8m4-4l4 4M5 13v6h14v-6" /></svg>
      </button>
      {#if shared}<span class="toast" role="status">{shared}</span>{/if}
    </div>

    {#if shown.synopsis}
      <p class="syn" class:expanded>{shown.synopsis}</p>
      {#if shown.synopsis.length > 180}
        <button class="more" onclick={() => (expanded = !expanded)} aria-expanded={expanded}>{expanded ? 'Less' : 'More'}</button>
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
        <h2 class="section-title">Episodes</h2>
        {#if item.seasons.length > 1}
          <div class="h-scroll" role="group" aria-label="Seasons">
            {#each item.seasons as s (s.n)}
              <button class="chip" aria-pressed={s.n === seasonN} onclick={() => (seasonN = s.n)}>{s.title ?? `Season ${s.n}`}</button>
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
              <li>
                <a href={watchHref(shown.id, ep.id)} class="ep">
                  <div class="still">
                    <img sizes={still.sizes} srcset={still.srcset} src={still.src} alt="" loading="lazy" decoding="async" width="320" height="180" />
                    <span class="dur">{fmt(ep.durationSec)}</span>
                  </div>
                  <div class="ep-txt">
                    <h3>{ep.n}. {ep.title}</h3>
                    {#if ep.synopsis}<p>{ep.synopsis}</p>{/if}
                  </div>
                </a>
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
{/if}

<style>
  .details {
    position: relative;
    isolation: isolate;
    margin-top: calc(-12px - var(--safe-t));
    padding-top: clamp(120px, 34vw, 300px);
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
    opacity: 0.75;
  }
  .wash {
    position: absolute;
    inset: 0;
    background: linear-gradient(0deg, var(--bg) 4%, color-mix(in srgb, var(--bg) 55%, transparent) 50%, transparent),
      linear-gradient(0deg, color-mix(in srgb, var(--item, var(--tint)) 35%, transparent), transparent 70%);
  }
  :global([data-blur='all']) .backdrop img {
    filter: blur(2px) saturate(1.1);
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
    box-shadow: 0 20px 40px -18px var(--item, var(--tint)), inset 0 0 0 1px var(--glass-stroke);
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
  .space {
    font-size: 0.72rem;
    font-weight: 800;
    letter-spacing: 0.12em;
    text-transform: uppercase;
    color: color-mix(in srgb, var(--tint) 70%, white);
    text-decoration: none;
  }
  h1 {
    margin: 4px 0 2px;
    font-size: clamp(1.4rem, 5vw, 2.6rem);
    line-height: 1.1;
    font-weight: 850;
    text-wrap: balance;
  }
  .alt {
    margin: 0 0 6px;
    color: var(--text-secondary);
    font-size: 0.85rem;
  }
  .meta,
  .genres {
    list-style: none;
    margin: 6px 0 0;
    padding: 0;
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
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
    font-size: 0.75rem;
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
  .ready {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: var(--success);
    box-shadow: 0 0 8px var(--success);
  }
  .ready.warming {
    background: rgb(255 255 255 / 0.5);
    box-shadow: none;
    animation: pulse 1s ease-in-out infinite alternate;
  }
  @keyframes pulse {
    to {
      opacity: 0.3;
    }
  }
  .btn[disabled] {
    opacity: 0.55;
    cursor: not-allowed;
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
    max-width: 760px;
    color: color-mix(in srgb, var(--text-primary) 86%, transparent);
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
    padding: 0;
    border: 0;
    background: none;
    color: var(--text-secondary);
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
  .eps {
    list-style: none;
    margin: 0;
    padding: 4px 16px;
    display: grid;
    gap: 12px;
  }
  .ep {
    display: grid;
    grid-template-columns: clamp(120px, 36vw, 200px) 1fr;
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
  .dur {
    position: absolute;
    right: 6px;
    bottom: 6px;
    padding: 1px 6px;
    border-radius: 6px;
    background: rgb(0 0 0 / 0.7);
    font-size: 0.7rem;
  }
  .ep-txt h3 {
    margin: 0 0 4px;
    font-size: 0.92rem;
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
