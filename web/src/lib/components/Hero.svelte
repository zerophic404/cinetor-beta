<script lang="ts">
  import type { Item } from '$lib/data/types';
  import { tint } from '$lib/design/tint.svelte';
  import { HERO_AUTO_ADVANCE_MS } from '$lib/design/motion';
  import { perf } from '$lib/perf/tier.svelte';
  import { colorOf, ensureColor } from '$lib/data/live-color.svelte';
  import { myList } from '$lib/data/library.svelte';
  import { CarouselController } from '$lib/ui/carousel';
  import { imgFor } from '$lib/ui/img';
  import { detailsHref, handoff, watchHref } from '$lib/ui/handoff';

  let { items }: { items: Item[] } = $props();

  let index = $state(0);
  let el = $state<HTMLElement>();
  let ctrl: CarouselController | null = null;

  const n = $derived(items.length);
  const current = $derived(items[Math.min(index, Math.max(0, n - 1))]);
  // Only the current slide and its neighbours are in the DOM (memory on low-end devices).
  const near = (i: number) => n > 0 && (i === index || i === (index + 1) % n || i === (index - 1 + n) % n);

  $effect(() => {
    ensureColor(current);
    tint.follow(current ? colorOf(current) : null);
    // Warm the next slide's color so the tint morph is ready when it advances.
    if (n > 1) ensureColor(items[(index + 1) % n]);
  });

  $effect(() => {
    const count = items.length;
    const node = el;
    const c = new CarouselController({ count, intervalMs: HERO_AUTO_ADVANCE_MS, onChange: (i) => (index = i) });
    ctrl = c;
    index = 0;
    c.setPaused('hidden', document.hidden);
    c.start();
    const onVis = () => c.setPaused('hidden', document.hidden);
    document.addEventListener('visibilitychange', onVis);
    let io: IntersectionObserver | undefined;
    if (node && typeof IntersectionObserver !== 'undefined') {
      io = new IntersectionObserver(([e]) => c.setPaused('offscreen', !e.isIntersecting), { threshold: 0.2 });
      io.observe(node);
    }
    return () => {
      document.removeEventListener('visibilitychange', onVis);
      io?.disconnect();
      c.destroy();
      if (ctrl === c) ctrl = null;
    };
  });

  // Swipe (touch/pen) + press-to-pause.
  let startX = 0;
  let startY = 0;
  let tracking = false;

  function down(e: PointerEvent) {
    if (e.pointerType === 'mouse') return;
    tracking = true;
    startX = e.clientX;
    startY = e.clientY;
    ctrl?.pause('manual');
  }
  function up(e: PointerEvent) {
    if (!tracking) return;
    tracking = false;
    const dx = e.clientX - startX;
    const dy = e.clientY - startY;
    if (Math.abs(dx) > 48 && Math.abs(dx) > Math.abs(dy) * 1.2) {
      if (dx < 0) ctrl?.next();
      else ctrl?.prev();
    }
    ctrl?.resume('manual');
  }
  function cancel() {
    tracking = false;
    ctrl?.resume('manual');
  }
  function blur(e: FocusEvent) {
    const to = e.relatedTarget;
    if (!(to instanceof Node) || !el?.contains(to)) ctrl?.resume('focus');
  }
  function key(e: KeyboardEvent) {
    if (e.key === 'ArrowRight') ctrl?.next();
    else if (e.key === 'ArrowLeft') ctrl?.prev();
    else return;
    e.preventDefault();
  }
</script>

