<script lang="ts">
  import type { Item } from '$lib/data/types';
  import { img } from '$lib/data/repo';
  import { tint } from '$lib/design/tint.svelte';
  import { HERO_AUTO_ADVANCE_MS } from '$lib/design/motion';
  import { perf } from '$lib/perf/tier.svelte';

  let { items }: { items: Item[] } = $props();
  let index = $state(0);
  let paused = $state(false);
  const current = $derived(items[index % Math.max(1, items.length)]);

  $effect(() => {
    tint.follow(current?.dominantColor);
  });

  $effect(() => {
    if (paused || items.length < 2) return;
    const id = setInterval(() => {
      if (!document.hidden) index = (index + 1) % items.length;
    }, HERO_AUTO_ADVANCE_MS);
    return () => clearInterval(id);
  });
</script>

{#if current}
  <section
    class="hero"
    aria-roledescription="carousel"
    aria-label="Featured"
    onpointerenter={() => (paused = true)}
    onpointerleave={() => (paused = false)}
    onfocusin={() => (paused = true)}
    onfocusout={() => (paused = false)}
  >
    {#each items as item, i (item.id)}
      <img
        class="bg"
        class:on={i === index}
        class:kb={perf.flags.kenBurns}
        src={img(item.backdrop)}
        alt=""
        loading={i === 0 ? 'eager' : 'lazy'}
        fetchpriority={i === 0 ? 'high' : 'auto'}
        decoding="async"
      />
    {/each}
    <div class="scrim"></div>
    <div class="meta">
      <h1>{current.title}</h1>
      <p class="sub">{current.year} · {current.maturity} · ★ {current.rating.toFixed(1)} · {current.genres.slice(0, 3).join(' · ')}</p>
      <p class="syn">{current.synopsis}</p>
      <div class="actions">
        <a class="btn btn-primary" href="#{current.id}">▶ Play</a>
        <a class="btn btn-ghost" href="#{current.id}">Details</a>
      </div>
    </div>
    <div class="dots" role="tablist" aria-label="Featured titles">
      {#each items as item, i (item.id)}
        <button
          role="tab"
          aria-selected={i === index}
          aria-label={item.title}
          class:on={i === index}
          onclick={() => (index = i)}
        ></button>
      {/each}
    </div>
  </section>
{/if}

<style>
  .hero {
    position: relative;
    margin: 0 16px;
    height: clamp(280px, 56vw, 540px);
    border-radius: var(--radius-xl);
    overflow: hidden;
    isolation: isolate;
    box-shadow: 0 30px 60px -30px color-mix(in srgb, var(--tint) 60%, transparent), inset 0 0 0 1px var(--glass-stroke);
  }
  .bg {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    object-fit: cover;
    opacity: 0;
    transition: opacity calc(var(--dur-xlong) * var(--dur-scale)) var(--ease-standard);
  }
  .bg.on {
    opacity: 1;
  }
  .bg.on.kb {
    animation: kb 12s ease-out both;
  }
  @keyframes kb {
    from { transform: scale(1.12); }
    to { transform: scale(1); }
  }
  .scrim {
    position: absolute;
    inset: 0;
    background: linear-gradient(0deg, rgb(0 0 0 / 0.88), rgb(0 0 0 / 0.25) 55%, transparent),
      linear-gradient(90deg, rgb(0 0 0 / 0.55), transparent 65%);
  }
  .meta {
    position: absolute;
    left: clamp(16px, 4vw, 40px);
    right: 16px;
    bottom: clamp(36px, 5vw, 56px);
    max-width: 560px;
  }
  h1 {
    margin: 0 0 4px;
    font-size: clamp(1.5rem, 4.6vw, 2.8rem);
    line-height: 1.08;
    font-weight: 850;
    text-wrap: balance;
  }
  .sub {
    margin: 0 0 6px;
    font-size: 0.82rem;
    color: var(--text-secondary);
  }
  .syn {
    margin: 0 0 14px;
    color: color-mix(in srgb, var(--text-primary) 82%, transparent);
    display: -webkit-box;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }
  .actions {
    display: flex;
    gap: 10px;
  }
  .dots {
    position: absolute;
    right: 16px;
    bottom: 16px;
    display: flex;
    gap: 6px;
  }
  .dots button {
    width: 8px;
    height: 8px;
    padding: 0;
    border: 0;
    border-radius: 99px;
    background: rgb(255 255 255 / 0.4);
    cursor: pointer;
    transition: width calc(var(--dur-medium) * var(--dur-scale)) var(--ease-emphasized);
  }
  .dots button.on {
    width: 22px;
    background: var(--tint);
  }
</style>
