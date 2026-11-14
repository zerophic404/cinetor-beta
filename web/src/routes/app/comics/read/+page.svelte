<script lang="ts">
  import { onMount, tick } from 'svelte';
  import { page } from '$app/state';
  import { base } from '$app/paths';
  import { goto } from '$app/navigation';
  import { repo, img } from '$lib/data/repo';
  import type { Chapter, Item } from '$lib/data/types';
  import { activeProfileId } from '$lib/data/library.svelte';
  import { localKV } from '$lib/data/kv';
  import { perf } from '$lib/perf/tier.svelte';
  import { MODE_LABEL, ReadProgress, chapterIndex, defaultMode, effectiveMode, keyStep, preloadRange, tapZone, type ReadMode } from '$lib/comics/reader';

  const id = $derived(page.url.searchParams.get('id') ?? '');
  const chParam = $derived(page.url.searchParams.get('ch'));
  const progress = new ReadProgress(localKV(), activeProfileId());

  let item = $state.raw<Item | null>(null);
  let status = $state<'loading' | 'ready' | 'missing'>('loading');
  let chIdx = $state(0);
  let pageIdx = $state(0);
  let override = $state<ReadMode | null>(null);
  let chrome = $state(true);
  let atEnd = $state(false);
  /** Vertical mode: chapters stacked so far (next chapter auto-appends at the bottom). */
  let stacked = $state<number[]>([]);
  let scroller = $state<HTMLDivElement>();

  const chapters = $derived<Chapter[]>(item?.pages ?? []);
  const chapter = $derived<Chapter | undefined>(chapters[chIdx]);
  const total = $derived(chapter?.pages.length ?? 0);
  const mode = $derived<ReadMode>(item ? effectiveMode(item, override) : 'ltr');

  $effect(() => {
    const want = id;
    const ch = chParam;
    let alive = true;
    status = 'loading';
    repo.item(want, 'COMICS').then(
      (it) => {
        if (!alive) return;
        if (!it || !it.pages?.length) {
          status = 'missing';
          return;
        }
        item = it;
        override = progress.getMode(it.id);
        const i = chapterIndex(it.pages, ch ?? progress.last(it.id)?.chapterId);
        openChapter(i, progress.get(it.id, it.pages[i].id)?.page ?? 0);
        status = 'ready';
      },
      () => alive && (status = 'missing')
    );
    return () => {
      alive = false;
    };
  });

  function openChapter(i: number, p = 0) {
    chIdx = i;
    pageIdx = Math.max(0, Math.min(p, (chapters[i]?.pages.length ?? 1) - 1));
    stacked = [i];
    atEnd = false;
    if (mode === 'vertical') void tick().then(() => scrollToPage(i, pageIdx, false));
  }

  // Save progress for the visible page of the current chapter.
  $effect(() => {
    if (!item || !chapter || status !== 'ready') return;
    progress.save(item.id, chapter.id, pageIdx, total);
  });

  // Preload the next pages (3, LOW tier 1), incl. the first pages of the next chapter at the end.
  $effect(() => {
    if (!chapter) return;
    const tier = perf.flags.tier;
    for (const i of preloadRange(pageIdx, total, tier)) new Image().src = img(chapter.pages[i]);
    const nextCh = chapters[chIdx + 1];
    if (nextCh && pageIdx >= total - 2) new Image().src = img(nextCh.pages[0]);
  });

  function go(step: -1 | 1) {
    if (!chapter) return;
    const n = pageIdx + step;
    if (n >= total) {
      // Next chapter auto-loads.
      if (chIdx + 1 < chapters.length) openChapter(chIdx + 1, 0);
      else atEnd = true;
      return;
    }
    if (n < 0) {
      if (chIdx > 0) openChapter(chIdx - 1, (chapters[chIdx - 1]?.pages.length ?? 1) - 1);
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
    if (Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(e.clientY - py0)) {
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
    const t = e.target as HTMLElement | null;
    if (t?.closest('input, textarea, select, [contenteditable="true"]') || document.querySelector('dialog[open]')) return;
    if (e.key === 'Escape') {
      e.preventDefault();
      back();
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

  const MODES: ReadMode[] = ['rtl', 'ltr', 'vertical'];
  /** Picking the title's default mode clears the override. */
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
    // Auto-hide the chrome after a moment of reading.
    const t = setTimeout(() => (chrome = false), 2500);
    return () => clearTimeout(t);
  });
</script>

<svelte:window onkeydown={onKey} />
<svelte:head><title>{chapter ? `Ch. ${chapter.n} · ` : ''}{item?.title ?? 'Reader'} · Cinetor</title></svelte:head>

<div class="reader" class:chrome data-mode={mode}>
  {#if status === 'loading'}
    <div class="center"><span class="spin" aria-label="Loading"></span></div>
  {:else if status === 'missing' || !item || !chapter}
    <div class="center"><p>Comic not found.</p><button class="btn btn-ghost" onclick={back}>Back</button></div>
  {:else if mode === 'vertical'}
    <div class="vscroll" bind:this={scroller}>
      <button class="vtap" onclick={() => (chrome = !chrome)} aria-label="Toggle reader controls">
        {#each stacked as ci (ci)}
          {@const c = chapters[ci]}
          {#each c.pages as p, pi (p + pi)}
            <img data-ch={ci} data-p={pi} use:observePage src={img(p)} alt="Chapter {c.n} page {pi + 1}" loading={pi < 2 ? 'eager' : 'lazy'} decoding="async" width="800" height="1200" />
          {/each}
          <span class="sep">End of chapter {c.n}{chapters[ci + 1] ? ` — next: ${chapters[ci + 1].title}` : ''}</span>
        {/each}
      </button>
      <div class="sentinel" use:sentinel aria-hidden="true"></div>
      {#if atEnd}<p class="end">You're all caught up.</p>{/if}
    </div>
  {:else}
    <button class="paged" onpointerdown={down} onpointerup={up} onclick={tap} aria-label="Page {pageIdx + 1} of {total}. Tap sides to turn pages, centre for controls">
      {#key `${chIdx}-${pageIdx}`}
        <img src={img(chapter.pages[pageIdx])} alt="Chapter {chapter.n} page {pageIdx + 1}" decoding="async" width="800" height="1200" />
      {/key}
    </button>
    {#if atEnd}
      <div class="endcard glass">
        <b>You're all caught up</b>
        <button class="btn btn-ghost" onclick={back}>Back to chapters</button>
      </div>
    {/if}
  {/if}

  {#if item && chapter}
    <header class="top glass glass-bar" aria-hidden={!chrome}>
      <button class="ic" onclick={back} aria-label="Back" tabindex={chrome ? 0 : -1}>‹</button>
      <div class="ttl"><b>{item.title}</b><small>Ch. {chapter.n} · {chapter.title}</small></div>
      <div class="modes" role="radiogroup" aria-label="Reading mode">
        {#each MODES as m (m)}
          <button class="chip" role="radio" aria-checked={mode === m} class:active={mode === m} title={MODE_LABEL[m]} tabindex={chrome ? 0 : -1} onclick={() => pickMode(m)}>
            {m === 'rtl' ? '⇠ RTL' : m === 'ltr' ? 'LTR ⇢' : '⇣ Vertical'}
          </button>
        {/each}
      </div>
    </header>
    <footer class="bottom glass glass-bar" aria-hidden={!chrome}>
      <button class="ic" disabled={chIdx === 0} onclick={() => openChapter(chIdx - 1)} aria-label="Previous chapter" tabindex={chrome ? 0 : -1}>⏮</button>
      <input
        type="range"
        min="1"
        max={total}
        value={pageIdx + 1}
        dir={mode === 'rtl' ? 'rtl' : 'ltr'}
        oninput={(e) => slide(Number(e.currentTarget.value))}
        aria-label="Page"
        tabindex={chrome ? 0 : -1}
      />
      <span class="pg">{pageIdx + 1} / {total}</span>
      <button class="ic" disabled={chIdx >= chapters.length - 1} onclick={() => openChapter(chIdx + 1)} aria-label="Next chapter" tabindex={chrome ? 0 : -1}>⏭</button>
    </footer>
  {/if}
</div>

<style>
  .reader {
    position: fixed;
    inset: 0;
    z-index: 60;
    background: #000;
    color: #fff;
  }
  .center {
    height: 100%;
    display: grid;
    place-content: center;
    gap: 12px;
    text-align: center;
  }
  .spin {
    width: 36px;
    height: 36px;
    border-radius: 50%;
    border: 3px solid rgb(255 255 255 / 0.2);
    border-top-color: var(--accent);
    animation: spin 0.9s linear infinite;
  }
  @keyframes spin {
    to { transform: rotate(360deg); }
  }
  .paged {
    position: absolute;
    inset: 0;
    display: grid;
    place-items: center;
    padding: 0;
    border: 0;
    background: none;
    cursor: pointer;
    touch-action: pan-y pinch-zoom;
  }
  .paged img {
    max-width: 100%;
    max-height: 100dvh;
    width: auto;
    height: auto;
    object-fit: contain;
    animation: fade 0.18s ease-out;
    user-select: none;
    -webkit-user-drag: none;
  }
  @keyframes fade {
    from { opacity: 0.4; }
  }
  .vscroll {
    position: absolute;
    inset: 0;
    overflow-y: auto;
    overscroll-behavior: contain;
  }
  .vtap {
    display: grid;
    justify-items: center;
    width: 100%;
    padding: 0;
    border: 0;
    background: none;
    cursor: default;
  }
  .vtap img {
    display: block;
    width: min(100%, 800px);
    height: auto;
    min-height: 200px;
    background: #111;
  }
  .sep {
    display: block;
    padding: 28px 16px;
    color: var(--text-secondary);
    font-size: 0.85rem;
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
    padding: 22px;
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
    padding: 8px 10px;
    border-radius: 20px;
    transition: opacity 0.25s, translate 0.25s var(--ease-emphasized, ease);
  }
  .top {
    top: calc(10px + var(--safe-t, 0px));
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
  }
  .modes {
    display: flex;
    gap: 4px;
    flex-wrap: wrap;
    justify-content: flex-end;
  }
  .modes .chip {
    min-height: 30px;
    padding: 0 10px;
    font-size: 0.75rem;
  }
  .ic {
    flex: none;
    width: 40px;
    height: 40px;
    border: 0;
    border-radius: 50%;
    background: none;
    color: inherit;
    font-size: 1.3rem;
    cursor: pointer;
  }
  .ic:disabled {
    opacity: 0.3;
  }
  input[type='range'] {
    flex: 1;
    accent-color: var(--accent);
  }
  .pg {
    font-variant-numeric: tabular-nums;
    font-size: 0.8rem;
    min-width: 56px;
    text-align: center;
  }
  @media (max-width: 600px) {
    .modes .chip {
      padding: 0 8px;
    }
    .top {
      flex-wrap: wrap;
    }
  }
</style>