{#if current}
  <section
    bind:this={el}
    class="hero"
    aria-roledescription="carousel"
    aria-label="Featured"
    onpointerenter={(e) => e.pointerType === 'mouse' && ctrl?.pause('hover')}
    onpointerleave={(e) => e.pointerType === 'mouse' && ctrl?.resume('hover')}
    onpointerdown={down}
    onpointerup={up}
    onpointercancel={cancel}
    onfocusin={() => ctrl?.pause('focus')}
    onfocusout={blur}
    onkeydown={key}
  >
    {#each items as item, i (item.id)}
      {#if near(i)}
        {@const a = imgFor(item.backdrop, 'hero')}
        <img
          class="bg"
          class:on={i === index}
          class:kb={perf.flags.kenBurns}
          sizes={a.sizes}
          srcset={a.srcset}
          src={a.src}
          alt=""
          loading={i === index ? 'eager' : 'lazy'}
          fetchpriority={i === index ? 'high' : 'low'}
          decoding="async"
          draggable="false"
        />
      {/if}
    {/each}
    <div class="scrim"></div>
    {#key current.id}
      <div class="meta">
        <h1><a href={detailsHref(current)} onclick={() => handoff(current)}>{current.title}</a></h1>
        <p class="sub">
          {#if current.year}{current.year} · {/if}{current.maturity}{#if current.rating} · ★ {current.rating.toFixed(1)}{/if}
          {#if current.genres.length} · {current.genres.slice(0, 3).join(' · ')}{/if}
        </p>
        <p class="syn">{current.synopsis}</p>
        <div class="actions">
          <a class="btn btn-primary" href={watchHref(current.id)}>
            <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M8 5.5v13l10.5-6.5z" /></svg>
            Play
          </a>
          <button class="btn btn-ghost" aria-pressed={myList.has(current)} onclick={() => myList.toggle(current)}>
            {myList.has(current) ? '✓ My List' : '+ My List'}
          </button>
          <a class="btn btn-ghost info" href={detailsHref(current)} onclick={() => handoff(current)} aria-label="More info">ⓘ</a>
        </div>
      </div>
    {/key}
    {#if n > 1}
      <div class="dots" role="tablist" aria-label="Featured titles">
        {#each items as item, i (item.id)}
          <button
            role="tab"
            aria-selected={i === index}
            aria-label={item.title}
            class:on={i === index}
            style:--ms="{HERO_AUTO_ADVANCE_MS}ms"
            onclick={() => ctrl?.goTo(i)}
          ></button>
        {/each}
      </div>
    {/if}
  </section>
{/if}

<style>
  .hero {
    position: relative;
    margin: 0 16px;
    height: clamp(300px, 58vw, 560px);
    border-radius: var(--radius-xl);
    overflow: hidden;
    isolation: isolate;
    touch-action: pan-y;
    user-select: none;
    background: color-mix(in srgb, var(--tint) 30%, var(--surface));
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
    animation: kb 14s ease-out both;
  }
  @keyframes kb {
    from {
      transform: scale(1.12) translate3d(1.5%, 1%, 0);
    }
    to {
      transform: scale(1) translate3d(0, 0, 0);
    }
  }
  .scrim {
    position: absolute;
    inset: 0;
    background: linear-gradient(0deg, rgb(0 0 0 / 0.9), rgb(0 0 0 / 0.25) 55%, transparent),
      linear-gradient(90deg, rgb(0 0 0 / 0.6), transparent 65%),
      linear-gradient(0deg, color-mix(in srgb, var(--tint) 22%, transparent), transparent 40%);
  }
  .meta {
    position: absolute;
    left: clamp(16px, 4vw, 40px);
    right: 16px;
    bottom: clamp(40px, 5vw, 60px);
    max-width: 580px;
    animation: rise calc(var(--dur-long) * var(--dur-scale)) var(--ease-emphasized) both;
  }
  @keyframes rise {
    from {
      opacity: 0;
      transform: translateY(10px);
    }
  }
  h1 {
    margin: 0 0 4px;
    font-size: clamp(1.5rem, 4.6vw, 2.8rem);
    line-height: 1.08;
    font-weight: 850;
    text-wrap: balance;
  }
  h1 a {
    text-decoration: none;
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
    flex-wrap: wrap;
    gap: 10px;
  }
  .info {
    padding: 0;
    width: 44px;
    font-size: 1.15rem;
  }
  .dots {
    position: absolute;
    right: 16px;
    bottom: 16px;
    display: flex;
    gap: 6px;
  }
  .dots button {
    position: relative;
    width: 8px;
    height: 8px;
    padding: 0;
    border: 0;
    border-radius: 99px;
    background: rgb(255 255 255 / 0.4);
    cursor: pointer;
    overflow: hidden;
    transition: width calc(var(--dur-medium) * var(--dur-scale)) var(--ease-emphasized);
  }
  .dots button.on {
    width: 26px;
    background: rgb(255 255 255 / 0.3);
  }
  .dots button.on::after {
    content: '';
    position: absolute;
    inset: 0;
    background: var(--tint);
    transform-origin: left;
    animation: fill var(--ms) linear both;
  }
  .hero:hover .dots button.on::after,
  .hero:focus-within .dots button.on::after {
    animation-play-state: paused;
  }
  @keyframes fill {
    from {
      transform: scaleX(0);
    }
  }
</style>
