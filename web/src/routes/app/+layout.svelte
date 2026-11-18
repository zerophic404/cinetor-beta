<script lang="ts">
  import { onMount } from 'svelte';
  import { page } from '$app/state';
  import { afterNavigate, goto, onNavigate } from '$app/navigation';
  import { base } from '$app/paths';
  import { installSpatialNav, restoreFocus } from '$lib/tv/spatial';
  import Nav from '$lib/components/Nav.svelte';
  import { spaceFromSlug, isVideoSpace, type CatalogFile, type Item, type Space } from '$lib/data/types';
  import { ISOLATED_SPACE_ENTRIES, VIDEO_TABS, isolatedTabs, spaceOfPath } from '$lib/nav';
  import { bindTintToDocument } from '$lib/design/tint.svelte';
  import { perf } from '$lib/perf/tier.svelte';
  import MiniPlayer from '$lib/player/MiniPlayer.svelte';
  import Toast from '$lib/components/Toast.svelte';
  import { engine } from '$lib/player/engine';
  import { wirePlayer } from '$lib/player/controller';
  import { downloads } from '$lib/download/manager.svelte';
  import { music } from '$lib/music/player.svelte';
  import MusicBar from '$lib/music/MusicBar.svelte';
  import NowPlaying from '$lib/music/NowPlaying.svelte';
  import { adult } from '$lib/adult/store.svelte';
  import { catalog, setAdultGate } from '$lib/data/app-catalog';
  import { imgAttrs } from '$lib/data/images';
  import { repo } from '$lib/data/repo';
  import { settings } from '$lib/settings/store.svelte';
  import { desktop } from '$lib/desktop/desktop.svelte';
  import DesktopMini from '$lib/desktop/DesktopMini.svelte';

  let { children } = $props();
  // Catalog asks the 18+ gate before any gateway adult request (set before children render).
  setAdultGate(() => adult.enabled && adult.unlocked);

  const space = $derived(spaceFromSlug(page.params.space ?? '') ?? spaceOfPath(page.url.pathname));
  const isolated = $derived(!isVideoSpace(space) && space !== 'TV');
  const tabs = $derived(isolated ? isolatedTabs(space) : VIDEO_TABS);
  const watching = $derived(page.url.pathname.replace(/\/$/, '').endsWith('/app/watch'));
  /** Full-screen routes without the nav (player, comic reader). */
  const immersive = $derived(watching || page.url.pathname.replace(/\/$/, '').endsWith('/app/comics/read'));
  const barShown = $derived(!!music.current && !immersive);

  /** Park slot of the ONE persistent <video> (preloads buffer here, hidden). */
  function park(node: HTMLElement) {
    wirePlayer();
    engine.setPark(node);
    return { destroy: () => engine.setPark(null) };
  }
  const spaceLabel = $derived(isolated ? (ISOLATED_SPACE_ENTRIES.find((e) => e.space === space)?.label ?? null) : null);

  function sizedPosterUrl(raw: string): string {
    return imgAttrs(raw, 'poster', perf.flags.tier, (p) => repo.asset(p)).src;
  }

  function sizedBackdropUrl(raw: string, hero = false): string {
    return imgAttrs(raw, hero ? 'hero' : 'backdrop', perf.flags.tier, (p) => repo.asset(p)).src;
  }

  function extractAboveFoldUrls(file: CatalogFile, maxPerRow: number): string[] {
    const byId = new Map<string, Item>();
    for (const it of file.items) byId.set(it.id, it);
    const cat = file.categories[0];
    const urls: string[] = [];

    const heroList: Item[] = [];
    if (cat?.heroIds?.length) {
      for (const id of cat.heroIds.slice(0, 5)) {
        const it = byId.get(id);
        if (it) heroList.push(it);
      }
    }
    if (!heroList.length) heroList.push(...file.items.slice(0, 4));

    for (const it of heroList) {
      const heroRaw = it.backdrop || it.poster;
      const posterRaw = it.poster || it.backdrop;
      if (heroRaw) urls.push(sizedBackdropUrl(heroRaw, true));
      if (posterRaw) urls.push(sizedPosterUrl(posterRaw));
    }

    for (const row of (cat?.rows ?? []).slice(0, 2)) {
      const landscape = row.style === 'LANDSCAPE';
      for (const id of row.itemIds.slice(0, maxPerRow)) {
        const it = byId.get(id);
        if (!it) continue;
        const raw = landscape ? it.backdrop || it.poster : it.poster || it.backdrop;
        if (raw) urls.push(landscape ? sizedBackdropUrl(raw, false) : sizedPosterUrl(raw));
      }
    }
    return urls;
  }

  function predecodeImage(url: string): Promise<void> {
    if (!url || typeof Image === 'undefined') return Promise.resolve();
    return new Promise<void>((resolve) => {
      const img = new Image();
      img.referrerPolicy = 'no-referrer';
      img.decoding = 'async';
      const done = () => resolve();
      img.onload = () => {
        if (typeof img.decode === 'function') {
          img.decode().then(done, done);
        } else {
          done();
        }
      };
      img.onerror = done;
      img.src = url;
    });
  }

  function warmBootCatalogsAndThumbnails(): Promise<void> {
    const bootSpaces: readonly Space[] = ['HOME', 'ANIME', 'DRAMA'];
    const low = perf.flags.tier === 'LOW';
    const maxPerRow = low ? 4 : 8;
    const seen = new Set<string>();
    const catWithSpace = catalog as typeof catalog & { space?: (s: Space) => Promise<CatalogFile> };
    const fetchSpace = (s: Space): Promise<CatalogFile> =>
      typeof catWithSpace.space === 'function' ? catWithSpace.space(s) : catalog.file(s);

    const jobs = bootSpaces.map(async (s) => {
      try {
        const file = await fetchSpace(s);
        const urls = extractAboveFoldUrls(file, maxPerRow).filter((u) => {
          if (!u || seen.has(u)) return false;
          seen.add(u);
          return seen.size <= (low ? 14 : 42);
        });
        await Promise.all(urls.map(predecodeImage));
      } catch {
        /* offline / cold gateway — never block boot */
      }
    });

    return Promise.allSettled(jobs).then(() => undefined);
  }

  // Card → details morph via the View Transitions API (fallback: plain navigation + CSS fades).
  onNavigate((navigation) => {
    const doc = document as Document & { startViewTransition?: (cb: () => Promise<void>) => unknown };
    if (!doc.startViewTransition || perf.flags.tier === 'LOW') return;
    if (navigation.from?.url.pathname === navigation.to?.url.pathname) return;
    return new Promise<void>((resolve) => {
      doc.startViewTransition!(async () => {
        resolve();
        await navigation.complete;
      });
    });
  });

  onMount(() => {
    perf.init();
    settings.apply();
    const offAdult = adult.watchVisibility();
    const unbind = bindTintToDocument();
    // TV / remote mode: spatial navigation (auto on TV UAs, ?tv=1, or the first arrow key).
    const offTv = installSpatialNav({
      onBack: () => {
        const p = location.pathname.replace(/\/$/, '');
        if (p === `${base}/app`) return;
        if (history.length > 1) history.back();
        else {
          const curSpace = spaceOfPath(location.pathname);
          const isIso = !isVideoSpace(curSpace) && curSpace !== 'TV';
          goto(isIso ? `${base}/app/me` : `${base}/app`);
        }
      }
    });

    // Warm HOME, ANIME, DRAMA catalogs + hero & first 2 rows' thumbnails before dismissing splash (900ms cap).
    const splashCap = new Promise<void>((resolve) => setTimeout(resolve, 900));
    void Promise.race([warmBootCatalogsAndThumbnails(), splashCap]).finally(() => {
      requestAnimationFrame(() => window.__cinetorReady?.());
    });

    // Resume downloads interrupted by a reload (after first paint).
    setTimeout(() => void downloads.init(), 1500);
    return () => {
      unbind();
      offTv();
      offAdult();
    };
  });

  // Focus memory per route: restore the last focused tile when coming back (TV mode only).
  afterNavigate(() => {
    setTimeout(() => restoreFocus(), 60);
  });
