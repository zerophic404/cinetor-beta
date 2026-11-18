<script lang="ts" module>
  export type CardVariant = 'poster' | 'landscape' | 'circle' | 'channel';
  let nextArtInstance = 0;
</script>

<script lang="ts">
  import type { Item } from '$lib/data/types';
  import { tint } from '$lib/design/tint.svelte';
  import { colorOf, ensureColor } from '$lib/data/live-color.svelte';
  import { imgFor } from '$lib/ui/img';
  import { detailsHref, handoff } from '$lib/ui/handoff';
  import {
    detectDubBadge,
    displayGenres,
    formatRating,
    generatedArtSpec,
    visibleWatchProgress
  } from '$lib/ui/space-model';

  interface Props {
    item: Item;
    variant?: CardVariant;
    /** Deprecated/ignored in v2.1+: Trending Now renders clean unnumbered cards. */
    rank?: number;
    /** Watch progress 0..1 (rendered when within 2%..95%). */
    progress?: number;
    eager?: boolean;
  }
  let { item, variant = 'poster', progress, eager = false }: Props = $props();

  const artUid = `ga-${++nextArtInstance}`;

  let loaded = $state(false);
  let failed = $state(false);
  let tryRaw = $state(false);
  let imgEl = $state<HTMLImageElement>();

  const wide = $derived(variant === 'landscape');
  const rawImg = $derived(wide ? item.backdrop || item.poster : item.poster || item.backdrop);
  const cleanRawImg = $derived(
    /^https?:\/\//i.test(rawImg)
      ? rawImg
          .replace(/([?&])x-oss-process=[^&]*(&|$)/, (_, p1: string, p2: string) => (p2 ? p1 : ''))
          .replace(/[?&]$/, '')
      : ''
  );
  const attrs = $derived(imgFor(rawImg, wide ? 'backdrop' : variant === 'circle' ? 'avatar' : 'poster'));
  const color = $derived(colorOf(item));

  $effect(() => {
    void rawImg;
    void attrs.src;
    loaded = false;
    failed = false;
    tryRaw = false;
  });

  const useGenerated = $derived(!attrs.src || failed);
  const art = $derived(useGenerated ? generatedArtSpec(item.id || item.title) : null);

  const isLive = $derived(Boolean(item.live || item.status === 'LIVE'));
  const showBadges = $derived(variant === 'poster' || variant === 'landscape');
  const ratingText = $derived(showBadges && !isLive ? formatRating(item.rating) : null);
  const dubBadge = $derived(showBadges ? detectDubBadge(item) : null);
  const showSeriesCapsule = $derived(variant === 'poster' && !dubBadge && item.type === 'SERIES');
  const watched = $derived(visibleWatchProgress(progress));

  const subLabel = $derived.by(() => {
    if (variant === 'channel') return displayGenres(item, 1)[0] ?? '';
    if (variant !== 'poster' && variant !== 'landscape') return '';
    const parts: string[] = [];
    if (item.year > 0) parts.push(String(item.year));
    const clean = displayGenres(item, 1)[0];
    if (clean) parts.push(clean);
    else if (item.type === 'SERIES') parts.push('Series');
    return parts.join(' · ');
  });

  function hot() {
    ensureColor(item);
    tint.followSoon(colorOf(item));
  }
</script>

<a
  class="card {variant}"
  href={detailsHref(item)}
  aria-label={item.title}
  title={item.title}
  style:--card-accent={color}
  onfocus={hot}
  onpointerenter={hot}
  onclick={() => handoff(item, imgEl)}
