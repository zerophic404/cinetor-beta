<script lang="ts">
  // Own cue rendering (side-loaded VTT/SRT) → sync offset works both directions, style per profile.
  import { activeCues, cueHtml } from './subs';
  import type { PlayerUI } from './ui.svelte';
  import { engine } from './engine';

  let { ui, raised = false }: { ui: PlayerUI; raised?: boolean } = $props();
  let html = $state('');

  $effect(() => {
    const cues = ui.cues;
    const offset = ui.subOffset;
    if (!cues.length) {
      html = '';
      return;
    }
    let raf = 0;
    let last = '';
    const loop = () => {
      const v = engine.video;
      const out = v ? activeCues(cues, v.currentTime, offset).map((c) => cueHtml(c.text)).join('<br>') : '';
      if (out !== last) html = last = out;
      raf = requestAnimationFrame(loop);
    };
    loop();
    return () => cancelAnimationFrame(raf);
  });
</script>

{#if html}
  <div class="subs" class:raised aria-live="off" style:--sub-size="{ui.subStyle.size / 100}" style:--sub-color={ui.subStyle.color} style:--sub-bg="rgb(0 0 0 / {ui.subStyle.bg})">
    <!-- eslint-disable-next-line svelte/no-at-html-tags -- cueHtml escapes everything except <i>/<b>/<u> -->
    <span>{@html html}</span>
  </div>
{/if}

<style>
  .subs {
    position: absolute;
    left: 6%;
    right: 6%;
    bottom: 7%;
    z-index: 3;
    text-align: center;
    pointer-events: none;
    transition: bottom 200ms ease;
  }
  .subs.raised {
    bottom: 22%;
  }
  span {
    display: inline;
    padding: 0.1em 0.4em;
    line-height: 1.45;
    font-size: calc(clamp(14px, 3.6cqw, 34px) * var(--sub-size));
    font-weight: 600;
    color: var(--sub-color);
    background: var(--sub-bg);
    border-radius: 4px;
    box-decoration-break: clone;
    -webkit-box-decoration-break: clone;
    text-shadow: 0 1px 3px rgb(0 0 0 / 0.9);
    white-space: pre-line;
  }
</style>
