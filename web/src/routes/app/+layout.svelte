<script lang="ts">
  import { onMount } from 'svelte';
  import { page } from '$app/state';
  import { onNavigate } from '$app/navigation';
  import Nav from '$lib/components/Nav.svelte';
  import SpacesSheet from '$lib/components/SpacesSheet.svelte';
  import { spaceFromSlug, isVideoSpace } from '$lib/data/types';
  import { ISOLATED_SPACE_ENTRIES, VIDEO_TABS, isolatedTabs } from '$lib/nav';
  import { bindTintToDocument } from '$lib/design/tint.svelte';
  import { perf } from '$lib/perf/tier.svelte';

  let { children } = $props();
  let spacesOpen = $state(false);

  const space = $derived(spaceFromSlug(page.params.space ?? '') ?? 'HOME');
  const isolated = $derived(!isVideoSpace(space));
  const tabs = $derived(isolated ? isolatedTabs(space) : VIDEO_TABS);
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
    requestAnimationFrame(() => window.__cinetorReady?.());
    return unbind;
  });
</script>

<div class="ambient" aria-hidden="true"></div>
<a class="skip" href="#main">Skip to content</a>

<Nav {tabs} {spaceLabel} onSpaces={() => (spacesOpen = true)} />
<SpacesSheet bind:open={spacesOpen} />

<main id="main" class="app" tabindex="-1">
  {@render children()}
</main>

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
