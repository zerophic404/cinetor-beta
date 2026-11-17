<script lang="ts">
  import { onMount, tick } from 'svelte';
  import { page } from '$app/state';
  import { base } from '$app/paths';
  import { goto } from '$app/navigation';
  import { img } from '$lib/data/repo';
  import { catalog, comicsSource } from '$lib/data/app-catalog';
  import type { Chapter, Item } from '$lib/data/types';
  import { activeProfileId } from '$lib/data/library.svelte';
  import { localKV } from '$lib/data/kv';
  import { perf } from '$lib/perf/tier.svelte';
  import { isMangadexId, type ChapterPageSet } from '$lib/comics/mangadex';
  import {
    MODE_LABEL,
    ReadProgress,
    chapterIndex,
    defaultMode,
    effectiveMode,
    keyStep,
    preloadRange,
    resolvePageSrc,
    tapZone,
    verticalScrollStep,
    type ReadMode
  } from '$lib/comics/reader';

  const id = $derived(page.url.searchParams.get('id') ?? '');
  const chParam = $derived(page.url.searchParams.get('ch'));
  const progress = new ReadProgress(localKV(), activeProfileId());

  let item = $state.raw<Item | null>(null);
  let loadStatus = $state<'loading' | 'ready' | 'missing'>('loading');
  let chIdx = $state(0);
  let pageIdx = $state(0);
  let override = $state<ReadMode | null>(null);
  let dataSaver = $state(progress.getDataSaver());
  let zoom = $state(1);
  let chrome = $state(true);
  let atEnd = $state(false);
  /** Vertical mode: chapters stacked so far (next chapter auto-appends at the bottom). */
  let stacked = $state<number[]>([]);
  let scroller = $state<HTMLDivElement>();

  const chapters = $derived<Chapter[]>(item?.pages ?? []);
  const chapter = $derived<Chapter | undefined>(chapters[chIdx]);

  /** Remote chapters resolve primary + data-saver backup URLs on open. */
  let loadedSets = $state<Record<string, ChapterPageSet>>({});
  let pageAttempts = $state<Record<string, number>>({});
  let refreshedChapters = $state<Record<string, boolean>>({});

  function pageSetFor(c: Chapter | undefined): ChapterPageSet | undefined {
    if (!c) return undefined;
    if (c.pages.length) return { primary: c.pages, backup: c.pages };
    return loadedSets[c.id];
  }

  function primaryPagesFor(c: Chapter | undefined): string[] {
    const s = pageSetFor(c);
    if (!s) return [];
    return dataSaver && s.backup.length ? s.backup : s.primary;
  }

  function pageUrlAt(c: Chapter | undefined, pi: number): string | null {
    const s = pageSetFor(c);
    if (!c || !s || pi < 0 || pi >= s.primary.length) return null;
    const attempt = pageAttempts[`${c.id}:${pi}`] ?? 0;
    const resolved = resolvePageSrc(s.primary[pi] ?? '', s.backup[pi], attempt, dataSaver);
    return resolved ? img(resolved) : null;
  }

  const curPages = $derived(primaryPagesFor(chapter));
  const total = $derived(curPages.length);
  const chBusy = $derived(!!chapter && !chapter.pages.length && loadedSets[chapter.id] === undefined);
  const chGone = $derived(!!chapter && !chapter.pages.length && loadedSets[chapter.id]?.primary.length === 0);
  const mode = $derived<ReadMode>(item ? effectiveMode(item, override) : 'ltr');

  /** Resolve one chapter's primary + backup pages (never throws; empty = "chapter unavailable"). */
  async function ensure(ci: number, fresh = false): Promise<void> {
    const it = item;
    const c = chapters[ci];
    if (!it || !c || c.pages.length) return;
    if (!fresh && loadedSets[c.id] !== undefined) return;
    if (fresh) {
      const nextSets = { ...loadedSets };
      delete nextSets[c.id];
      loadedSets = nextSets;
    }
    let resolvedSet: ChapterPageSet = { primary: [], backup: [] };
    if (isMangadexId(it.id)) {
      resolvedSet = await comicsSource.chapterPageSet(c.id, undefined, { fresh }).catch(() => ({ primary: [], backup: [] }));
    } else {
      const urls = await catalog.comicPages(it, c).catch(() => [] as string[]);
      resolvedSet = { primary: urls, backup: urls };
    }
    loadedSets = { ...loadedSets, [c.id]: resolvedSet };
  }

  function reloadChapter(ci: number) {
    const c = chapters[ci];
    if (!c) return;
    const cleaned: Record<string, number> = {};
    for (const [k, v] of Object.entries(pageAttempts)) {
      if (!k.startsWith(`${c.id}:`)) cleaned[k] = v;
    }
    pageAttempts = cleaned;
    refreshedChapters = { ...refreshedChapters, [c.id]: true };
    void ensure(ci, true);
  }

  function onPageImgError(c: Chapter, ci: number, pi: number) {
    const key = `${c.id}:${pi}`;
    const nextAttempt = (pageAttempts[key] ?? 0) + 1;
    pageAttempts = { ...pageAttempts, [key]: nextAttempt };
    const s = pageSetFor(c);
    if (s && resolvePageSrc(s.primary[pi] ?? '', s.backup[pi], nextAttempt, dataSaver) === null && !refreshedChapters[c.id]) {
      reloadChapter(ci);
    }
  }

  function retrySinglePage(c: Chapter, pi: number) {
    const key = `${c.id}:${pi}`;
    pageAttempts = { ...pageAttempts, [key]: 0 };
  }

  function toggleDataSaver() {
    dataSaver = !dataSaver;
    progress.setDataSaver(dataSaver);
    pageAttempts = {};
  }

  function adjustZoom(delta: number) {
    zoom = delta === 0 ? 1 : Math.round(Math.min(2.5, Math.max(1, zoom + delta)) * 100) / 100;
  }

  $effect(() => {
    void ensure(chIdx);
    // Warm the next chapter so swiping past the end is instant.
    if (chapters[chIdx + 1] && pageIdx >= total - 2) void ensure(chIdx + 1);
  });
  $effect(() => {
    for (const ci of stacked) void ensure(ci);
  });

  $effect(() => {
    const want = id;
    const ch = chParam;
    let alive = true;
    loadStatus = 'loading';
    catalog.item(want, 'COMICS').then(
      (it) => {
        if (!alive) return;
        if (!it || !it.pages?.length) {
          loadStatus = 'missing';
          return;
        }
        item = it;
        override = progress.getMode(it.id);
        const i = chapterIndex(it.pages, ch ?? progress.last(it.id)?.chapterId);
        openChapter(i, progress.get(it.id, it.pages[i].id)?.page ?? 0);
        loadStatus = 'ready';
      },
      () => alive && (loadStatus = 'missing')
    );
    return () => {
      alive = false;
    };
  });

  function openChapter(i: number, p = 0) {
    if (i < 0 || i >= chapters.length) return;
    chIdx = i;
    pageIdx = Math.max(0, p);
    stacked = [i];
    atEnd = false;
    if (mode === 'vertical') void tick().then(() => scrollToPage(i, pageIdx, false));
  }

  // Clamp the page once a lazily-resolved chapter knows its length.
  $effect(() => {
    if (total > 0 && pageIdx > total - 1) pageIdx = total - 1;
  });

  // Save progress for the visible page of the current chapter.
  $effect(() => {
    if (!item || !chapter || loadStatus !== 'ready' || total === 0) return;
    progress.save(item.id, chapter.id, pageIdx, total);
  });

  // Preload the next pages (3, LOW tier 1), incl. the first page of the next chapter near the end.
  $effect(() => {
    if (!chapter || !total) return;
    const tier = perf.flags.tier;
    for (const i of preloadRange(pageIdx, total, tier)) {
      const nextUrl = pageUrlAt(chapter, i);
      if (nextUrl) {
        const pre = new Image();
        pre.referrerPolicy = 'no-referrer';
        pre.src = nextUrl;
      }
    }
    const nextCh = chapters[chIdx + 1];
    const nextFirst = pageUrlAt(nextCh, 0);
    if (nextFirst && pageIdx >= total - 2) {
      const pre = new Image();
      pre.referrerPolicy = 'no-referrer';
      pre.src = nextFirst;
    }
  });

  function go(step: -1 | 1) {
    if (!chapter) return;
    const n = pageIdx + step;
    if (total === 0 || n >= total) {
      if (chIdx + 1 < chapters.length) openChapter(chIdx + 1, 0);
      else atEnd = true;
      return;
    }
    if (n < 0) {
      if (chIdx > 0) openChapter(chIdx - 1, Math.max(0, primaryPagesFor(chapters[chIdx - 1]).length - 1));
      return;
    }
    atEnd = false;
    pageIdx = n;
  }

  // ---- paged input: tap zones + swipe ----
  let px0 = 0;
  let py0 = 0;
  let swiped = false;
  function down(e: PointerEvent) {
    px0 = e.clientX;
    py0 = e.clientY;
    swiped = false;
  }
  function up(e: PointerEvent) {
    const dx = e.clientX - px0;
    if (zoom <= 1.05 && Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(e.clientY - py0)) {
      swiped = true;
      const forward = dx < 0 ? mode !== 'rtl' : mode === 'rtl';
      go(forward ? 1 : -1);
    }
  }
  function tap(e: MouseEvent) {
    if (swiped) return;
    const r = (e.currentTarget as HTMLElement).getBoundingClientRect();
    const z = tapZone(e.clientX - r.left, r.width, mode);
    if (z === 'menu') chrome = !chrome;
    else go(z === 'next' ? 1 : -1);
  }

  function onKey(e: KeyboardEvent) {
    if (e.defaultPrevented || e.ctrlKey || e.metaKey || e.altKey) return;
    if (e.target instanceof Element && e.target.closest('input, textarea, select, [contenteditable="true"]')) return;
    if (typeof document !== 'undefined' && document.querySelector('dialog[open]')) return;

    if (e.key === 'Escape') {
      e.preventDefault();
      back();
      return;
    }
    if (e.key === '[' && chIdx > 0) {
      e.preventDefault();
      openChapter(chIdx - 1, 0);
      return;
    }
    if (e.key === ']' && chIdx + 1 < chapters.length) {
      e.preventDefault();
      openChapter(chIdx + 1, 0);
      return;
    }
    if (e.key === '+' || e.key === '=') {
      e.preventDefault();
      adjustZoom(0.25);
      return;
    }
    if (e.key === '-') {
      e.preventDefault();
      adjustZoom(-0.25);
      return;
    }
    if (e.key === '0') {
      e.preventDefault();
      adjustZoom(0);
      return;
    }
    if (mode === 'vertical') {
      const vStep = verticalScrollStep(e.key, e.shiftKey);
      if (vStep && scroller) {
        e.preventDefault();
        scroller.scrollBy({ top: vStep * Math.round(scroller.clientHeight * 0.82), behavior: 'smooth' });
        return;
      }
      if (e.key === 'Home' && scroller) {
        e.preventDefault();
        scroller.scrollTo({ top: 0, behavior: 'smooth' });
        return;
      }
      if (e.key === 'End' && scroller) {
        e.preventDefault();
        scroller.scrollTo({ top: scroller.scrollHeight, behavior: 'smooth' });
        return;
      }
      return;
    }
    const s = keyStep(e.key, mode);
    if (!s) return;
    e.preventDefault();
    go(s);
  }

  // ---- vertical: track visible page, auto-append next chapter ----
  function scrollToPage(ci: number, pi: number, smooth = true) {
    const el = scroller?.querySelector<HTMLElement>(`[data-ch="${ci}"][data-p="${pi}"]`);
    el?.scrollIntoView({ block: 'start', behavior: smooth ? 'smooth' : 'auto' });
  }

  function observePage(node: HTMLElement) {
    const io = new IntersectionObserver(
      (entries) => {
        for (const en of entries) {
          if (!en.isIntersecting) continue;
          const ci = Number(node.dataset.ch);
          const pi = Number(node.dataset.p);
          if (ci !== chIdx) chIdx = ci;
          pageIdx = pi;
        }
      },
      { root: scroller, rootMargin: '-45% 0px -45% 0px' }
    );
    io.observe(node);
    return { destroy: () => io.disconnect() };
  }

  function sentinel(node: HTMLElement) {
    const io = new IntersectionObserver(
      (entries) => {
        if (!entries.some((e) => e.isIntersecting)) return;
        const lastCi = stacked[stacked.length - 1];
        if (lastCi + 1 < chapters.length) stacked = [...stacked, lastCi + 1];
        else atEnd = true;
      },
      { root: scroller, rootMargin: '0px 0px 1200px 0px' }
    );
    io.observe(node);
    return { destroy: () => io.disconnect() };
  }

  function setMode(m: ReadMode | null) {
    if (!item) return;
    progress.setMode(item.id, m);
    override = m;
    stacked = [chIdx];
    if (effectiveMode(item, m) === 'vertical') void tick().then(() => scrollToPage(chIdx, pageIdx, false));
  }

  const MODES: { id: ReadMode; label: string }[] = [
    { id: 'rtl', label: 'RTL' },
    { id: 'ltr', label: 'LTR' },
    { id: 'vertical', label: 'Webtoon' }
  ];

  function pickMode(m: ReadMode) {
    if (item) setMode(m === defaultMode(item) ? null : m);
  }

  function slide(v: number) {
    const p = Math.max(0, Math.min(total - 1, v - 1));
    if (mode === 'vertical') scrollToPage(chIdx, p);
    else pageIdx = p;
  }

  function back() {
    if (history.length > 1) history.back();
    else goto(`${base}/app/comics/title?id=${encodeURIComponent(id)}`);
  }

  onMount(() => {
    const t = setTimeout(() => (chrome = false), 2500);
    return () => clearTimeout(t);
  });
