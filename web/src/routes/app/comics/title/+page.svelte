<script lang="ts">
  import { page } from '$app/state';
  import { base } from '$app/paths';
  import { repo, img } from '$lib/data/repo';
  import type { Item } from '$lib/data/types';
  import { SkeletonBox, SkeletonChapterList } from '$lib/design/skeleton';
  import { myList, activeProfileId } from '$lib/data/library.svelte';
  import { localKV } from '$lib/data/kv';
  import { ReadProgress, comicKind, defaultMode, MODE_LABEL } from '$lib/comics/reader';

  const id = $derived(page.url.searchParams.get('id') ?? '');
  let item = $state.raw<Item | null>(null);
  let status = $state<'loading' | 'ready' | 'missing'>('loading');
  let chaptersReady = $state(false);
  const progress = new ReadProgress(localKV(), activeProfileId());

  $effect(() => {
    const want = id;
    let alive = true;
    status = 'loading';
    chaptersReady = false;
    repo.item(want, 'COMICS').then(
      (it) => {
        if (!alive) return;
        if (!it || it.space !== 'COMICS') {
          status = 'missing';
          return;
        }
        item = it;
        status = 'ready';
        // Chapter list paints after the header (skeleton meanwhile) — mirrors the Android lazy list.
        setTimeout(() => alive && (chaptersReady = true), 250);
      },
      () => alive && (status = 'missing')
    );
    return () => {
      alive = false;
    };
  });

  const chapters = $derived(item?.pages ?? []);
  const last = $derived(item ? progress.last(item.id) : undefined);
  const kind = $derived(item ? comicKind(item) : 'comic');
  const readHref = (chId: string) => `${base}/app/comics/read?id=${encodeURIComponent(id)}&ch=${encodeURIComponent(chId)}`;
  const lastChapter = $derived(last ? chapters.find((c) => c.id === last.chapterId) : undefined);
</script>

<svelte:head><title>{item?.title ?? 'Comic'} · Comics · Cinetor</title></svelte:head>

{#if status === 'loading'}
  <div class="head" aria-busy="true">
    <SkeletonBox width="150px" height="225px" radius="16px" />
    <div class="grow"><SkeletonBox width="70%" height="28px" /><SkeletonBox width="40%" height="16px" /></div>
  </div>
  <SkeletonChapterList count={6} />
{:else if status === 'missing' || !item}
  <p class="state">Comic not found.</p>
{:else}
  <section class="head" style:--c={item.dominantColor} style:--a={item.accentColor}>
    <div class="bg" style:background-image="url({img(item.backdrop)})" aria-hidden="true"></div>
    <img class="cover" src={img(item.poster)} alt="{item.title} cover" width="150" height="225" />
    <div class="grow">
      <span class="kind">{kind}</span>
      <h1>{item.title}</h1>
      <p class="meta">{item.artist ?? ''} · {item.year} · ★ {item.rating.toFixed(1)} · {chapters.length} chapters</p>
      <p class="meta">{item.genres.join(' · ')} · reads as {MODE_LABEL[defaultMode(item)]}</p>
      <div class="acts">
        {#if chapters.length}
          <a class="btn btn-primary" href={readHref(lastChapter?.id ?? chapters[0].id)}>
            {lastChapter ? `Continue · Ch. ${lastChapter.n} p.${(last?.page ?? 0) + 1}` : 'Start reading'}
          </a>
        {/if}
        <button class="btn btn-ghost" aria-pressed={myList.has(item)} onclick={() => item && myList.toggle(item)}>
          {myList.has(item) ? '🔖 Bookmarked' : '🔖 Bookmark'}
        </button>
      </div>
    </div>
  </section>
  <p class="syn">{item.synopsis}</p>

  <h2 class="section-title">Chapters</h2>
  {#if !chaptersReady}
    <SkeletonChapterList count={Math.min(6, Math.max(3, chapters.length))} />
  {:else}
    <ol class="chapters">
      {#each chapters as ch (ch.id)}
        {@const f = progress.fraction(item.id, ch.id)}
        <li>
          <a href={readHref(ch.id)} class:done={f >= 1}>
            <span class="n">{ch.n}</span>
            <span class="t"><b>{ch.title}</b><small>{ch.pages.length} pages{f > 0 ? ` · ${f >= 1 ? 'Read' : `${Math.round(f * 100)}%`}` : ''}</small></span>
            {#if f > 0}<span class="bar" aria-hidden="true"><i style:width="{f * 100}%"></i></span>{/if}
            {#if last?.chapterId === ch.id}<span class="cur">Last read</span>{/if}
          </a>
        </li>
      {/each}
    </ol>
  {/if}
{/if}

<style>
  .state {
    padding: 48px 16px;
    text-align: center;
    color: var(--text-secondary);
  }
  .head {
    position: relative;
    display: flex;
    gap: 20px;
    align-items: flex-end;
    margin: 0 16px;
    padding: 22px;
    border-radius: 28px;
    overflow: hidden;
    isolation: isolate;
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
    opacity: 0.35;
    mask-image: linear-gradient(180deg, #000, transparent);
  }
  .head::after {
    content: '';
    position: absolute;
    inset: 0;
    z-index: -1;
    background: linear-gradient(180deg, color-mix(in srgb, var(--c, #111) 50%, transparent), transparent);
  }
  .cover {
    width: clamp(110px, 28vw, 150px);
    height: auto;
    aspect-ratio: 2 / 3;
    border-radius: 16px;
    object-fit: cover;
    box-shadow: 0 20px 40px -16px rgb(0 0 0 / 0.85);
  }
  .kind {
    font-size: 0.72rem;
    font-weight: 800;
    letter-spacing: 0.1em;
    text-transform: uppercase;
    color: var(--a);
  }
  h1 {
    margin: 0;
    font-size: clamp(1.3rem, 4.5vw, 2.2rem);
  }
  .meta {
    margin: 0;
    color: var(--text-secondary);
    font-size: 0.85rem;
  }
  .acts {
    display: flex;
    gap: 10px;
    flex-wrap: wrap;
    margin-top: 6px;
  }
  .syn {
    margin: 14px 24px 0;
    color: var(--text-secondary);
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
  }
  .chapters a.done {
    opacity: 0.65;
  }
  .n {
    width: 34px;
    height: 34px;
    display: grid;
    place-items: center;
    border-radius: 10px;
    background: color-mix(in srgb, var(--accent) 20%, transparent);
    font-weight: 800;
  }
  .t {
    flex: 1;
    display: grid;
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
    background: rgb(255 255 255 / 0.08);
  }
  .bar i {
    display: block;
    height: 100%;
    background: var(--accent);
  }
  .cur {
    font-size: 0.7rem;
    font-weight: 700;
    color: var(--accent);
  }
</style>