</script>

<div class="ambient" aria-hidden="true"></div>
<a class="skip" href="#main">Skip to content</a>

{#if !immersive}<Nav {tabs} {space} {spaceLabel} />{/if}

<main id="main" class="app" class:watching={immersive} class:with-bar={barShown} tabindex="-1">
  {@render children()}
</main>

<div class="video-park" aria-hidden="true" use:park></div>
<MiniPlayer hidden={watching || desktop.mini} />
<Toast />
<DesktopMini />
<!-- The ONE audio element for music: owned here so playback survives navigation. -->
<audio class="music-audio" use:music.attach preload="metadata"></audio>
<MusicBar hidden={immersive} />
<NowPlaying />

<style>
  :global(:root) {
    --header-h: 56px;
  }
  .app {
    min-height: 100dvh;
    padding: calc(var(--header-h, 56px) + 12px + var(--safe-t)) var(--safe-r) calc(var(--nav-h) + 28px + var(--safe-b)) var(--safe-l);
    outline: none;
  }
  /* Keep /app/title's top inline player / backdrop flush below the 56px top header (never hidden behind it). */
  .app:not(.watching) :global(.details) {
    margin-top: -12px;
  }
  @media (min-width: 900px) {
    .app {
      padding: calc(var(--header-h, 56px) + 16px + var(--safe-t)) calc(8px + var(--safe-r)) calc(24px + var(--safe-b)) calc(var(--rail-w) + 24px + var(--safe-l));
    }
    .app:not(.watching) :global(.details) {
      margin-top: -16px;
    }
  }
  .app.with-bar {
    padding-bottom: calc(var(--nav-h) + 100px + var(--safe-b));
  }
  .music-audio {
    display: none;
  }
  .app.watching {
    padding: 0 0 calc(24px + var(--safe-b));
  }
  /* Desktop: keep the player clear of the custom title bar. */
  :global(html.tauri) .app.watching {
    padding-top: var(--titlebar-h, 36px);
  }
  .video-park {
    position: fixed;
    left: 0;
    top: 0;
    width: 2px;
    height: 2px;
    opacity: 0;
    overflow: hidden;
    pointer-events: none;
    z-index: -1;
  }
  .video-park :global(video) {
    width: 2px;
    height: 2px;
  }
  .skip {
    position: absolute;
    left: -9999px;
    top: calc(var(--safe-t) + 8px);
    z-index: 100;
    padding: 8px 14px;
    background: var(--accent);
    color: var(--on-accent);
    border-radius: 99px;
  }
  .skip:focus {
    left: 12px;
  }
</style>
