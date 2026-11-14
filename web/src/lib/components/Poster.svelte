<script lang="ts">
  import type { Item } from '$lib/data/types';
  import { img } from '$lib/data/repo';
  import { tint } from '$lib/design/tint.svelte';

  interface Props {
    item: Item;
    landscape?: boolean;
    rank?: number;
    eager?: boolean;
  }
  let { item, landscape = false, rank, eager = false }: Props = $props();
  let loaded = $state(false);
  const src = $derived(img(landscape ? item.backdrop : item.poster));
</script>

<a
  class="card"
  class:landscape
  href="#{item.id}"
  title={item.title}
  style:--card-accent={item.accentColor}
  onfocus={() => tint.follow(item.dominantColor)}
  onpointerenter={() => tint.follow(item.dominantColor)}
>
  <div class="frame" class:loaded style:background-color={item.dominantColor}>
    {#if !loaded}<div class="skeleton fill" aria-hidden="true"></div>{/if}
    <img
      {src}
      alt=""
      loading={eager ? 'eager' : 'lazy'}
      decoding="async"
      width={landscape ? 480 : 300}
      height={landscape ? 270 : 450}
      onload={() => (loaded = true)}
      onerror={() => (loaded = true)}
    />
    {#if rank}<span class="rank">{rank}</span>{/if}
    {#if item.live || item.status === 'LIVE'}<span class="live">LIVE</span>{/if}
  </div>
  <span class="title">{item.title}</span>
</a>

<style>
  .card {
    flex: none;
    width: clamp(108px, 28vw, 168px);
    text-decoration: none;
    scroll-snap-align: start;
    border-radius: var(--radius-card);
  }
  .card.landscape {
    width: clamp(220px, 60vw, 320px);
  }
  .frame {
    position: relative;
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
  .card:hover .frame,
  .card:focus-visible .frame {
    transform: scale(var(--card-focus-scale));
    box-shadow: 0 12px 30px -10px var(--card-accent), 0 0 0 2px color-mix(in srgb, var(--card-accent) 70%, white);
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
  .rank {
    position: absolute;
    left: 6px;
    bottom: -6px;
    font-size: 3.2rem;
    font-weight: 900;
    line-height: 1;
    color: transparent;
    -webkit-text-stroke: 2px #fff;
    text-shadow: 0 4px 18px rgb(0 0 0 / 0.7);
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
</style>
