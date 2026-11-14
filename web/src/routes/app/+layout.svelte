<script lang="ts">
  import { onMount } from 'svelte';
  import { page } from '$app/state';
  import { afterNavigate, goto, onNavigate } from '$app/navigation';
  import { base } from '$app/paths';
  import { installSpatialNav, restoreFocus } from '$lib/tv/spatial';
  import Nav from '$lib/components/Nav.svelte';
  import SpacesSheet from '$lib/components/SpacesSheet.svelte';
  import { spaceFromSlug, isVideoSpace } from '$lib/data/types';
  import { ISOLATED_SPACE_ENTRIES, VIDEO_TABS, isolatedTabs, spaceOfPath } from '$lib/nav';
  import { bindTintToDocument } from '$lib/design/tint.svelte';
  import { perf } from '$lib/perf/tier.svelte';
  import MiniPlayer from '$lib/player/MiniPlayer.svelte';
  import { engine } from '$lib/player/engine';
  import { wirePlayer } from '$lib/player/controller';
  import { downloads } from '$lib/download/manager.svelte';

  let { children } = $props();
  let spacesOpen = $state(false);

  const space = $derived(spaceFromSlug(page.params.space ?? '') ?? spaceOfPath(page.url.pathname));
  const isolated = $derived(!isVideoSpace(space));
  const tabs = $derived(isolated ? isolatedTabs(space) : VIDEO_TABS);
  const watching = $derived(page.url.pathname.replace(/\/$/, '').endsWith('/app/watch'));

  /** Park slot of the ONE persistent <video> (preloads buffer here, hidden). */
  function park(node: HTMLElement) {
    wirePlayer();
    engine.setPark(node);
    return { destroy: () => engine.setPark(null) };
  }
  const spaceLabel = $derived(isolated ? (ISOLATED_SPACE_ENTRIES.find((e) => e.space === space)?.label ?? null) : null);

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
    const unbind = bindTintToDocument();
    // TV / remote mode: spatial navigation (auto on TV UAs, ?tv=1, or the first arrow key).
    const offTv = installSpatialNav({
      onBack: () => {
        const p = location.pathname.replace(/\/$/, '');
        if (p === `${base}/app`) return;
        if (history.length > 1) history.back();
        else goto(`${base}/app`);
      }
    });
    requestAnimationFrame(() => window.__cinetorReady?.());
    // Resume downloads interrupted by a reload (after first paint).
    setTimeout(() => void downloads.init(), 1500);
    return () => {
      unbind();
      offTv();
    };
  });

  // Focus memory per route: restore the last focused tile when coming back (TV mode only).
  afterNavigate(() => {
    setTimeout(() => restoreFocus(), 60);
  });
</script>

<div class="ambient" aria-hidden="true"></div>
<a class="skip" href="#main">Skip to content</a>

{#if !watching}<Nav {tabs} {spaceLabel} onSpaces={() => (spacesOpen = true)} />{/if}
<SpacesSheet bind:open={spacesOpen} />

<main id="main" class="app" class:watching tabindex="-1">
  {@render children()}
</main>

<div class="video-park" aria-hidden="true" use:park></div>
<MiniPlayer hidden={watching} />

<style>
  .app {
    min-height: 100dvh;
    padding: calc(12px + var(--safe-t)) var(--safe-r) calc(var(--nav-h) + 28px + var(--safe-b)) var(--safe-l);
    outline: none;
  }
  @media (min-width: 900px) {
    .app {
      padding: calc(16px + var(--safe-t)) calc(8px + var(--safe-r)) calc(24px + var(--safe-b)) calc(var(--rail-w) + 24px + var(--safe-l));
    }
  }
  .app.watching {
    padding: 0 0 calc(24px + var(--safe-b));
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
    top: 8px;
    z-index: 100;
    padding: 8px 14px;
    background: var(--accent);
    border-radius: 99px;
  }
  .skip:focus {
    left: 12px;
  }
</style>
