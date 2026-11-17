<script lang="ts">
  // Glass controls overlay (auto-hide 3 s): top bar, center transport, seek bar, bottom actions.
  import { clock, pipSupported } from './controller';
  import { engine } from './engine';
  import { fmtTime } from './gesture-math';
  import { ASPECT_LABEL, type PlayerUI } from './ui.svelte';
  import SettingsSheet from './SettingsSheet.svelte';
  import type { Sub } from '$lib/data/types';

  let {
    ui,
    title,
    subtitle,
    subs,
    intro,
    next,
    onBack
  }: {
    ui: PlayerUI;
    title: string;
    subtitle?: string;
    subs: Sub[];
    intro?: [number, number] | null;
    next?: { title: string; go: () => void } | null;
    onBack: () => void;
  } = $props();

  let bar: HTMLDivElement | undefined = $state();
  let hoverT = $state<number | null>(null);
  let hoverX = $state(0);
  let scrubT = $state<number | null>(null);

  const ytMode = engine.isYouTube;
  const dur = $derived($clock.dur);
  const shownT = $derived(scrubT ?? ui.drag?.target ?? $clock.t);
  const pct = (t: number) => (dur > 0 ? Math.min(100, Math.max(0, (t / dur) * 100)) : 0);
  const inIntro = $derived(!!intro && $clock.t >= intro[0] && $clock.t < intro[1] - 1);

  function timeAt(clientX: number): number {
    if (!bar || dur <= 0) return 0;
    const r = bar.getBoundingClientRect();
    return Math.min(1, Math.max(0, (clientX - r.left) / r.width)) * dur;
  }
  function barDown(e: PointerEvent) {
    if (!bar) return;
    bar.setPointerCapture(e.pointerId);
    ui.scrubbing = true;
    scrubT = timeAt(e.clientX);
    ui.show();
  }
  function barMove(e: PointerEvent) {
    const t = timeAt(e.clientX);
    if (bar) hoverX = Math.min(100, Math.max(0, ((e.clientX - bar.getBoundingClientRect().left) / bar.getBoundingClientRect().width) * 100));
    hoverT = t;
    if (ui.scrubbing) scrubT = t;
  }
  function barUp() {
    if (scrubT !== null) ui.seekTo(scrubT);
    scrubT = null;
    ui.scrubbing = false;
    ui.armHide();
  }
  function barKey(e: KeyboardEvent) {
    if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
      e.preventDefault();
      e.stopPropagation();
      ui.seek(e.key === 'ArrowLeft' ? -5 : 5);
    }
  }
</script>

