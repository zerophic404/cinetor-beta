<script lang="ts" module>
  let nextHeroArt = 0;
</script>

<script lang="ts">
  import { isVideoSpace, type Item } from '$lib/data/types';
  import { tint } from '$lib/design/tint.svelte';
  import { HERO_AUTO_ADVANCE_MS } from '$lib/design/motion';
  import { perf } from '$lib/perf/tier.svelte';
  import { colorOf, ensureColor } from '$lib/data/live-color.svelte';
  import { myList } from '$lib/data/library.svelte';
  import { dataSaver, prepareMedia, preloader } from '$lib/player/controller';
  import {
    CarouselController,
    DEFAULT_TOUCH_COOLDOWN_MS,
    deckCardTransform,
    deckOffset,
    resolveSwipe
  } from '$lib/ui/carousel';
  import {
    dedupeItems,
    detectDubBadge,
    displayGenres,
    formatRating,
    generatedArtSpec,
    has4kBadge
  } from '$lib/ui/space-model';
  import { imgFor } from '$lib/ui/img';
  import { detailsHref, handoff } from '$lib/ui/handoff';

  interface Props {
    items: Item[];
    paused?: boolean;
  }
  let { items, paused = false }: Props = $props();

  const heroUid = `hga-${++nextHeroArt}`;
  const readiness = preloader.readiness;

  let index = $state(0);
  let activeMs = $state<number>(HERO_AUTO_ADVANCE_MS);
  let isPaused = $state(false);
  let el = $state<HTMLElement>();
  let fgPosterEl = $state<HTMLImageElement>();
  let isWide = $state(typeof window !== 'undefined' ? window.matchMedia('(min-width: 768px)').matches : true);
  let dragPx = $state(0);
  let dragging = $state(false);
  let rawFallbackIds = $state.raw<Record<string, boolean>>({});
  let failedIds = $state.raw<Record<string, boolean>>({});
  let fgRawFallbackIds = $state.raw<Record<string, boolean>>({});
  let fgFailedIds = $state.raw<Record<string, boolean>>({});
  let ctrl: CarouselController | null = null;

  const slides = $derived(dedupeItems(items));
  const n = $derived(slides.length);
  const safeIndex = $derived(n > 0 ? Math.min(index, n - 1) : 0);
  const current = $derived(slides[safeIndex]);
  const lowTier = $derived(perf.flags.tier === 'LOW');
  const readyMap = $derived($readiness);

  const nearWide = (i: number) =>
    n > 0 && (i === safeIndex || i === (safeIndex + 1) % n || i === (safeIndex - 1 + n) % n);

  function isStreamReady(item: Item): boolean {
    for (const [k, v] of Object.entries(readyMap)) {
      if ((k === item.id || k.startsWith(`${item.id}#`) || k.startsWith(`${item.id}@`)) && v === 'ready') {
        return true;
      }
    }
    return false;
  }

  /** Navigate to Title Details with inline autoplay (`&play=1`), never `/app/watch` for VOD. */
  function heroPlayHref(item: Item): string {
    const href = detailsHref(item);
    if (item.type === 'CHANNEL' || item.space === 'MUSIC' || item.space === 'COMICS') return href;
    return `${href}${href.includes('?') ? '&' : '?'}play=1`;
  }

  $effect(() => {
    if (typeof window === 'undefined') return;
    const mql = window.matchMedia('(min-width: 768px)');
    isWide = mql.matches;
    const onChange = (e: MediaQueryListEvent) => {
      isWide = e.matches;
    };
    mql.addEventListener('change', onChange);
    return () => mql.removeEventListener('change', onChange);
  });

  $effect(() => {
    ensureColor(current);
    tint.follow(current ? colorOf(current) : null);
    if (n > 1) ensureColor(slides[(safeIndex + 1) % n]);
  });

  // Gently pre-warm the front Hero slide on non-low, non-data-saver sessions so the green dot lights up.
  $effect(() => {
    const item = current;
    if (!item || lowTier || !isVideoSpace(item.space) || dataSaver()) return;
    if (typeof document !== 'undefined' && document.hidden) return;
    const ctl = new AbortController();
    const timer = setTimeout(async () => {
      try {
        const ep = item.seasons?.[0]?.episodes?.[0];
        const m = await prepareMedia(item, ep, ctl.signal);
        if (!ctl.signal.aborted && m.playable) {
          void preloader.preload(m);
        }
      } catch {
        /* ignore preload errors */
      }
    }, 650);
    return () => {
      clearTimeout(timer);
      ctl.abort();
    };
  });

  function syncCtrlMeta() {
    if (!ctrl) return;
    activeMs = ctrl.currentDelayMs;
    isPaused = ctrl.paused;
  }

  $effect(() => {
    const count = slides.length;
    const node = el;
    const c = new CarouselController({
      count,
      intervalMs: HERO_AUTO_ADVANCE_MS,
      touchCooldownMs: DEFAULT_TOUCH_COOLDOWN_MS,
      onChange: (i) => {
        index = i;
        activeMs = c.currentDelayMs;
        isPaused = c.paused;
      }
    });
    ctrl = c;
    index = 0;
    activeMs = HERO_AUTO_ADVANCE_MS;
    if (typeof document !== 'undefined') {
      c.setPaused('hidden', document.hidden);
      const reduced =
        (typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches) ||
        document.documentElement.dataset.motion === 'reduce';
      c.setPaused('reduced', reduced);
    }
    c.setPaused('modal', paused);
    c.start();
    syncCtrlMeta();

    const onVis = () => {
      c.setPaused('hidden', document.hidden);
      syncCtrlMeta();
    };
    document.addEventListener('visibilitychange', onVis);

    let mqlMotion: MediaQueryList | undefined;
    const onMotion = () => {
      const r = Boolean(mqlMotion?.matches) || document.documentElement.dataset.motion === 'reduce';
      c.setPaused('reduced', r);
      syncCtrlMeta();
    };
    if (typeof matchMedia === 'function') {
      mqlMotion = matchMedia('(prefers-reduced-motion: reduce)');
      mqlMotion.addEventListener('change', onMotion);
    }

    let io: IntersectionObserver | undefined;
    if (node && typeof IntersectionObserver !== 'undefined') {
      io = new IntersectionObserver(
        ([e]) => {
          c.setPaused('offscreen', !e.isIntersecting);
          syncCtrlMeta();
        },
        { threshold: 0.2 }
      );
      io.observe(node);
    }
    return () => {
      document.removeEventListener('visibilitychange', onVis);
      mqlMotion?.removeEventListener('change', onMotion);
      io?.disconnect();
      c.destroy();
      if (ctrl === c) ctrl = null;
    };
  });

  $effect(() => {
    ctrl?.setPaused('modal', paused);
    syncCtrlMeta();
  });

  function stripResizeParam(url: string): string {
    if (!/^https?:\/\//i.test(url)) return '';
    return url
      .replace(/([?&])x-oss-process=[^&]*(&|$)/, (_, p1: string, p2: string) => (p2 ? p1 : ''))
      .replace(/[?&]$/, '');
  }

  function markFailed(id: string) {
    if (failedIds[id]) return;
    failedIds = { ...failedIds, [id]: true };
  }

  function handleSlideImgError(id: string, rawUrl: string, a: { src: string; srcset?: string }) {
    const fallbackUrl = stripResizeParam(rawUrl);
    if (!rawFallbackIds[id] && fallbackUrl && (a.srcset || a.src !== fallbackUrl)) {
      rawFallbackIds = { ...rawFallbackIds, [id]: true };
      return;
    }
    markFailed(id);
  }

  function handleFgPosterError(id: string, rawUrl: string, a: { src: string; srcset?: string }) {
    const fallbackUrl = stripResizeParam(rawUrl);
    if (!fgRawFallbackIds[id] && fallbackUrl && (a.srcset || a.src !== fallbackUrl)) {
      fgRawFallbackIds = { ...fgRawFallbackIds, [id]: true };
      return;
    }
    if (!fgFailedIds[id]) {
      fgFailedIds = { ...fgFailedIds, [id]: true };
    }
  }

  // Swipe (touch/pen) with live 3D deck drag + 6s post-touch cooldown.
  let startX = 0;
  let startY = 0;
  let tracking = false;
  let swipedAt = 0;

  function down(e: PointerEvent) {
    if (e.pointerType === 'mouse' || n < 2) return;
    tracking = true;
    dragging = false;
    dragPx = 0;
    startX = e.clientX;
    startY = e.clientY;
    ctrl?.touchStart();
    syncCtrlMeta();
  }

  function move(e: PointerEvent) {
    if (!tracking) return;
    const dx = e.clientX - startX;
    const dy = e.clientY - startY;
    if (!dragging) {
      if (Math.abs(dy) > 12 && Math.abs(dy) > Math.abs(dx) * 1.15) {
        tracking = false;
        dragPx = 0;
        ctrl?.touchEnd();
        syncCtrlMeta();
        return;
      }
      if (Math.abs(dx) > 8 && Math.abs(dx) > Math.abs(dy)) {
        dragging = true;
      }
    }
    if (dragging) {
      dragPx = dx;
    }
  }

  function up(e: PointerEvent) {
    if (!tracking) return;
    tracking = false;
    const dx = e.clientX - startX;
    const dy = e.clientY - startY;
    const dir = resolveSwipe(dx, dy, el?.clientWidth ?? 320);
    dragging = false;
    dragPx = 0;
    if (dir === 1) {
      swipedAt = Date.now();
      ctrl?.next();
    } else if (dir === -1) {
      swipedAt = Date.now();
      ctrl?.prev();
    }
    ctrl?.touchEnd();
    syncCtrlMeta();
  }

  function cancel() {
    if (!tracking) return;
    tracking = false;
    dragging = false;
    dragPx = 0;
    ctrl?.touchEnd();
    syncCtrlMeta();
  }

  function guardClick(e: MouseEvent, cb?: () => void) {
    if (Date.now() - swipedAt < 180) {
      e.preventDefault();
      e.stopPropagation();
      return;
    }
    cb?.();
  }

  function pickSlide(i: number, viaTouch: boolean) {
    if (viaTouch) ctrl?.noteTouch();
    ctrl?.goTo(i);
    syncCtrlMeta();
  }

  function focusOut(e: FocusEvent) {
    const to = e.relatedTarget;
    if (!(to instanceof Node) || !el?.contains(to)) {
      ctrl?.resume('focus');
      syncCtrlMeta();
    }
  }

  function key(e: KeyboardEvent) {
    if (document.documentElement.dataset.tv) return;
    if (e.key === 'ArrowRight') ctrl?.next();
    else if (e.key === 'ArrowLeft') ctrl?.prev();
    else return;
    e.preventDefault();
    syncCtrlMeta();
  }

  const dragFraction = $derived.by(() => {
    if (!dragging || !dragPx) return 0;
    const baseWidth = Math.max(220, (el?.clientWidth ?? 360) * 0.76);
    return Math.max(-1, Math.min(1, dragPx / baseWidth));
  });
</script>

{#if current}
  <section
    bind:this={el}
    class="hero-wrap"
    class:wide={isWide}
    class:deck={!isWide}
    data-paused={isPaused ? 'true' : 'false'}
    role="region"
    aria-roledescription="carousel"
    aria-label="Featured"
    onpointerenter={(e) => {
      if (e.pointerType === 'mouse') {
        ctrl?.pause('hover');
        syncCtrlMeta();
      }
    }}
    onpointerleave={(e) => {
      if (e.pointerType === 'mouse') {
        ctrl?.resume('hover');
        syncCtrlMeta();
      }
    }}
    onpointerdown={down}
    onpointermove={move}
    onpointerup={up}
    onpointercancel={cancel}
    onfocusin={() => {
      ctrl?.pause('focus');
      syncCtrlMeta();
    }}
    onfocusout={focusOut}
    onkeydown={key}
  >
    <!-- Ambient ArtTint theater spill behind the hero -->
    <div class="hero-ambient" aria-hidden="true"></div>

    {#if isWide}
      {@const ratingText = formatRating(current.rating)}
      {@const dub = detectDubBadge(current)}
      {@const genres = displayGenres(current, 2)}
      {@const is4k = has4kBadge(current)}
      {@const qualityTag = is4k ? '4K UHD' : isVideoSpace(current.space) ? 'HD' : null}
      {@const saved = myList.has(current)}
      {@const readyNow = isStreamReady(current)}
      {@const fgRaw = current.poster || current.backdrop}
      {@const fgRawUrl = stripResizeParam(fgRaw)}
      {@const fgUseRaw = Boolean(fgRawFallbackIds[current.id] && fgRawUrl)}
      {@const fgAttrs = imgFor(fgRaw, 'poster')}
      {@const fgUseGen = !fgAttrs.src || Boolean(fgFailedIds[current.id])}

      <div class="showcase glass">
        {#each slides as item, i (item.id)}
          {#if nearWide(i)}
            {@const raw = item.backdrop || item.poster}
            {@const rawUrl = stripResizeParam(raw)}
            {@const useRaw = Boolean(rawFallbackIds[item.id] && rawUrl)}
            {@const a = imgFor(raw, 'hero')}
            {@const useGen = !a.src || Boolean(failedIds[item.id])}
            {#if useGen}
              {@const art = generatedArtSpec(item.id || item.title)}
              <svg
                class="bg"
                class:on={i === safeIndex}
                viewBox="0 0 160 90"
                preserveAspectRatio="none"
                aria-hidden="true"
              >
                <defs>
                  <linearGradient id="{heroUid}-w{i}" x1="{art.x1}%" y1="0%" x2="{art.x2}%" y2="100%">
                    <stop offset="0%" stop-color={art.light} />
                    <stop offset="100%" stop-color={art.dark} />
                  </linearGradient>
                </defs>
                <rect width="160" height="90" fill="url(#{heroUid}-w{i})" />
                {#each art.orbs as orb, oi (oi)}
                  <circle
                    cx={orb.cx * 1.6}
                    cy={orb.cy * 0.95}
                    r={orb.r}
                    fill="#ffffff"
                    fill-opacity={orb.alpha}
                    style="mix-blend-mode: screen"
                  />
                {/each}
              </svg>
            {:else}
              {#key useRaw}
                <img
                  class="bg"
                  class:on={i === safeIndex}
                  class:kb={perf.flags.kenBurns}
                  referrerpolicy="no-referrer"
                  sizes={useRaw ? undefined : a.sizes}
                  srcset={useRaw ? undefined : a.srcset}
                  src={useRaw ? rawUrl : a.src}
                  alt=""
                  loading={i === safeIndex ? 'eager' : 'lazy'}
                  fetchpriority={i === safeIndex ? 'high' : 'low'}
                  decoding="async"
                  draggable="false"
                  onerror={() => handleSlideImgError(item.id, raw, a)}
                />
              {/key}
            {/if}
          {/if}
        {/each}

        <!-- Multi-stop dark cinema vignette + Liquid Glass specular rim -->
        <div class="scrim-h" aria-hidden="true"></div>
        <div class="scrim-v" aria-hidden="true"></div>
        <div class="rim" aria-hidden="true"></div>

        {#key current.id}
          <div class="showcase-stage">
            <div class="meta">
              <h1>
                <a href={detailsHref(current)} onclick={(e) => guardClick(e, () => handoff(current, fgPosterEl))}>
                  {current.title}
                </a>
              </h1>

              <div class="pills">
                {#if ratingText}
                  <span class="pill pill-rating">
                    <svg class="star" width="11" height="11" viewBox="0 0 24 24" aria-hidden="true">
                      <path
                        fill="currentColor"
                        d="M12 2.4l2.93 5.94 6.56.95-4.74 4.62 1.12 6.53L12 17.35 6.13 20.44l1.12-6.53L2.51 9.29l6.56-.95z"
                      />
                    </svg>
                    {ratingText}
                  </span>
                {/if}
                {#if current.year > 0}
                  <span class="pill">{current.year}</span>
                {/if}
                {#if qualityTag}
                  <span class="pill" class:pill-accent={is4k}>{qualityTag}</span>
                {/if}
                {#if dub}
                  <span class="pill pill-dub">{dub}</span>
                {/if}
                {#each genres as g (g)}
                  <span class="pill pill-genre">{g}</span>
                {/each}
              </div>

              {#if current.synopsis}
                <p class="syn">{current.synopsis}</p>
              {/if}

              <div class="actions">
                <a
                  class="btn hero-play"
                  href={heroPlayHref(current)}
                  onclick={(e) => guardClick(e, () => handoff(current, fgPosterEl))}
                >
                  <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true">
                    <path fill="currentColor" d="M8 5.2v13.6L19 12z" />
                  </svg>
                  <span>Play</span>
                  {#if readyNow}
                    <span class="ready-dot" aria-hidden="true"></span>
                  {/if}
                </a>

                <button
                  type="button"
                  class="btn hero-glass"
                  aria-pressed={saved}
                  onclick={(e) => guardClick(e, () => myList.toggle(current))}
                >
                  {#if saved}
                    <svg width="17" height="17" viewBox="0 0 24 24" aria-hidden="true">
                      <path
                        fill="none"
                        stroke="currentColor"
                        stroke-width="2.4"
                        stroke-linecap="round"
                        stroke-linejoin="round"
                        d="M5 12.5l4.5 4.5L19 7.5"
                      />
                    </svg>
                  {:else}
                    <svg width="17" height="17" viewBox="0 0 24 24" aria-hidden="true">
                      <path
                        fill="none"
                        stroke="currentColor"
                        stroke-width="2.2"
                        stroke-linecap="round"
                        d="M12 5v14M5 12h14"
                      />
                    </svg>
                  {/if}
                  <span>My List</span>
                </button>
              </div>
            </div>

            <a
              class="fg-poster"
              href={detailsHref(current)}
              tabindex="-1"
              aria-hidden="true"
              onclick={(e) => guardClick(e, () => handoff(current, fgPosterEl))}
            >
              {#if fgUseGen}
                {@const art = generatedArtSpec(current.id || current.title)}
                <svg class="fg-art" viewBox="0 0 100 150" preserveAspectRatio="none" aria-hidden="true">
                  <defs>
                    <linearGradient id="{heroUid}-fg" x1="{art.x1}%" y1="0%" x2="{art.x2}%" y2="100%">
                      <stop offset="0%" stop-color={art.light} />
                      <stop offset="100%" stop-color={art.dark} />
                    </linearGradient>
                  </defs>
                  <rect width="100" height="150" fill="url(#{heroUid}-fg)" />
                </svg>
              {:else}
                {#key fgUseRaw}
                  <img
                    bind:this={fgPosterEl}
                    class="fg-art"
                    referrerpolicy="no-referrer"
                    sizes={fgUseRaw ? undefined : '220px'}
                    srcset={fgUseRaw ? undefined : fgAttrs.srcset}
                    src={fgUseRaw ? fgRawUrl : fgAttrs.src}
                    alt=""
                    decoding="async"
                    draggable="false"
                    onerror={() => handleFgPosterError(current.id, fgRaw, fgAttrs)}
                  />
                {/key}
              {/if}
              <span class="fg-rim" aria-hidden="true"></span>
            </a>
          </div>
        {/key}

        {#if n > 1}
          <div class="dots dots-wide" role="tablist" aria-label="Featured titles">
            {#each slides as item, i (item.id)}
              <button
                type="button"
                role="tab"
                aria-selected={i === safeIndex}
                aria-label="Item {i + 1} of {n}: {item.title}"
                class:on={i === safeIndex}
                style:--ms="{activeMs}ms"
                onclick={(e) => pickSlide(i, e.detail === 0 ? false : !window.matchMedia('(hover: hover)').matches)}
              ></button>
            {/each}
          </div>
        {/if}
      </div>
    {:else}
      <!-- Mobile / Portrait (< 768px): Cinetor Beta `Banner.kt` Showcase Deck -->
      <div class="deck-stage" class:dragging>
        {#each slides as item, i (item.id)}
          {@const offset = deckOffset(i, safeIndex, n, dragFraction)}
          {@const tf = deckCardTransform(offset, lowTier)}
          {#if tf.visible}
            {@const isFront = i === safeIndex}
            {@const raw = item.poster || item.backdrop}
            {@const rawUrl = stripResizeParam(raw)}
            {@const useRaw = Boolean(rawFallbackIds[item.id] && rawUrl)}
            {@const a = imgFor(raw, 'poster')}
            {@const useGen = !a.src || Boolean(failedIds[item.id])}
            {@const ratingText = formatRating(item.rating)}
            {@const dub = detectDubBadge(item)}
            {@const genres = displayGenres(item, 2)}
            {@const is4k = has4kBadge(item)}
            {@const qualityTag = is4k ? '4K' : isVideoSpace(item.space) ? 'HD' : null}
            {@const saved = myList.has(item)}
            {@const readyNow = isFront && isStreamReady(item)}

            <article
              class="deck-card"
              class:front={isFront}
              inert={!isFront || undefined}
              style:z-index={tf.zIndex}
              style:transform="translate3d({tf.translatePct}%, 0, 0) scale({tf.scale})"
              style:filter={tf.blurPx > 0 ? `blur(${tf.blurPx}px)` : 'none'}
            >
              {#if useGen}
                {@const art = generatedArtSpec(item.id || item.title)}
                <svg class="deck-art" viewBox="0 0 100 132" preserveAspectRatio="none" aria-hidden="true">
                  <defs>
                    <linearGradient id="{heroUid}-m{i}" x1="{art.x1}%" y1="0%" x2="{art.x2}%" y2="100%">
                      <stop offset="0%" stop-color={art.light} />
                      <stop offset="100%" stop-color={art.dark} />
                    </linearGradient>
                  </defs>
                  <rect width="100" height="132" fill="url(#{heroUid}-m{i})" />
                  {#each art.orbs as orb, oi (oi)}
                    <circle
                      cx={orb.cx}
                      cy={orb.cy * 1.3}
                      r={orb.r}
                      fill="#ffffff"
                      fill-opacity={orb.alpha}
                      style="mix-blend-mode: screen"
                    />
                  {/each}
                </svg>
              {:else}
                {#key useRaw}
                  <img
                    class="deck-art"
                    referrerpolicy="no-referrer"
                    sizes={useRaw ? undefined : '(max-width: 767px) 78vw, 360px'}
                    srcset={useRaw ? undefined : a.srcset}
                    src={useRaw ? rawUrl : a.src}
                    alt=""
                    loading={isFront ? 'eager' : 'lazy'}
                    fetchpriority={isFront ? 'high' : 'low'}
                    decoding="async"
                    draggable="false"
                    onerror={() => handleSlideImgError(item.id, raw, a)}
                  />
                {/key}
              {/if}

              <div class="deck-scrim" aria-hidden="true"></div>
              <div class="deck-rim" aria-hidden="true"></div>

              <div class="deck-meta">
                <h2 class="deck-title">
                  <a href={detailsHref(item)} onclick={(e) => guardClick(e, () => handoff(item))}>{item.title}</a>
                </h2>

                <div class="pills deck-pills">
                  {#if ratingText}
                    <span class="pill pill-rating">
                      <svg class="star" width="10" height="10" viewBox="0 0 24 24" aria-hidden="true">
                        <path
                          fill="currentColor"
                          d="M12 2.4l2.93 5.94 6.56.95-4.74 4.62 1.12 6.53L12 17.35 6.13 20.44l1.12-6.53L2.51 9.29l6.56-.95z"
                        />
                      </svg>
                      {ratingText}
                    </span>
                  {/if}
                  {#if item.year > 0}
                    <span class="pill">{item.year}</span>
                  {/if}
                  {#if qualityTag}
                    <span class="pill" class:pill-accent={is4k}>{qualityTag}</span>
                  {/if}
                  {#if dub}
                    <span class="pill pill-dub">{dub}</span>
                  {/if}
                  {#each genres as g (g)}
                    <span class="pill pill-genre">{g}</span>
                  {/each}
                </div>

                <div class="deck-actions">
                  <a
                    class="btn hero-play deck-btn"
                    href={heroPlayHref(item)}
                    onclick={(e) => guardClick(e, () => handoff(item))}
                  >
                    <svg width="16" height="16" viewBox="0 0 24 24" aria-hidden="true">
                      <path fill="currentColor" d="M8 5.2v13.6L19 12z" />
                    </svg>
                    <span>Play</span>
                    {#if readyNow}
                      <span class="ready-dot" aria-hidden="true"></span>
                    {/if}
                  </a>

                  <button
                    type="button"
                    class="btn hero-glass deck-btn"
                    aria-pressed={saved}
                    onclick={(e) => guardClick(e, () => myList.toggle(item))}
                  >
                    {#if saved}
                      <svg width="15" height="15" viewBox="0 0 24 24" aria-hidden="true">
                        <path
                          fill="none"
                          stroke="currentColor"
                          stroke-width="2.4"
                          stroke-linecap="round"
                          stroke-linejoin="round"
                          d="M5 12.5l4.5 4.5L19 7.5"
                        />
                      </svg>
                    {:else}
                      <svg width="15" height="15" viewBox="0 0 24 24" aria-hidden="true">
                        <path
                          fill="none"
                          stroke="currentColor"
                          stroke-width="2.2"
                          stroke-linecap="round"
                          d="M12 5v14M5 12h14"
                        />
                      </svg>
                    {/if}
                    <span>My List</span>
                  </button>
                </div>
              </div>

              {#if tf.dimAlpha > 0}
                <div class="deck-dim" style:opacity={tf.dimAlpha} aria-hidden="true"></div>
              {/if}
            </article>

            {#if !isFront}
              <button
                type="button"
                class="peek-hit"
                tabindex="-1"
                style:z-index={tf.zIndex + 1}
                style:transform="translate3d({tf.translatePct}%, 0, 0) scale({tf.scale})"
                aria-label="Bring {item.title} to front"
                onclick={(e) => {
                  if (Date.now() - swipedAt < 180) {
                    e.preventDefault();
                    return;
                  }
                  pickSlide(i, true);
                }}
              ></button>
            {/if}
          {/if}
        {/each}
      </div>

      {#if n > 1}
        <div class="dots dots-deck" role="tablist" aria-label="Featured titles">
          {#each slides as item, i (item.id)}
            <button
              type="button"
              role="tab"
              aria-selected={i === safeIndex}
              aria-label="Item {i + 1} of {n}: {item.title}"
              class:on={i === safeIndex}
              style:--ms="{activeMs}ms"
              onclick={() => pickSlide(i, true)}
            ></button>
          {/each}
        </div>
      {/if}
    {/if}
  </section>
{/if}

<style>
  .hero-wrap {
    position: relative;
    isolation: isolate;
    touch-action: pan-y;
    user-select: none;
    margin-top: 6px;
  }

  /* Ambient ArtTint halo spilling softly behind the hero stage */
  .hero-ambient {
    position: absolute;
    inset: -24px 0 -36px;
    z-index: -1;
    pointer-events: none;
    background:
      radial-gradient(68% 72% at 50% 38%, color-mix(in srgb, var(--tint) 38%, transparent), transparent 74%),
      radial-gradient(50% 55% at 20% 25%, color-mix(in srgb, var(--accent) 18%, transparent), transparent 70%);
    opacity: 0.92;
    transition: opacity calc(var(--dur-xlong) * var(--dur-scale)) var(--ease-standard);
  }
  :global([data-tier='low']) .hero-ambient {
    background: radial-gradient(65% 65% at 50% 35%, color-mix(in srgb, var(--tint) 24%, transparent), transparent 72%);
  }
  :global([data-theme='daylight']) .hero-ambient {
    opacity: 0.45;
  }

  /* ==========================================================================
     Desktop / Tablet / TV (>= 768px): Atmospheric Backdrop + Foreground Poster
     ========================================================================== */
  .showcase {
    position: relative;
    margin: 0 16px;
    height: clamp(350px, 42vw, 520px);
    border-radius: var(--radius-xl);
    overflow: hidden;
    isolation: isolate;
    background: color-mix(in srgb, var(--tint) 26%, #0b0b0e);
    box-shadow:
      0 28px 64px -26px color-mix(in srgb, var(--tint) 58%, rgb(0 0 0 / 0.78)),
      0 10px 26px -12px rgb(0 0 0 / 0.52);
  }
  @media (min-width: 1280px) {
    .showcase {
      height: clamp(390px, 38vw, 520px);
    }
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
      transform: scale(1.09) translate3d(1%, 0.6%, 0);
    }
    to {
      transform: scale(1) translate3d(0, 0, 0);
    }
  }

  .scrim-h {
    position: absolute;
    inset: 0;
    pointer-events: none;
    background: linear-gradient(
      90deg,
      rgb(6 6 9 / 0.95) 0%,
      rgb(6 6 9 / 0.8) 42%,
      rgb(6 6 9 / 0.42) 72%,
      rgb(6 6 9 / 0.24) 100%
    );
  }
  .scrim-v {
    position: absolute;
    inset: 0;
    pointer-events: none;
    background:
      linear-gradient(0deg, rgb(6 6 9 / 0.94) 0%, rgb(6 6 9 / 0.36) 46%, rgb(6 6 9 / 0.14) 100%),
      linear-gradient(0deg, color-mix(in srgb, var(--tint) 22%, transparent), transparent 44%);
  }
  .rim,
  .deck-rim,
  .fg-rim {
    position: absolute;
    inset: 0;
    border-radius: inherit;
    pointer-events: none;
    box-shadow:
      inset 0 1px 0 rgb(255 255 255 / 0.26),
      inset 0 0 0 1px rgb(255 255 255 / 0.14);
  }

  .showcase-stage {
    position: absolute;
    inset: 0;
    z-index: 2;
    display: flex;
    align-items: flex-end;
    justify-content: space-between;
    gap: clamp(20px, 3vw, 48px);
    padding: clamp(24px, 3.6vw, 44px) clamp(24px, 4vw, 48px) clamp(32px, 4.2vw, 48px);
    animation: rise calc(var(--dur-long) * var(--dur-scale)) var(--ease-emphasized) both;
  }
  @keyframes rise {
    from {
      opacity: 0;
      transform: translateY(10px);
    }
  }

  .meta {
    flex: 1;
    min-width: 0;
    max-width: 600px;
  }

  h1 {
    margin: 0 0 10px;
    font-size: clamp(1.8rem, 3.5vw, 2.95rem);
    line-height: 1.05;
    font-weight: 850;
    letter-spacing: -0.03em;
    color: #ffffff;
    text-wrap: balance;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }
  h1 a {
    color: #ffffff;
    text-decoration: none;
  }

  .syn {
    margin: 12px 0 18px;
    max-width: 56ch;
    font-size: 0.92rem;
    line-height: 1.48;
    color: rgb(255 255 255 / 0.8);
    display: -webkit-box;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }

  .fg-poster {
    position: relative;
    flex: none;
    align-self: center;
    width: clamp(144px, 15.5vw, 214px);
    aspect-ratio: 2 / 3;
    border-radius: 18px;
    overflow: hidden;
    background: #101014;
    box-shadow:
      0 26px 52px -18px rgb(0 0 0 / 0.82),
      0 0 42px -18px color-mix(in srgb, var(--tint) 65%, transparent);
    transform: translateZ(0);
    transition: transform calc(var(--dur-medium) * var(--dur-scale)) var(--ease-emphasized);
  }
  .fg-poster:hover {
    transform: translateY(-3px) scale(1.02);
  }
  .fg-art {
    width: 100%;
    height: 100%;
    display: block;
    object-fit: cover;
  }

  /* ==========================================================================
     Shared Metadata Pills, Play Green Dot & Liquid Glass CTAs
     ========================================================================== */
  .pills {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 6px;
  }
  .pill {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 3px 9px;
    border-radius: var(--radius-pill);
    background: rgb(255 255 255 / 0.12);
    box-shadow: inset 0 0 0 1px rgb(255 255 255 / 0.18);
    color: rgb(255 255 255 / 0.94);
    font-size: 0.72rem;
    font-weight: 700;
    line-height: 1.3;
    letter-spacing: 0.01em;
    font-variant-numeric: tabular-nums;
  }
  .pill-accent {
    background: var(--accent);
    box-shadow: 0 4px 14px -4px var(--accent-glow);
    color: var(--on-accent);
    font-weight: 800;
    letter-spacing: 0.03em;
  }
  .pill-rating {
    background: rgb(8 8 11 / 0.78);
    box-shadow: inset 0 0 0 1px rgb(255 255 255 / 0.2);
    color: #ffffff;
  }
  .pill-rating .star {
    color: #ffb800;
  }
  .pill-dub {
    background: rgb(255 255 255 / 0.94);
    box-shadow: none;
    color: #09090b;
    font-weight: 800;
  }
  .pill-genre {
    background: rgb(255 255 255 / 0.09);
    color: rgb(255 255 255 / 0.86);
    font-weight: 600;
  }

  .actions {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 10px;
    margin-top: 14px;
  }
  .hero-play {
    color: var(--on-accent);
    background: linear-gradient(135deg, var(--accent), var(--accent-end));
    box-shadow:
      0 10px 28px -6px var(--accent-glow),
      inset 0 1px 0 rgb(255 255 255 / 0.25);
  }
  .hero-play:hover {
    transform: translateY(-1px);
  }
  .ready-dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: #22c55e;
    box-shadow: 0 0 8px rgb(34 197 94 / 0.85);
    flex: none;
  }
  .hero-glass {
    color: #ffffff;
    background: rgb(16 16 22 / 0.56);
    -webkit-backdrop-filter: blur(14px) saturate(150%);
    backdrop-filter: blur(14px) saturate(150%);
    box-shadow:
      inset 0 1px 0 rgb(255 255 255 / 0.24),
      inset 0 0 0 1px rgb(255 255 255 / 0.2);
  }
  .hero-glass:hover {
    background: rgb(26 26 34 / 0.7);
  }
  :global([data-blur='none']) .hero-glass {
    -webkit-backdrop-filter: none;
    backdrop-filter: none;
    background: rgb(18 18 24 / 0.86);
  }
  .hero-play:focus-visible,
  .hero-glass:focus-visible {
    outline: 2px solid #ffffff;
    outline-offset: 3px;
  }

  /* ==========================================================================
     Mobile / Portrait (< 768px): Cinetor Beta `Banner.kt` Showcase Deck
     ========================================================================== */
  .deck-stage {
    --deck-w: min(76vw, 356px);
    position: relative;
    width: 100%;
    aspect-ratio: 1 / 0.99;
    max-height: 490px;
    min-height: 360px;
    overflow: hidden;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  .deck-card,
  .peek-hit {
    position: absolute;
    width: var(--deck-w);
    aspect-ratio: 0.76;
    border-radius: 20px;
    transition:
      transform 460ms var(--ease-emphasized),
      filter 360ms var(--ease-standard);
  }
  .deck-stage.dragging .deck-card,
  .deck-stage.dragging .peek-hit {
    transition: none;
  }

  .deck-card {
    overflow: hidden;
    background: #101014;
    box-shadow: 0 24px 48px -18px rgb(0 0 0 / 0.78);
  }
  .deck-card.front {
    box-shadow:
      0 26px 54px -20px color-mix(in srgb, var(--tint) 58%, rgb(0 0 0 / 0.85)),
      0 10px 24px -12px rgb(0 0 0 / 0.65);
  }

  .peek-hit {
    padding: 0;
    border: 0;
    background: transparent;
    cursor: pointer;
  }

  .deck-art {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
  .deck-scrim {
    position: absolute;
    inset: 0;
    pointer-events: none;
    background: linear-gradient(
      180deg,
      rgb(6 6 9 / 0.06) 0%,
      transparent 34%,
      rgb(6 6 9 / 0.76) 66%,
      rgb(6 6 9 / 0.96) 100%
    );
  }
  .deck-dim {
    position: absolute;
    inset: 0;
    border-radius: inherit;
    background: #050507;
    pointer-events: none;
    transition: opacity 360ms var(--ease-standard);
  }

  .deck-meta {
    position: absolute;
    left: 14px;
    right: 14px;
    bottom: 14px;
    z-index: 2;
    display: flex;
    flex-direction: column;
    align-items: center;
    text-align: center;
  }
  .deck-title {
    margin: 0 0 8px;
    font-size: clamp(1.36rem, 5.8vw, 1.84rem);
    line-height: 1.06;
    font-weight: 850;
    letter-spacing: -0.025em;
    color: #ffffff;
    text-wrap: balance;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }
  .deck-title a {
    color: #ffffff;
    text-decoration: none;
  }
  .deck-pills {
    justify-content: center;
    gap: 5px;
    margin-bottom: 12px;
  }
  .deck-pills .pill {
    padding: 2px 7px;
    font-size: 0.66rem;
  }

  .deck-actions {
    width: 100%;
    display: flex;
    align-items: center;
    gap: 10px;
  }
  .deck-btn {
    flex: 1;
    min-height: 44px;
    padding: 0 12px;
    font-size: 0.88rem;
    border-radius: 12px;
  }

  /* ==========================================================================
     Capsule Pagination Dots (composite-only transform animation)
     ========================================================================== */
  .dots {
    display: flex;
    align-items: center;
    gap: 6px;
    z-index: 3;
  }
  .dots-wide {
    position: absolute;
    right: clamp(24px, 4vw, 48px);
    bottom: 14px;
  }
  .dots-deck {
    justify-content: center;
    margin-top: 10px;
    padding: 2px 16px;
  }

  .dots button {
    position: relative;
    width: 7px;
    height: 6px;
    padding: 0;
    border: 0;
    border-radius: 99px;
    background: color-mix(in srgb, var(--text-primary) 30%, transparent);
    cursor: pointer;
    overflow: hidden;
    transition: background calc(var(--dur-short) * var(--dur-scale)) var(--ease-standard);
  }
  .dots-wide button {
    background: rgb(255 255 255 / 0.34);
  }
  .dots button.on {
    width: 24px;
    background: color-mix(in srgb, var(--text-primary) 22%, transparent);
  }
  .dots-wide button.on {
    background: rgb(255 255 255 / 0.26);
  }
  .dots button.on::after {
    content: '';
    position: absolute;
    inset: 0;
    border-radius: inherit;
    background: var(--accent);
    transform-origin: left;
    animation: fill var(--ms, 5000ms) linear both;
  }
  .hero-wrap:hover .dots button.on::after,
  .hero-wrap:focus-within .dots button.on::after,
  .hero-wrap[data-paused='true'] .dots button.on::after {
    animation-play-state: paused;
  }
  @keyframes fill {
    from {
      transform: scaleX(0);
    }
    to {
      transform: scaleX(1);
    }
  }
</style>
