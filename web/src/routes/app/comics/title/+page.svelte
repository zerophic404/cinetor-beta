<script lang="ts">
  import { page } from '$app/state';
  import { base } from '$app/paths';
  import { img } from '$lib/data/repo';
  import { catalog, comicsSource } from '$lib/data/app-catalog';
  import type { Item } from '$lib/data/types';
  import { SkeletonBox, SkeletonChapterList } from '$lib/design/skeleton';
  import Sheet from '$lib/components/Sheet.svelte';
  import Poster from '$lib/components/Poster.svelte';
  import { myList, activeProfileId } from '$lib/data/library.svelte';
  import { localKV } from '$lib/data/kv';
  import { COMIC_CATS, type ComicCatId } from '$lib/comics/mangadex';
  import {
    COMIC_KIND_LABEL,
    MODE_LABEL,
    ReadProgress,
    comicKind,
    defaultMode,
    effectiveMode,
    filterChapters
  } from '$lib/comics/reader';

  const id = $derived(page.url.searchParams.get('id') ?? '');
  let item = $state.raw<Item | null>(null);
  let loadStatus = $state<'loading' | 'ready' | 'missing'>('loading');
  let chaptersReady = $state(false);
  let coverFailed = $state(false);
  let expandedSynopsis = $state(false);

  // Chapter list controls (matching Cinetor Beta)
  let sortOrder = $state<'desc' | 'asc'>('asc');
  let unreadOnly = $state(false);
  let chapterQuery = $state('');
  let showBackTop = $state(false);

  // Live Comics Search sheet
  let searchOpen = $state(false);
  let searchQuery = $state('');
  let searchCat = $state<ComicCatId | 'ALL'>('ALL');
  let searchBusy = $state(false);
  let searchHits = $state.raw<Item[]>([]);

  const progress = new ReadProgress(localKV(), activeProfileId());

  $effect(() => {
    const want = id;
    let alive = true;
    const peeked = want ? catalog.peek(want) : undefined;
    if (peeked && peeked.space === 'COMICS') {
      item = peeked;
      loadStatus = 'ready';
    } else {
      loadStatus = 'loading';
    }
    chaptersReady = false;
    coverFailed = false;
    expandedSynopsis = false;
    chapterQuery = '';
    catalog.item(want, 'COMICS').then(
      (it) => {
        if (!alive) return;
        if (!it || it.space !== 'COMICS') {
          loadStatus = 'missing';
          return;
        }
        item = it;
        loadStatus = 'ready';
        setTimeout(() => alive && (chaptersReady = true), 180);
      },
      () => alive && (loadStatus = 'missing')
    );
    return () => {
      alive = false;
    };
  });

  // Live MangaDex + local pool search with 280ms debounce
  $effect(() => {
    if (!searchOpen) return;
    const q = searchQuery.trim();
    const cat = searchCat;
    if (!q) {
      searchHits = [];
      searchBusy = false;
      return;
    }
    const ctl = new AbortController();
    searchBusy = true;
    const timer = setTimeout(async () => {
      try {
        const [remote, pool] = await Promise.all([
          comicsSource.search(q, ctl.signal, cat === 'ALL' ? undefined : cat).catch(() => [] as Item[]),
          catalog.pool(['COMICS']).catch(() => [] as Item[])
        ]);
        if (ctl.signal.aborted) return;
        const lower = q.toLowerCase();
        const localMatches = pool.filter(
          (it) =>
            (cat === 'ALL' || it.categoryId === cat) &&
            (it.title.toLowerCase().includes(lower) || (it.altTitle?.toLowerCase().includes(lower) ?? false))
        );
        const seen = new Set<string>();
        const merged: Item[] = [];
        for (const entry of [...remote, ...localMatches]) {
          if (seen.has(entry.id)) continue;
          seen.add(entry.id);
          merged.push(entry);
        }
        searchHits = merged;
      } finally {
        if (!ctl.signal.aborted) searchBusy = false;
      }
    }, 280);
    return () => {
      clearTimeout(timer);
      ctl.abort();
    };
  });

  function onScroll() {
    showBackTop = typeof window !== 'undefined' && window.scrollY > 640;
  }

  const chapters = $derived(item?.pages ?? []);
  const last = $derived(item ? progress.last(item.id) : undefined);
  const kind = $derived(item ? comicKind(item) : 'comic');
  const readMode = $derived(item ? effectiveMode(item, progress.getMode(item.id)) : 'ltr');
  const readHref = (chId: string) => `${base}/app/comics/read?id=${encodeURIComponent(id)}&ch=${encodeURIComponent(chId)}`;
  const lastChapter = $derived(last ? chapters.find((c) => c.id === last.chapterId) : undefined);
  const visibleChapters = $derived(
    item
      ? filterChapters(chapters, {
          order: sortOrder,
          query: chapterQuery,
          unreadOnly,
          isRead: (chId) => progress.isRead(item!.id, chId)
        })
      : []
  );
  const bookmarked = $derived(item ? myList.has(item) : false);