</script>

<svelte:window onkeydown={onKey} />
<svelte:head><title>{chapter ? `Ch. ${chapter.n} · ` : ''}{item?.title ?? 'Reader'} · Cinetor</title></svelte:head>

<div class="reader" class:chrome data-mode={mode}>
  {#if loadStatus === 'loading'}
    <div class="center"><span class="spin" aria-label="Loading"></span></div>
  {:else if loadStatus === 'missing' || !item || !chapter}
    <div class="center">
      <p>Comic not found.</p>
      <button type="button" class="btn btn-ghost" onclick={back}>Back</button>
    </div>
  {:else if mode === 'vertical'}
    <div class="vscroll" bind:this={scroller}>
      <div class="vstrip" style:--strip-w="{Math.round(800 * zoom)}px">
        {#each stacked as ci (ci)}
          {@const c = chapters[ci]}
          {@const pgs = primaryPagesFor(c)}
          {#each pgs as _, pi (`${c.id}-${pi}`)}
            {@const srcUrl = pageUrlAt(c, pi)}
            <div class="vpage" data-ch={ci} data-p={pi} use:observePage>
              {#if srcUrl}
                <button type="button" class="page-tap" onclick={() => (chrome = !chrome)} aria-label="Toggle reader controls">
                  <img
                    src={srcUrl}
                    alt="Chapter {c.n} page {pi + 1}"
                    loading={pi < 2 ? 'eager' : 'lazy'}
                    decoding="async"
                    referrerpolicy="no-referrer"
                    width="800"
                    height="1200"
                    onerror={() => onPageImgError(c, ci, pi)}
                  />
                </button>
              {:else}
                <div class="page-err glass">
                  <p>Page {pi + 1} couldn't load</p>
                  <div class="gone-acts">
                    <button type="button" class="btn btn-ghost sm" onclick={() => retrySinglePage(c, pi)}>Retry page</button>
                    <button type="button" class="btn btn-ghost sm" onclick={() => reloadChapter(ci)}>Reload chapter</button>
                  </div>
                </div>
              {/if}
            </div>
          {/each}
          {#if !pgs.length}
            <div class="sep-card glass">
              {#if loadedSets[c.id] === undefined}
                <span class="spin sm" aria-hidden="true"></span>
                <span>Loading chapter {c.n}…</span>
              {:else}
                <p>Chapter {c.n} is unavailable right now.</p>
                <button type="button" class="btn btn-ghost sm" onclick={() => reloadChapter(ci)}>Retry chapter</button>
              {/if}
            </div>
          {/if}
          <span class="sep">End of chapter {c.n}{chapters[ci + 1] ? ` · Next: ${chapters[ci + 1].title}` : ''}</span>
        {/each}
      </div>
      <div class="sentinel" use:sentinel aria-hidden="true"></div>
      {#if atEnd}<p class="end">You're all caught up.</p>{/if}
    </div>
  {:else if chBusy}
    <div class="center"><span class="spin" aria-label="Loading chapter"></span></div>
  {:else if chGone}
    <div class="center">
      <p>Chapter unavailable right now.</p>
      <div class="gone-acts">
        <button type="button" class="btn btn-ghost" onclick={() => reloadChapter(chIdx)}>Retry chapter</button>
        <button type="button" class="btn btn-ghost" disabled={chIdx === 0} onclick={() => openChapter(chIdx - 1)}>Previous</button>
        <button type="button" class="btn btn-primary" disabled={chIdx >= chapters.length - 1} onclick={() => openChapter(chIdx + 1)}>Next chapter</button>
      </div>
      <button type="button" class="btn btn-ghost" onclick={back}>Back to chapters</button>
    </div>
  {:else}
    {@const activeSrc = pageUrlAt(chapter, pageIdx)}
    {#if activeSrc}
      <button
        type="button"
        class="paged"
        onpointerdown={down}
        onpointerup={up}
        onclick={tap}
        aria-label="Page {pageIdx + 1} of {total}. Tap sides to turn pages, center for controls"
      >
        {#key `${chIdx}-${pageIdx}-${activeSrc}`}
          <img
            src={activeSrc}
            alt="Chapter {chapter.n} page {pageIdx + 1}"
            decoding="async"
            referrerpolicy="no-referrer"
            width="800"
            height="1200"
            style:transform={zoom > 1 ? `scale(${zoom})` : undefined}
            onerror={() => onPageImgError(chapter, chIdx, pageIdx)}
          />
        {/key}
      </button>
    {:else}
      <div class="center">
        <p>Page {pageIdx + 1} couldn't load.</p>
        <div class="gone-acts">
          <button type="button" class="btn btn-primary" onclick={() => retrySinglePage(chapter, pageIdx)}>Retry page</button>
          <button type="button" class="btn btn-ghost" onclick={() => reloadChapter(chIdx)}>Reload chapter</button>
        </div>
      </div>
    {/if}
    {#if atEnd}
      <div class="endcard glass glass-sheet">
        <b>You're all caught up</b>
        <button type="button" class="btn btn-ghost" onclick={back}>Back to chapters</button>
      </div>
    {/if}
  {/if}

  {#if item && chapter}
    <header class="top glass glass-bar" aria-hidden={!chrome}>
      <button type="button" class="ic" onclick={back} aria-label="Back" tabindex={chrome ? 0 : -1}>
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="m15 18-6-6 6-6" />
        </svg>
      </button>
      <div class="ttl">
        <b>{item.title}</b>
        <small>Ch. {chapter.n}{total > 0 ? ` · Page ${pageIdx + 1} of ${total}` : ''}</small>
      </div>
      <div class="controls-right">
        <button
          type="button"
          class="chip saver"
          class:active={dataSaver}
          aria-pressed={dataSaver}
          title="Use compressed data-saver pages"
          tabindex={chrome ? 0 : -1}
          onclick={toggleDataSaver}
        >
          Saver
        </button>
        <div class="zoom-box" role="group" aria-label="Zoom">
          <button type="button" class="zbtn" disabled={zoom <= 1} tabindex={chrome ? 0 : -1} onclick={() => adjustZoom(-0.25)} aria-label="Zoom out">−</button>
          <button type="button" class="zval" tabindex={chrome ? 0 : -1} onclick={() => adjustZoom(0)} title="Reset zoom">{Math.round(zoom * 100)}%</button>
          <button type="button" class="zbtn" disabled={zoom >= 2.5} tabindex={chrome ? 0 : -1} onclick={() => adjustZoom(0.25)} aria-label="Zoom in">+</button>
        </div>
        <div class="modes" role="radiogroup" aria-label="Reading mode">
          {#each MODES as m (m.id)}
            <button
              type="button"
              class="chip"
              role="radio"
              aria-checked={mode === m.id}
              class:active={mode === m.id}
              title={MODE_LABEL[m.id]}
              tabindex={chrome ? 0 : -1}
              onclick={() => pickMode(m.id)}
            >
              {m.label}
            </button>
          {/each}
        </div>
      </div>
    </header>

    <footer class="bottom glass glass-bar" aria-hidden={!chrome}>
      <button
        type="button"
        class="ic"
        disabled={chIdx === 0}
        onclick={() => openChapter(chIdx - 1)}
        aria-label="Previous chapter"
        tabindex={chrome ? 0 : -1}
      >
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M6 6v12M18 6l-8 6 8 6z" />
        </svg>
      </button>
      <input
        type="range"
        min="1"
        max={Math.max(1, total)}
        value={pageIdx + 1}
        dir={mode === 'rtl' ? 'rtl' : 'ltr'}
        oninput={(e) => slide(Number(e.currentTarget.value))}
        aria-label="Page"
        tabindex={chrome ? 0 : -1}
      />
      <span class="pg">{total > 0 ? `${pageIdx + 1} / ${total}` : '—'}</span>
      <button
        type="button"
        class="ic"
        disabled={chIdx >= chapters.length - 1}
        onclick={() => openChapter(chIdx + 1)}
        aria-label="Next chapter"
        tabindex={chrome ? 0 : -1}
      >
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M18 6v12M6 6l8 6-8 6z" />
        </svg>
      </button>
    </footer>
  {/if}
</div>

<style>
  .reader {
    position: fixed;
    inset: 0;
    z-index: 60;
    background: #070709;
    color: #fff;
  }
  .center {
    height: 100%;
    display: grid;
    place-content: center;
    gap: 12px;
    padding: 20px;
    text-align: center;
  }
  .spin {
    width: 36px;
    height: 36px;
    margin: 0 auto;
    border-radius: 50%;
    border: 3px solid rgb(255 255 255 / 0.2);
    border-top-color: var(--accent);
    animation: spin 0.9s linear infinite;
  }
  .spin.sm {
    width: 22px;
    height: 22px;
    border-width: 2.5px;
  }
  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }
  .gone-acts {
    display: flex;
    gap: 10px;
    justify-content: center;
    flex-wrap: wrap;
  }
  .btn.sm {
    min-height: 34px;
    padding: 0 14px;
    font-size: 0.8rem;
  }
  .paged {
    position: absolute;
    inset: 0;
    display: grid;
    place-items: center;
    overflow: auto;
    padding: 0;
    border: 0;
    background: none;
    cursor: pointer;
    touch-action: pan-x pan-y pinch-zoom;
  }
  .paged img {
    max-width: 100%;
    max-height: 100dvh;
    width: auto;
    height: auto;
    object-fit: contain;
    transform-origin: center center;
    animation: fade 0.16s ease-out;
    user-select: none;
    -webkit-user-drag: none;
  }
  @keyframes fade {
    from {
      opacity: 0.45;
    }
  }
  .vscroll {
    position: absolute;
    inset: 0;
    overflow: auto;
    overscroll-behavior: contain;
  }
  .vstrip {
    display: grid;
    justify-items: center;
    width: 100%;
    padding: 0;
  }
  .vpage {
    width: min(100%, var(--strip-w, 800px));
  }
  .page-tap {
    display: block;
    width: 100%;
    padding: 0;
    border: 0;
    background: none;
    cursor: default;
  }
  .page-tap img {
    display: block;
    width: 100%;
    height: auto;
    min-height: 180px;
    background: #101014;
    user-select: none;
    -webkit-user-drag: none;
  }
  .page-err,
  .sep-card {
    margin: 14px 16px;
    padding: 24px 16px;
    border-radius: 18px;
    display: grid;
    justify-items: center;
    gap: 10px;
    text-align: center;
  }
  .page-err p,
  .sep-card p {
    margin: 0;
    color: var(--text-secondary);
    font-size: 0.88rem;
  }
  .sep {
    display: block;
    padding: 28px 16px;
    color: var(--text-secondary);
    font-size: 0.85rem;
    text-align: center;
  }
  .sentinel {
    height: 4px;
  }
  .end {
    text-align: center;
    padding: 24px 0 120px;
    color: var(--text-secondary);
  }
  .endcard {
    position: absolute;
    left: 50%;
    top: 50%;
    translate: -50% -50%;
    display: grid;
    gap: 12px;
    padding: 24px;
    border-radius: 22px;
    text-align: center;
  }
  .top,
  .bottom {
    position: absolute;
    left: 10px;
    right: 10px;
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 8px 12px;
    border-radius: 20px;
    transition:
      opacity 0.22s ease,
      translate 0.22s var(--ease-emphasized, ease);
  }
  .top {
    top: calc(10px + var(--safe-t, 0px));
  }
  :global(html.tauri) .reader .top {
    top: calc(10px + var(--titlebar-h, 36px));
  }
  .bottom {
    bottom: calc(10px + var(--safe-b, 0px));
  }
  .reader:not(.chrome) .top {
    opacity: 0;
    translate: 0 -20px;
    pointer-events: none;
  }
  .reader:not(.chrome) .bottom {
    opacity: 0;
    translate: 0 20px;
    pointer-events: none;
  }
  .ttl {
    flex: 1;
    min-width: 0;
    display: grid;
  }
  .ttl b,
  .ttl small {
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .ttl small {
    color: var(--text-secondary);
    font-size: 0.75rem;
    font-variant-numeric: tabular-nums;
  }
  .controls-right {
    display: flex;
    align-items: center;
    gap: 6px;
    flex-wrap: wrap;
    justify-content: flex-end;
  }
  .zoom-box {
    display: inline-flex;
    align-items: center;
    border-radius: 999px;
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
  }
  .zbtn,
  .zval {
    height: 30px;
    border: 0;
    background: none;
    color: inherit;
    font-size: 0.76rem;
    font-weight: 700;
    font-variant-numeric: tabular-nums;
    cursor: pointer;
  }
  .zbtn {
    width: 28px;
  }
  .zbtn:disabled {
    opacity: 0.35;
    cursor: default;
  }
  .zval {
    padding: 0 4px;
    min-width: 40px;
  }
  .modes {
    display: flex;
    gap: 4px;
    flex-wrap: wrap;
    justify-content: flex-end;
  }
  .modes .chip,
  .chip.saver {
    min-height: 30px;
    padding: 0 10px;
    font-size: 0.74rem;
  }
  .ic {
    flex: none;
    width: 38px;
    height: 38px;
    display: grid;
    place-items: center;
    border: 0;
    border-radius: 50%;
    background: none;
    color: inherit;
    cursor: pointer;
  }
  .ic:hover:not(:disabled) {
    background: var(--glass-fill);
  }
  .ic:disabled {
    opacity: 0.3;
    cursor: default;
  }
  input[type='range'] {
    flex: 1;
    accent-color: var(--accent);
  }
  .pg {
    font-variant-numeric: tabular-nums;
    font-size: 0.8rem;
    min-width: 58px;
    text-align: center;
  }
  @media (max-width: 680px) {
    .top {
      flex-wrap: wrap;
    }
    .controls-right {
      width: 100%;
      justify-content: space-between;
    }
  }
</style>