>
  <div class="frame" class:loaded={loaded && !useGenerated} style:background-color={art ? art.dark : color}>
    {#if useGenerated && art}
      <svg class="gen-art" viewBox="0 0 100 150" preserveAspectRatio="none" aria-hidden="true">
        <defs>
          <linearGradient id="{artUid}-bg" x1="{art.x1}%" y1="0%" x2="{art.x2}%" y2="100%">
            <stop offset="0%" stop-color={art.light} />
            <stop offset="100%" stop-color={art.dark} />
          </linearGradient>
          <linearGradient id="{artUid}-beam" x1="0%" y1="0%" x2="0%" y2="100%">
            <stop offset="0%" stop-color="#ffffff" stop-opacity="0.16" />
            <stop offset="100%" stop-color="#ffffff" stop-opacity="0" />
          </linearGradient>
          {#each art.orbs as orb, oi (oi)}
            <radialGradient id="{artUid}-o{oi}" cx="50%" cy="50%" r="50%">
              <stop offset="0%" stop-color="#ffffff" stop-opacity={orb.alpha} />
              <stop offset="100%" stop-color="#ffffff" stop-opacity="0" />
            </radialGradient>
          {/each}
        </defs>
        <rect width="100" height="150" fill="url(#{artUid}-bg)" />
        {#each art.orbs as orb, oi (oi)}
          <circle cx={orb.cx} cy={orb.cy * 1.5} r={orb.r} fill="url(#{artUid}-o{oi})" style="mix-blend-mode: screen" />
        {/each}
        <line
          x1={art.beamX1}
          y1="0"
          x2={art.beamX2}
          y2="150"
          stroke="url(#{artUid}-beam)"
          stroke-width="14"
          style="mix-blend-mode: screen"
        />
      </svg>
      {#if variant !== 'circle'}
        <span class="gen-scrim" aria-hidden="true"></span>
        <span class="gen-title" aria-hidden="true">{item.title}</span>
      {/if}
    {:else}
      {#if !loaded}<div class="skeleton fill" aria-hidden="true"></div>{/if}
      {#key tryRaw}
        <img
          bind:this={imgEl}
          referrerpolicy="no-referrer"
          sizes={tryRaw ? undefined : attrs.sizes}
          srcset={tryRaw ? undefined : attrs.srcset}
          src={tryRaw ? cleanRawImg || rawImg : attrs.src}
          alt=""
          loading={eager ? 'eager' : 'lazy'}
          decoding="async"
          width={wide ? 480 : 300}
          height={wide ? 270 : variant === 'poster' ? 450 : 300}
          onload={() => (loaded = true)}
          onerror={() => {
            if (!tryRaw && cleanRawImg && (attrs.srcset || attrs.src !== cleanRawImg)) {
              tryRaw = true;
              loaded = false;
              return;
            }
            failed = true;
            loaded = true;
          }}
        />
      {/key}
    {/if}

    {#if isLive}
      <span class="live">LIVE</span>
    {:else if ratingText}
      <span class="rating-pill" aria-hidden="true">
        <svg class="star" width="10" height="10" viewBox="0 0 24 24" aria-hidden="true">
          <path fill="currentColor" d="M12 2.4l2.93 5.94 6.56.95-4.74 4.62 1.12 6.53L12 17.35 6.13 20.44l1.12-6.53L2.51 9.29l6.56-.95z" />
        </svg>
        {ratingText}
      </span>
    {/if}

    {#if dubBadge}
      <span class="dub-badge" aria-hidden="true">{dubBadge}</span>
    {:else if showSeriesCapsule}
      <span class="series-capsule" title="Series" aria-hidden="true"></span>
    {/if}

    {#if watched !== null}
      <span
        class="progress"
        role="progressbar"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={Math.round(watched * 100)}
        aria-label="Watched"
      >
        <i style:width="{Math.round(watched * 100)}%"></i>
      </span>
    {/if}
  </div>

  <span class="title">{item.title}</span>
  {#if subLabel}
    <span class="sub">{subLabel}</span>
  {/if}
</a>

<style>
  .card {
    --w: clamp(112px, 28vw, 168px);
    position: relative;
    display: flex;
    flex-direction: column;
    flex: none;
    width: var(--w);
    text-decoration: none;
    scroll-snap-align: start;
    border-radius: var(--radius-card);
  }
  .card.landscape {
    --w: clamp(220px, 60vw, 320px);
  }
  .card.circle,
  .card.channel {
    --w: clamp(96px, 24vw, 136px);
  }

  .frame {
    position: relative;
    z-index: 1;
    width: 100%;
    aspect-ratio: 2 / 3;
    border-radius: var(--radius-card);
    overflow: hidden;
    transform: translateZ(0);
    transition:
      transform calc(var(--dur-medium) * var(--dur-scale)) var(--ease-emphasized),
      box-shadow calc(var(--dur-medium) * var(--dur-scale)) var(--ease-standard);
    box-shadow:
      0 8px 20px -12px rgb(0 0 0 / 0.55),
      inset 0 0 0 1px var(--glass-stroke);
  }
  .landscape .frame {
    aspect-ratio: 16 / 9;
  }
  .circle .frame {
    aspect-ratio: 1;
    border-radius: 50%;
  }
  .channel .frame {
    aspect-ratio: 1;
  }
  .channel img {
    object-fit: contain;
    padding: 14%;
  }

  .card:hover .frame,
  .card:focus-visible .frame,
  :global([data-tv]) .card:focus .frame {
    transform: scale(var(--card-focus-scale, 1.05));
    box-shadow:
      0 14px 32px -10px color-mix(in srgb, var(--card-accent) 65%, rgb(0 0 0 / 0.7)),
      0 0 0 2px var(--focus-ring);
  }
  .card:focus-visible {
    outline: none;
  }
  .card:active .frame {
    transform: scale(0.97);
  }

  img,
  .gen-art {
    width: 100%;
    height: 100%;
    display: block;
  }
  img {
    object-fit: cover;
    opacity: 0;
    transition: opacity calc(var(--dur-long) * var(--dur-scale)) var(--ease-standard);
  }
  .loaded img {
    opacity: 1;
  }
  .fill {
    position: absolute;
    inset: 0;
    border-radius: 0;
  }

  .gen-scrim {
    position: absolute;
    inset: 0;
    background: linear-gradient(180deg, transparent 42%, rgb(6 6 9 / 0.92) 100%);
    pointer-events: none;
  }
  .gen-title {
    position: absolute;
    left: 8px;
    right: 8px;
    bottom: 10px;
    color: #ffffff;
    font-size: 0.76rem;
    font-weight: 700;
    line-height: 1.25;
    text-align: center;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
    pointer-events: none;
  }

  /* CinePulse Top-Left Rating Pill */
  .rating-pill {
    position: absolute;
    top: 6px;
    left: 6px;
    z-index: 2;
    display: inline-flex;
    align-items: center;
    gap: 3px;
    padding: 2px 6px;
    border-radius: 6px;
    background: rgb(8 8 11 / 0.82);
    box-shadow: inset 0 0 0 1px rgb(255 255 255 / 0.14);
    color: #ffffff;
    font-size: 0.66rem;
    font-weight: 750;
    line-height: 1.2;
    font-variant-numeric: tabular-nums;
    pointer-events: none;
  }
  .rating-pill .star {
    color: #ffb800;
    flex: none;
  }

  /* CinePulse Top-Right Dub Badge or Sleek Series Capsule */
  .dub-badge {
    position: absolute;
    top: 6px;
    right: 6px;
    z-index: 2;
    padding: 2px 5.5px;
    border-radius: 5px;
    background: rgb(255 255 255 / 0.95);
    color: #09090b;
    font-size: 0.6rem;
    font-weight: 800;
    line-height: 1.25;
    letter-spacing: 0.01em;
    box-shadow: 0 2px 8px rgb(0 0 0 / 0.45);
    pointer-events: none;
  }
  .series-capsule {
    position: absolute;
    top: 7px;
    right: 7px;
    z-index: 2;
    width: 8px;
    height: 16px;
    border-radius: 99px;
    background: rgb(255 255 255 / 0.9);
    box-shadow:
      0 2px 6px rgb(0 0 0 / 0.5),
      inset 0 0 0 1px rgb(0 0 0 / 0.14);
    pointer-events: none;
  }

  .live {
    position: absolute;
    top: 6px;
    left: 6px;
    z-index: 2;
    padding: 2px 7px;
    border-radius: var(--radius-pill);
    background: var(--live-badge);
    color: #ffffff;
    font-size: 0.64rem;
    font-weight: 800;
    letter-spacing: 0.07em;
  }

  /* Bottom Cinema Red Watch-Progress Line (2%..95%) */
  .progress {
    position: absolute;
    left: 0;
    right: 0;
    bottom: 0;
    z-index: 2;
    height: 3.5px;
    background: rgb(255 255 255 / 0.3);
    overflow: hidden;
  }
  .progress i {
    display: block;
    height: 100%;
    background: var(--accent);
    box-shadow: 0 0 8px var(--accent-glow);
  }

  /* Always-visible title underneath EVERY thumbnail (1-2 lines, ellipsis, touch & desktop) */
  .title {
    display: -webkit-box;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    -webkit-box-orient: vertical;
    margin-top: 7px;
    font-size: 0.83rem;
    font-weight: 650;
    line-height: 1.28;
    color: var(--text-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    word-break: break-word;
    transition: color calc(var(--dur-short) * var(--dur-scale)) var(--ease-standard);
  }
  .card:hover .title,
  .card:focus-visible .title,
  :global([data-tv]) .card:focus .title {
    color: var(--text-primary);
  }
  .sub {
    display: block;
    margin-top: 2px;
    font-size: 0.72rem;
    line-height: 1.25;
    color: var(--text-tertiary);
    font-variant-numeric: tabular-nums;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .circle .title {
    text-align: center;
  }

  @media (prefers-reduced-motion: reduce) {
    .card:hover .frame,
    .card:focus-visible .frame {
      transform: none;
    }
  }
</style>