</script>

<svelte:window onscroll={onScroll} />
<svelte:head><title>{item?.title ?? 'Comic'} · Comics · Cinetor</title></svelte:head>

<div class="topbar">
  <a class="back-pill glass" href="{base}/app/comics">
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
      <path d="m15 18-6-6 6-6" />
    </svg>
    <span>Comics</span>
  </a>
  <button type="button" class="search-pill glass" onclick={() => (searchOpen = true)} aria-label="Search comics">
    <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true">
      <circle cx="10.5" cy="10.5" r="6.5" />
      <path d="m20 20-4.6-4.6" />
    </svg>
    <span>Search Manga, Manhwa, Manhua…</span>
  </button>
</div>

{#if loadStatus === 'loading'}
  <div class="head glass" aria-busy="true">
    <SkeletonBox width="150px" height="225px" radius="16px" />
    <div class="grow"><SkeletonBox width="70%" height="28px" /><SkeletonBox width="45%" height="16px" /><SkeletonBox width="55%" height="40px" radius="999px" /></div>
  </div>
  <SkeletonChapterList count={6} />
{:else if loadStatus === 'missing' || !item}
  <div class="empty-msg glass">
    <p><b>Comic not found</b></p>
    <p class="sub">This title may be unavailable offline or no longer listed.</p>
    <div class="acts center-acts">
      <a class="btn btn-primary" href="{base}/app/comics">Browse Comics</a>
      <button type="button" class="btn btn-ghost" onclick={() => (searchOpen = true)}>Search Comics</button>
    </div>
  </div>
{:else}
  <section class="head glass" style:--c={item.dominantColor} style:--a={item.accentColor}>
    {#if item.backdrop && !coverFailed}
      <div class="bg" style:background-image="url({img(item.backdrop)})" aria-hidden="true"></div>
    {/if}
    {#if item.poster && !coverFailed}
      <img
        class="cover"
        src={img(item.poster)}
        alt="{item.title} cover"
        width="150"
        height="225"
        referrerpolicy="no-referrer"
        onerror={() => (coverFailed = true)}
      />
    {:else}
      <div class="cover fallback" aria-hidden="true">
        <span>{COMIC_KIND_LABEL[kind]}</span>
      </div>
    {/if}
    <div class="grow">
      <div class="badges">
        <span class="kind-pill">{COMIC_KIND_LABEL[kind]}</span>
        {#if item.rating > 0}<span class="meta-pill">★ {item.rating.toFixed(1)}</span>{/if}
        {#if item.year > 0}<span class="meta-pill">{item.year}</span>{/if}
        {#if item.maturity && item.maturity !== 'NR'}<span class="meta-pill">{item.maturity}</span>{/if}
      </div>
      <h1>{item.title}</h1>
      {#if item.artist}
        <p class="meta">By {item.artist}</p>
      {/if}
      {#if item.genres.length}
        <p class="meta">{item.genres.join(' · ')}</p>
      {/if}
      <p class="meta mode-note">{MODE_LABEL[readMode]} · {chapters.length} {chapters.length === 1 ? 'chapter' : 'chapters'}</p>

      <div class="acts">
        {#if chapters.length}
          <a class="btn btn-primary" href={readHref(lastChapter?.id ?? chapters[0].id)}>
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H11v17H6.5A2.5 2.5 0 0 0 4 22zM20 5.5A2.5 2.5 0 0 0 17.5 3H13v17h4.5a2.5 2.5 0 0 1 2.5 2z" />
            </svg>
            {lastChapter ? `Continue · Ch. ${lastChapter.n} p.${(last?.page ?? 0) + 1}` : 'Start reading'}
          </a>
        {/if}
        <button type="button" class="btn btn-ghost" aria-pressed={bookmarked} onclick={() => item && myList.toggle(item)}>
          <svg width="17" height="17" viewBox="0 0 24 24" fill={bookmarked ? 'currentColor' : 'none'} stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M6 4h12a1 1 0 0 1 1 1v16l-7-4-7 4V5a1 1 0 0 1 1-1z" />
          </svg>
          {bookmarked ? 'Bookmarked' : 'Bookmark'}
        </button>
      </div>
    </div>
  </section>

  <div class="stats">
    <div class="stat glass">
      <b>{item.rating > 0 ? `★ ${item.rating.toFixed(1)}` : '—'}</b>
      <small>Rating</small>
    </div>
    <div class="stat glass">
      <b>{chapters.length}</b>
      <small>Chapters</small>
    </div>
    <div class="stat glass">
      <b>{defaultMode(item) === 'vertical' ? 'Webtoon' : 'Paged RTL'}</b>
      <small>Format</small>
    </div>
  </div>

  {#if item.synopsis}
    <div class="syn-wrap">
      <p class="syn" class:clamped={!expandedSynopsis && item.synopsis.length > 220}>{item.synopsis}</p>
      {#if item.synopsis.length > 220}
        <button type="button" class="more-btn" onclick={() => (expandedSynopsis = !expandedSynopsis)}>
          {expandedSynopsis ? 'Show less' : 'Show more'}
        </button>
      {/if}
    </div>
  {/if}

  <section class="ch-section" aria-labelledby="ch-head">
    <div class="ch-toolbar">
      <div class="ch-title-row">
        <div class="sec-head">
          <span class="accent-bar" aria-hidden="true"></span>
          <h2 id="ch-head">{visibleChapters.length === chapters.length ? `${chapters.length} Chapters` : `${visibleChapters.length} of ${chapters.length} Chapters`}</h2>
        </div>
        {#if chapters.length > 1}
          <div class="ch-pills">
            <button
              type="button"
              class="chip sm"
              aria-pressed={unreadOnly}
              onclick={() => (unreadOnly = !unreadOnly)}
            >
              Unread
            </button>
            <button
              type="button"
              class="chip sm"
              onclick={() => (sortOrder = sortOrder === 'asc' ? 'desc' : 'asc')}
              aria-label="Sort chapters {sortOrder === 'asc' ? 'newest first' : 'oldest first'}"
            >
              {sortOrder === 'desc' ? 'Newest ↓' : 'Oldest ↑'}
            </button>
          </div>
        {/if}
      </div>

      {#if chapters.length > 4}
        <div class="ch-filter glass">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true">
            <circle cx="10.5" cy="10.5" r="6.5" />
            <path d="m20 20-4.6-4.6" />
          </svg>
          <input
            type="search"
            bind:value={chapterQuery}
            maxlength="40"
            placeholder="Filter by chapter number or title…"
            aria-label="Filter chapters"
          />
          {#if chapterQuery}
            <button type="button" class="clear-btn" onclick={() => (chapterQuery = '')} aria-label="Clear filter">×</button>
          {/if}
        </div>
      {/if}
    </div>

    {#if !chaptersReady}
      <SkeletonChapterList count={Math.min(6, Math.max(3, chapters.length || 4))} />
    {:else if chapters.length === 0}
      <p class="empty-ch glass">No readable English chapters are available for this title. It may be officially licensed and published elsewhere.</p>
    {:else if visibleChapters.length === 0}
      <div class="empty-ch glass">
        <p>No chapters match your filter.</p>
        <button type="button" class="btn btn-ghost sm-btn" onclick={() => { chapterQuery = ''; unreadOnly = false; }}>Reset filters</button>
      </div>
    {:else}
      <ol class="chapters">
        {#each visibleChapters as ch (ch.id)}
          {@const f = progress.fraction(item.id, ch.id)}
          {@const isLast = last?.chapterId === ch.id}
          <li>
            <a href={readHref(ch.id)} class:done={f >= 1} class:current={isLast}>
              <span class="n">{ch.n}</span>
              <span class="t">
                <b>{ch.title}</b>
                <small>
                  {(ch.pageCount ?? ch.pages.length) > 0 ? `${ch.pageCount ?? ch.pages.length} pages` : 'Tap to load pages'}
                  {f > 0 ? ` · ${f >= 1 ? 'Read' : `${Math.round(f * 100)}% read`}` : ''}
                </small>
              </span>
              {#if isLast}<span class="cur">Last read</span>{/if}
              {#if f > 0}<span class="bar" aria-hidden="true"><i style:width="{Math.round(f * 100)}%"></i></span>{/if}
            </a>
          </li>
        {/each}
      </ol>
    {/if}
  </section>

  {#if showBackTop}
    <button
      type="button"
      class="back-top glass"
      onclick={() => window.scrollTo({ top: 0, behavior: 'smooth' })}
      aria-label="Back to top"
    >
      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <path d="m18 15-6-6-6 6" />
      </svg>
    </button>
  {/if}
{/if}

<Sheet bind:open={searchOpen} title="Search Comics" wide>
  <div class="live-search">
    <div class="ch-filter glass">
      <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true">
        <circle cx="10.5" cy="10.5" r="6.5" />
        <path d="m20 20-4.6-4.6" />
      </svg>
      <input
        type="search"
        bind:value={searchQuery}
        placeholder="Title (e.g. Solo Leveling, Berserk, One Piece)…"
        aria-label="Search comics"
      />
      {#if searchQuery}
        <button type="button" class="clear-btn" onclick={() => (searchQuery = '')} aria-label="Clear search">×</button>
      {/if}
    </div>

    <div class="cat-chips" role="group" aria-label="Comic origin">
      <button type="button" class="chip sm" aria-pressed={searchCat === 'ALL'} onclick={() => (searchCat = 'ALL')}>All</button>
      {#each COMIC_CATS as cat (cat.id)}
        <button type="button" class="chip sm" aria-pressed={searchCat === cat.id} onclick={() => (searchCat = cat.id)}>{cat.title}</button>
      {/each}
    </div>

    {#if !searchQuery.trim()}
      <p class="search-hint">Type a Manga, Manhwa, or Manhua title to search live.</p>
    {:else if searchBusy && !searchHits.length}
      <div class="search-grid" aria-busy="true">
        {#each Array(6) as _, i (i)}<SkeletonBox aspect="2 / 3" radius="var(--radius-card)" />{/each}
      </div>
    {:else if !searchHits.length}
      <p class="search-hint">No comics found for “{searchQuery.trim()}”.</p>
    {:else}
      <div class="search-grid" onclickcapture={() => (searchOpen = false)} role="presentation">
        {#each searchHits as hit (hit.id)}
          <Poster item={hit} />
        {/each}
      </div>
    {/if}
  </div>
</Sheet>

<style>
  .topbar {
    display: flex;
    align-items: center;
    gap: 10px;
    margin: 0 16px 12px;
  }
  .back-pill {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    min-height: 40px;
    padding: 0 14px 0 10px;
    border-radius: 999px;
    text-decoration: none;
    font-size: 0.88rem;
    font-weight: 700;
  }
  .search-pill {
    flex: 1;
    min-width: 0;
    display: flex;
    align-items: center;
    gap: 8px;
    min-height: 40px;
    padding: 0 14px;
    border: 0;
    border-radius: 999px;
    color: var(--text-secondary);
    font-size: 0.85rem;
    text-align: left;
    cursor: pointer;
  }
  .search-pill span {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .empty-msg {
    max-width: 460px;
    margin: 36px auto;
    padding: 28px 20px;
    border-radius: 24px;
    text-align: center;
  }
  .empty-msg p {
    margin: 0;
  }
  .empty-msg .sub {
    margin-top: 6px;
    color: var(--text-secondary);
    font-size: 0.88rem;
  }
  .center-acts {
    justify-content: center;
    margin-top: 14px;
  }
  .head {
    position: relative;
    display: flex;
    gap: 20px;
    align-items: flex-end;
    margin: 0 16px;
    padding: 22px;
    border-radius: 26px;
    overflow: hidden;
    isolation: isolate;
  }
  @media (max-width: 560px) {
    .head {
      flex-direction: column;
      align-items: center;
      text-align: center;
    }
    .badges,
    .acts {
      justify-content: center;
    }
  }
  .grow {
    flex: 1;
    min-width: 0;
    display: grid;
    gap: 6px;
  }
  .bg {
    position: absolute;
    inset: 0;
    z-index: -1;
    background-size: cover;
    background-position: center;
    filter: blur(22px) saturate(135%);
    transform: scale(1.12);
    opacity: 0.3;
    mask-image: linear-gradient(180deg, #000, transparent 92%);
  }
  .head::after {
    content: '';
    position: absolute;
    inset: 0;
    z-index: -1;
    background: linear-gradient(180deg, color-mix(in srgb, var(--c, #121216) 48%, transparent), transparent);
  }
  .cover {
    width: clamp(118px, 28vw, 154px);
    height: auto;
    aspect-ratio: 2 / 3;
    border-radius: 16px;
    object-fit: cover;
    flex: none;
    box-shadow:
      0 20px 40px -16px rgb(0 0 0 / 0.8),
      inset 0 0 0 1px var(--glass-stroke);
  }
  .cover.fallback {
    display: grid;
    place-items: center;
    background: linear-gradient(145deg, color-mix(in srgb, var(--accent) 34%, var(--surface)), var(--surface));
    color: var(--text-secondary);
    font-weight: 800;
    font-size: 0.85rem;
    letter-spacing: 0.08em;
    text-transform: uppercase;
  }
  .badges {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 6px;
  }
  .kind-pill {
    padding: 3px 10px;
    border-radius: 999px;
    font-size: 0.7rem;
    font-weight: 800;
    letter-spacing: 0.07em;
    text-transform: uppercase;
    color: var(--on-accent);
    background: linear-gradient(135deg, var(--accent), var(--accent-end));
  }
  .meta-pill {
    padding: 3px 9px;
    border-radius: 999px;
    font-size: 0.72rem;
    font-weight: 700;
    color: var(--text-primary);
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
  }
  h1 {
    margin: 2px 0 0;
    font-size: clamp(1.35rem, 4.4vw, 2.1rem);
    font-weight: 820;
    letter-spacing: -0.02em;
    line-height: 1.15;
  }
  .meta {
    margin: 0;
    color: var(--text-secondary);
    font-size: 0.85rem;
  }
  .mode-note {
    font-weight: 600;
  }
  .acts {
    display: flex;
    gap: 10px;
    flex-wrap: wrap;
    margin-top: 8px;
  }
  .stats {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 10px;
    margin: 12px 16px 0;
  }
  .stat {
    display: grid;
    justify-items: center;
    gap: 2px;
    padding: 10px 8px;
    border-radius: 16px;
    text-align: center;
  }
  .stat b {
    font-size: 0.96rem;
    font-weight: 780;
    font-variant-numeric: tabular-nums;
  }
  .stat small {
    color: var(--text-secondary);
    font-size: 0.74rem;
  }
  .syn-wrap {
    margin: 14px 20px 0;
  }
  .syn {
    margin: 0;
    max-width: 72ch;
    color: var(--text-secondary);
    font-size: 0.9rem;
    line-height: 1.55;
    white-space: pre-line;
  }
  .syn.clamped {
    display: -webkit-box;
    -webkit-line-clamp: 3;
    line-clamp: 3;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }
  .more-btn {
    margin-top: 4px;
    padding: 4px 0;
    border: 0;
    background: none;
    color: var(--accent);
    font-size: 0.84rem;
    font-weight: 700;
    cursor: pointer;
  }
  .ch-section {
    margin-top: 18px;
  }
  .ch-toolbar {
    display: grid;
    gap: 10px;
    margin: 0 16px 12px;
  }
  .ch-title-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    flex-wrap: wrap;
  }
  .sec-head {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .accent-bar {
    width: 3px;
    height: 16px;
    border-radius: 99px;
    background: var(--accent);
  }
  .sec-head h2 {
    margin: 0;
    font-size: 1.05rem;
    font-weight: 780;
  }
  .ch-pills {
    display: flex;
    gap: 8px;
  }
  .chip.sm {
    min-height: 32px;
    padding: 0 13px;
    font-size: 0.8rem;
  }
  .ch-filter {
    display: flex;
    align-items: center;
    gap: 8px;
    height: 42px;
    padding: 0 12px;
    border-radius: 14px;
    color: var(--text-secondary);
  }
  .ch-filter input {
    flex: 1;
    min-width: 0;
    height: 100%;
    border: 0;
    background: none;
    color: var(--text-primary);
    font: inherit;
    font-size: 0.9rem;
    outline: none;
  }
  .clear-btn {
    width: 28px;
    height: 28px;
    border: 0;
    border-radius: 50%;
    background: none;
    color: var(--text-secondary);
    font-size: 1.2rem;
    cursor: pointer;
  }
  .empty-ch {
    margin: 0 16px;
    padding: 22px 18px;
    border-radius: 18px;
    color: var(--text-secondary);
    font-size: 0.9rem;
    text-align: center;
  }
  .sm-btn {
    margin-top: 10px;
    min-height: 36px;
    padding: 0 16px;
    font-size: 0.84rem;
  }
  .chapters {
    list-style: none;
    margin: 0;
    padding: 0 16px;
    display: grid;
    gap: 8px;
  }
  .chapters a {
    position: relative;
    display: flex;
    align-items: center;
    gap: 14px;
    padding: 12px 14px;
    border-radius: 16px;
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
    text-decoration: none;
    overflow: hidden;
    transition: transform 0.14s ease, background-color 0.14s ease;
  }
  .chapters a:hover,
  .chapters a:focus-visible {
    transform: translateY(-1px);
    background: color-mix(in srgb, var(--accent) 8%, var(--glass-fill));
  }
  .chapters a.current {
    box-shadow: inset 0 0 0 1.5px color-mix(in srgb, var(--accent) 65%, var(--glass-stroke));
  }
  .chapters a.done {
    opacity: 0.68;
  }
  .n {
    min-width: 36px;
    height: 36px;
    padding: 0 6px;
    display: grid;
    place-items: center;
    border-radius: 11px;
    background: color-mix(in srgb, var(--accent) 18%, transparent);
    font-weight: 800;
    font-size: 0.86rem;
    font-variant-numeric: tabular-nums;
  }
  .t {
    flex: 1;
    min-width: 0;
    display: grid;
    gap: 2px;
  }
  .t b {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .t small {
    color: var(--text-secondary);
    font-size: 0.78rem;
  }
  .bar {
    position: absolute;
    left: 0;
    right: 0;
    bottom: 0;
    height: 3px;
    background: var(--divider);
  }
  .bar i {
    display: block;
    height: 100%;
    background: var(--accent);
  }
  .cur {
    flex: none;
    padding: 2px 8px;
    border-radius: 999px;
    background: color-mix(in srgb, var(--accent) 18%, transparent);
    font-size: 0.7rem;
    font-weight: 750;
    color: var(--accent);
  }
  .back-top {
    position: fixed;
    right: 20px;
    bottom: calc(var(--nav-h, 64px) + 24px + var(--safe-b, 0px));
    z-index: 20;
    width: 44px;
    height: 44px;
    display: grid;
    place-items: center;
    border: 0;
    border-radius: 50%;
    color: var(--text-primary);
    cursor: pointer;
  }
  .live-search {
    display: grid;
    gap: 12px;
  }
  .cat-chips {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }
  .search-hint {
    margin: 8px 0;
    color: var(--text-secondary);
    font-size: 0.86rem;
    text-align: center;
  }
  .search-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(104px, 1fr));
    gap: 12px 10px;
    max-height: 56dvh;
    overflow-y: auto;
    padding: 2px;
  }
  .search-grid :global(.card) {
    width: auto;
  }
</style>