{#if ui.locked}
  {#if ui.lockShown}
    <button class="unlock glass" onclick={() => ui.unlock()} aria-label="Unlock controls">
      <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><rect x="5" y="11" width="14" height="9" rx="2" /><path d="M8 11V8a4 4 0 0 1 8 0v3" /></svg>
      Tap to unlock
    </button>
  {/if}
{:else}
  <div class="ctl" class:show={ui.visible || ui.settings}>
    <div class="scrim-top"></div>
    <div class="scrim-bot"></div>

    <header class="top">
      <button class="ib" onclick={onBack} aria-label="Minimize player">
        <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M6 9l6 6 6-6" /></svg>
      </button>
      <div class="ttl">
        <strong>{title}</strong>
        {#if subtitle}<span>{subtitle}</span>{/if}
      </div>
      {#if !$ytMode && pipSupported()}
        <button class="ib" onclick={() => ui.pip()} aria-label="Picture in picture">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><rect x="3" y="5" width="18" height="14" rx="2" /><rect x="12" y="12" width="7" height="5" rx="1" fill="currentColor" /></svg>
        </button>
      {/if}
      <button class="ib" onclick={() => (ui.settings = !ui.settings)} aria-label="Settings" aria-expanded={ui.settings}>
        <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><circle cx="12" cy="12" r="3" /><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z" /></svg>
      </button>
    </header>

    <div class="mid">
      <button class="ib big" onclick={() => ui.seek(-10)} aria-label="Back 10 seconds">
        <svg viewBox="0 0 24 24" width="30" height="30" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M3 12a9 9 0 1 0 3-6.7L3 8" /><path d="M3 3v5h5" /><text x="12" y="15.5" font-size="7" text-anchor="middle" fill="currentColor" stroke="none">10</text></svg>
      </button>
      <button class="ib play" onclick={() => ui.togglePlay()} aria-label={$clock.paused ? 'Play' : 'Pause'}>
        {#if $clock.waiting && !$clock.paused}
          <span class="spin" aria-hidden="true"></span>
        {:else if $clock.paused}
          <svg viewBox="0 0 24 24" width="40" height="40" aria-hidden="true"><path fill="currentColor" d="M8 5.5v13l10.5-6.5z" /></svg>
        {:else}
          <svg viewBox="0 0 24 24" width="40" height="40" aria-hidden="true"><path fill="currentColor" d="M7 5h3.5v14H7zM13.5 5H17v14h-3.5z" /></svg>
        {/if}
      </button>
      <button class="ib big" onclick={() => ui.seek(10)} aria-label="Forward 10 seconds">
        <svg viewBox="0 0 24 24" width="30" height="30" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M21 12a9 9 0 1 1-3-6.7L21 8" /><path d="M21 3v5h-5" /><text x="12" y="15.5" font-size="7" text-anchor="middle" fill="currentColor" stroke="none">10</text></svg>
      </button>
    </div>

    <div class="bottom">
      <div
        class="bar"
        bind:this={bar}
        role="slider"
        tabindex="0"
        aria-label="Seek"
        aria-valuemin={0}
        aria-valuemax={Math.round(dur)}
        aria-valuenow={Math.round(shownT)}
        aria-valuetext={fmtTime(shownT)}
        onpointerdown={barDown}
        onpointermove={barMove}
        onpointerup={barUp}
        onpointercancel={barUp}
        onpointerleave={() => !ui.scrubbing && (hoverT = null)}
        onkeydown={barKey}
      >
        <div class="rail">
          {#each $clock.buffered as [s, e], i (i)}
            <i class="buf" style:left="{pct(s)}%" style:width="{pct(e) - pct(s)}%"></i>
          {/each}
          {#if intro && dur > 0}<i class="intro" style:left="{pct(intro[0])}%" style:width="{pct(intro[1]) - pct(intro[0])}%"></i>{/if}
          <i class="played" style:width="{pct(shownT)}%"></i>
        </div>
        <i class="thumb" class:active={ui.scrubbing} style:left="{pct(shownT)}%"></i>
        {#if hoverT !== null || scrubT !== null}
          <span class="bubble" style:left="{scrubT !== null ? pct(scrubT) : hoverX}%">{fmtTime(scrubT ?? hoverT ?? 0)}</span>
        {/if}
      </div>
      <div class="row">
        <span class="time">{fmtTime(shownT)} / {fmtTime(dur)}</span>
        <span class="sp"></span>
        {#if next}
          <button class="ib" onclick={() => next?.go()} aria-label="Next episode: {next.title}" title="Next (N)">
            <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true"><path fill="currentColor" d="M6 5.5v13l9-6.5zM16 5h2.5v14H16z" /></svg>
          </button>
        {/if}
        <button class="ib" onclick={() => ui.toggleCaptions(subs)} aria-label="Subtitles (C)" aria-pressed={ui.subId !== 'off'}>
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><rect x="3" y="5" width="18" height="14" rx="2" /><path d="M10 10.5a2 2 0 1 0 0 3M17 10.5a2 2 0 1 0 0 3" /></svg>
        </button>
        <button class="ib" onclick={() => ui.lock()} aria-label="Lock controls">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><rect x="5" y="11" width="14" height="9" rx="2" /><path d="M8 11V8a4 4 0 0 1 8 0v3" /></svg>
        </button>
        <button class="ib txt" onclick={() => ui.cycleAspect()} aria-label="Aspect: {ASPECT_LABEL[ui.aspect]}">{ASPECT_LABEL[ui.aspect]}</button>
        <button class="ib" onclick={() => ui.toggleFullscreen()} aria-label={ui.fullscreen ? 'Exit fullscreen' : 'Fullscreen'}>
          {#if ui.fullscreen}
            <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M9 4v5H4M15 4v5h5M9 20v-5H4M15 20v-5h5" /></svg>
          {:else}
            <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5" /></svg>
          {/if}
        </button>
      </div>
    </div>
  </div>

  {#if inIntro && intro}
    <button class="skip glass" onclick={() => ui.seekTo(intro?.[1] ?? 0)}>Skip Intro ›</button>
  {/if}

  {#if ui.settings}<SettingsSheet {ui} {subs} />{/if}
{/if}

<style>
  .ctl {
    position: absolute;
    inset: 0;
    z-index: 5;
    opacity: 0;
    transition: opacity 220ms ease;
    pointer-events: none;
    display: grid;
    grid-template-rows: auto 1fr auto;
    container-type: inline-size;
  }
  .ctl.show {
    opacity: 1;
  }
  .ctl.show button,
  .ctl.show .bar {
    pointer-events: auto;
  }
  .scrim-top,
  .scrim-bot {
    position: absolute;
    left: 0;
    right: 0;
    height: 38%;
    pointer-events: none;
  }
  .scrim-top {
    top: 0;
    background: linear-gradient(rgb(0 0 0 / 0.7), transparent);
  }
  .scrim-bot {
    bottom: 0;
    background: linear-gradient(transparent, rgb(0 0 0 / 0.8));
  }
  .top,
  .bottom,
  .mid {
    position: relative;
  }
  .top {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: calc(6px + var(--safe-t)) calc(8px + var(--safe-r)) 0 calc(8px + var(--safe-l));
  }
  .ttl {
    flex: 1;
    min-width: 0;
    display: grid;
  }
  .ttl strong,
  .ttl span {
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .ttl span {
    font-size: 0.8rem;
    color: var(--text-secondary);
  }
  .mid {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: clamp(20px, 9cqw, 72px);
  }
  .ib {
    display: inline-grid;
    place-items: center;
    min-width: 44px;
    height: 44px;
    padding: 0 6px;
    border: 0;
    border-radius: 50%;
    background: none;
    color: #fff;
    cursor: pointer;
    filter: drop-shadow(0 1px 3px rgb(0 0 0 / 0.6));
  }
  .ib:hover,
  .ib:focus-visible {
    background: rgb(255 255 255 / 0.12);
  }
  .ib[aria-pressed='true'] {
    color: var(--accent);
  }
  .ib.txt {
    border-radius: 99px;
    font-size: 0.78rem;
    font-weight: 700;
    padding: 0 10px;
  }
  .big {
    width: 54px;
    height: 54px;
  }
  .play {
    width: 72px;
    height: 72px;
    background: rgb(0 0 0 / 0.35);
    backdrop-filter: blur(10px);
    -webkit-backdrop-filter: blur(10px);
    box-shadow: inset 0 0 0 1px rgb(255 255 255 / 0.18);
  }
  .spin {
    width: 34px;
    height: 34px;
    border-radius: 50%;
    border: 3px solid rgb(255 255 255 / 0.25);
    border-top-color: var(--accent);
    animation: spin 0.8s linear infinite;
  }
  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }
  .bottom {
    padding: 0 calc(12px + var(--safe-r)) calc(4px + var(--safe-b)) calc(12px + var(--safe-l));
  }
  .bar {
    position: relative;
    height: 26px;
    display: flex;
    align-items: center;
    cursor: pointer;
    touch-action: none;
    outline: none;
  }
  .rail {
    position: relative;
    flex: 1;
    height: 4px;
    border-radius: 2px;
    background: rgb(255 255 255 / 0.22);
    overflow: hidden;
    transition: height 120ms ease;
  }
  .bar:hover .rail,
  .bar:focus-visible .rail {
    height: 6px;
  }
  .rail i {
    position: absolute;
    top: 0;
    bottom: 0;
  }
  .buf {
    background: rgb(255 255 255 / 0.38);
  }
  .intro {
    background: rgb(255 214 0 / 0.45);
  }
  .played {
    left: 0;
    background: var(--accent);
  }
  .thumb {
    position: absolute;
    top: 50%;
    width: 14px;
    height: 14px;
    margin: -7px 0 0 -7px;
    border-radius: 50%;
    background: var(--accent);
    box-shadow: 0 0 0 3px rgb(229 9 20 / 0.25);
    transition: transform 120ms ease;
  }
  .thumb.active {
    transform: scale(1.35);
  }
  .bubble {
    position: absolute;
    bottom: 26px;
    transform: translateX(-50%);
    padding: 3px 8px;
    border-radius: 8px;
    background: rgb(0 0 0 / 0.8);
    font-size: 0.78rem;
    font-variant-numeric: tabular-nums;
    pointer-events: none;
    white-space: nowrap;
  }
  .row {
    display: flex;
    align-items: center;
    gap: 2px;
  }
  .time {
    font-size: 0.8rem;
    font-variant-numeric: tabular-nums;
    color: rgb(255 255 255 / 0.9);
  }
  .sp {
    flex: 1;
  }
  .skip {
    position: absolute;
    z-index: 6;
    right: calc(16px + var(--safe-r));
    bottom: calc(86px + var(--safe-b));
    padding: 10px 18px;
    border: 0;
    border-radius: 99px;
    font-weight: 800;
    cursor: pointer;
  }
  .unlock {
    position: absolute;
    z-index: 6;
    top: 50%;
    left: 50%;
    transform: translate(-50%, -50%);
    display: flex;
    gap: 8px;
    align-items: center;
    padding: 12px 18px;
    border: 0;
    border-radius: 99px;
    font-weight: 700;
    cursor: pointer;
  }
  @media (max-width: 480px) {
    .ib.txt {
      display: none;
    }
  }
</style>
