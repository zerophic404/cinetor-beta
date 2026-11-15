<script lang="ts">
  import { page } from '$app/state';
  import { repo, img } from '$lib/data/repo';
  import type { Item } from '$lib/data/types';
  import { SkeletonChapterList, SkeletonBox } from '$lib/design/skeleton';
  import { myList } from '$lib/data/library.svelte';
  import { music } from '$lib/music/player.svelte';
  import { trackOf, type Track } from '$lib/music/core';
  import { fmtDur } from '$lib/music/queue';
  import TrackList from '$lib/music/TrackList.svelte';

  const id = $derived(page.url.searchParams.get('id') ?? '');
  const trackId = $derived(page.url.searchParams.get('track'));

  let album = $state.raw<Item | null>(null);
  let tracks = $state.raw<Item[]>([]);
  let status = $state<'loading' | 'ready' | 'missing'>('loading');
  let autoPlayed = '';

  $effect(() => {
    const want = id;
    const tr = trackId;
    let alive = true;
    status = 'loading';
    repo.file('MUSIC').then(
      (f) => {
        if (!alive) return;
        const byId = new Map(f.items.map((i) => [i.id, i] as const));
        const al = byId.get(want);
        if (!al || al.type !== 'ALBUM') {
          status = 'missing';
          return;
        }
        album = al;
        tracks = (al.trackIds ?? []).map((t) => byId.get(t)).filter((t): t is Item => !!t);
        status = 'ready';
        // Deep link from a track card → play it (the click that navigated here is the user gesture).
        if (tr && autoPlayed !== tr) {
          autoPlayed = tr;
          const list = tracks.map((t) => trackOf(t, img)).filter((t): t is Track => !!t);
          const i = list.findIndex((t) => t.id === tr);
          if (i >= 0 && music.current?.id !== tr) music.playQueue(list, i);
        }
      },
      () => alive && (status = 'missing')
    );
    return () => {
      alive = false;
    };
  });

  const total = $derived(tracks.reduce((n, t) => n + (t.durationSec ?? 0), 0));
  function playAll(shuffle: boolean) {
    const list = tracks.map((t) => trackOf(t, img)).filter((t): t is Track => !!t);
    if (!list.length) return;
    music.setShuffle(shuffle);
    music.playQueue(list, shuffle ? Math.floor(Math.random() * list.length) : 0);
  }
</script>

<svelte:head><title>{album?.title ?? 'Album'} · Music · Cinetor</title></svelte:head>

{#if status === 'loading'}
  <div class="head sk" aria-busy="true">
    <SkeletonBox width="200px" height="200px" radius="18px" />
    <div class="sk-txt"><SkeletonBox width="60%" height="28px" /><SkeletonBox width="40%" height="16px" /></div>
  </div>
  <SkeletonChapterList count={6} />
{:else if status === 'missing' || !album}
  <p class="state">Album not found.</p>
{:else}
  <section class="head" style:--c={album.dominantColor} style:--a={album.accentColor}>
    <div class="bg" style:background-image="url({img(album.poster)})" aria-hidden="true"></div>
    <img class="art" src={img(album.poster)} alt="{album.title} artwork" width="220" height="220" />
    <div class="txt">
      <span class="kicker">Album</span>
      <h1>{album.title}</h1>
      <p>{album.artist} · {album.year} · {tracks.length} tracks · {fmtDur(total)}</p>
      <div class="genres">{album.genres.join(' · ')}</div>
      <div class="acts">
        <button class="btn btn-primary" onclick={() => playAll(false)}>▶ Play</button>
        <button class="btn btn-ghost" onclick={() => playAll(true)}>⤮ Shuffle</button>
        <button class="btn btn-ghost" aria-pressed={myList.has(album)} onclick={() => album && myList.toggle(album)}>{myList.has(album) ? '✓ Saved' : '+ Save'}</button>
      </div>
    </div>
  </section>
  <p class="syn">{album.synopsis}</p>
  <TrackList items={tracks} art={false} />
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
    flex-wrap: wrap;
    gap: 22px;
    align-items: flex-end;
    margin: 0 16px;
    padding: 24px;
    border-radius: 28px;
    overflow: hidden;
    isolation: isolate;
    background: linear-gradient(180deg, color-mix(in srgb, var(--c, #222) 90%, #000), #000);
  }
  .head.sk {
    background: none;
  }
  .sk-txt {
    flex: 1;
    display: grid;
    gap: 10px;
  }
  .bg {
    position: absolute;
    inset: -50px;
    z-index: -1;
    background-size: cover;
    background-position: center;
    filter: blur(50px) saturate(1.3);
    opacity: 0.5;
  }
  :global([data-tier='low']) .bg {
    display: none;
  }
  .art {
    width: clamp(140px, 40vw, 220px);
    height: auto;
    aspect-ratio: 1;
    object-fit: cover;
    border-radius: 18px;
    box-shadow: 0 24px 50px -18px rgb(0 0 0 / 0.85);
  }
  .txt {
    flex: 1;
    min-width: 220px;
  }
  .kicker {
    font-size: 0.72rem;
    font-weight: 800;
    letter-spacing: 0.1em;
    text-transform: uppercase;
    color: var(--a);
  }
  h1 {
    margin: 4px 0;
    font-size: clamp(1.5rem, 5vw, 2.6rem);
  }
  .txt p {
    margin: 0;
    color: var(--text-secondary);
  }
  .genres {
    margin-top: 4px;
    font-size: 0.8rem;
    color: var(--text-tertiary);
  }
  .acts {
    display: flex;
    gap: 10px;
    flex-wrap: wrap;
    margin-top: 14px;
  }
  .syn {
    margin: 16px 24px 8px;
    color: var(--text-secondary);
    font-size: 0.9rem;
  }
</style>
