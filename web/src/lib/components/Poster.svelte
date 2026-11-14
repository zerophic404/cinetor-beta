<script lang="ts" module>
  export type CardVariant = 'poster' | 'landscape' | 'circle' | 'channel';
</script>

<script lang="ts">
  import type { Item } from '$lib/data/types';
  import { tint } from '$lib/design/tint.svelte';
  import { colorOf, ensureColor } from '$lib/data/live-color.svelte';
  import { imgFor } from '$lib/ui/img';
  import { detailsHref, handoff } from '$lib/ui/handoff';

  interface Props {
    item: Item;
    variant?: CardVariant;
    /** TOP10 rank numeral. */
    rank?: number;
    /** CONTINUE progress 0..1. */
    progress?: number;
    eager?: boolean;
  }
  let { item, variant = 'poster', rank, progress, eager = false }: Props = $props();

  let loaded = $state(false);
  let imgEl = $state<HTMLImageElement>();

  const wide = $derived(variant === 'landscape');
  const attrs = $derived(imgFor(wide ? item.backdrop : item.poster, wide ? 'backdrop' : variant === 'circle' ? 'avatar' : 'poster'));
  const color = $derived(colorOf(item));

  function hot() {
    ensureColor(item);
    tint.followSoon(colorOf(item));
  }
</script>

<a
  class="card {variant}"
  class:ranked={!!rank}
  href={detailsHref(item)}
  title={item.title}
  style:--card-accent={color}
  onfocus={hot}
  onpointerenter={hot}
  onclick={() => handoff(item, imgEl)}
>
  {#if rank}<span class="rank" aria-hidden="true">{rank}</span>{/if}
  <div class="frame" class:loaded style:background-color={color}>
    {#if !loaded}<div class="skeleton fill" aria-hidden="true"></div>{/if}
    <img
      bind:this={imgEl}
      sizes={attrs.sizes}
      srcset={attrs.srcset}
      src={attrs.src}
      alt=""
      loading={eager ? 'eager' : 'lazy'}
      decoding="async"
      width={wide ? 480 : 300}
      height={wide ? 270 : variant === 'poster' ? 450 : 300}
      onload={() => (loaded = true)}
      onerror={() => (loaded = true)}
    />
    {#if item.live || item.status === 'LIVE'}<span class="live">LIVE</span>{/if}
    {#if progress !== undefined}
      <span class="progress" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(progress * 100)} aria-label="Watched">
        <i style:width="{Math.round(progress * 100)}%"></i>
      </span>
    {/if}
  </div>
  <span class="title">{item.title}</span>
</a>

<style>
  .card {
    --w: clamp(108px, 28vw, 168px);
    position: relative;
    display: block;
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
  .card.ranked {
    width: calc(var(--w) + 38px);
    padding-left: 38px;
  }
  .frame {
    position: relative;
    z-index: 1;
    aspect-ratio: 2 / 3;
    border-radius: var(--radius-card);
    overflow: hidden;
    transform: translateZ(0);
    transition: transform calc(var(--dur-medium) * var(--dur-scale)) var(--ease-emphasized),
      box-shadow calc(var(--dur-medium) * var(--dur-scale)) var(--ease-standard);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
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
  .card:focus-visible .frame {
    transform: scale(var(--card-focus-scale));
    box-shadow: 0 12px 30px -10px var(--card-accent), 0 0 0 2px color-mix(in srgb, var(--card-accent) 60%, var(--accent));
  }
  .card:focus-visible {
    outline: none;
  }
  .card:active .frame {
    transform: scale(0.97);
  }
  img {
    width: 100%;
    height: 100%;
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
  .title {
    display: block;
    margin-top: 6px;
    font-size: 0.82rem;
    color: var(--text-secondary);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .circle .title {
    text-align: center;
  }
  .rank {
    position: absolute;
    left: -2px;
    bottom: 18px;
    z-index: 0;
    font-size: clamp(4.2rem, 13vw, 6.2rem);
    font-weight: 900;
    line-height: 0.8;
    letter-spacing: -0.06em;
    color: var(--bg);
    -webkit-text-stroke: 3px color-mix(in srgb, var(--tint) 70%, white);
    paint-order: stroke fill;
  }
  .live {
    position: absolute;
    top: 8px;
    left: 8px;
    padding: 2px 8px;
    border-radius: var(--radius-pill);
    background: var(--live-badge);
    font-size: 0.68rem;
    font-weight: 800;
    letter-spacing: 0.08em;
  }
  .progress {
    position: absolute;
    left: 8px;
    right: 8px;
    bottom: 8px;
    height: 4px;
    border-radius: 99px;
    background: rgb(255 255 255 / 0.25);
    overflow: hidden;
  }
  .progress i {
    display: block;
    height: 100%;
    background: var(--accent);
  }
</style>
