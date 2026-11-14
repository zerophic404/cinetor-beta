<script lang="ts">
  // Full player: the persistent <video> is MOVED into .stage (instant start, buffer kept).
  import { onMount } from 'svelte';
  import { engine } from './engine';
  import { clock, nextUp, session, sleep } from './controller';
  import { attachGestures, volumeReadOnly, type LevelKind } from './Gestures';
  import { BRIGHTNESS_MAX, BRIGHTNESS_MIN } from './gesture-math';
  import { keyAction } from './keys';
  import { navKey, tvEnabled } from '../tv/spatial';
  import { externalPlayerHref, type Media } from './sources';
  import { PlayerUI } from './ui.svelte';
  import Controls from './Controls.svelte';
  import Subtitles from './Subtitles.svelte';
  import { settings } from '$lib/settings/store.svelte';

  interface Props {
    media: Media;
    onBack: () => void;
    /** Live TV mode: LIVE badge + channel zapping (PageUp/PageDown, ↑/↓ while controls are hidden, centre swipe). */
    live?: boolean;
    liveInfo?: string;
    onZap?: (dir: 1 | -1) => void;
  }
  let { media, onBack, live = false, liveInfo = '', onZap }: Props = $props();

  const ui = new PlayerUI();
  const sess = session.state;
  const err = engine.error;
  let stage: HTMLDivElement | undefined = $state();
  let countdown = $state<number | null>(null);
  const iosVolume = volumeReadOnly();

  const intro = $derived<[number, number] | null>(
    media.introEndSec && media.introEndSec > (media.introStartSec ?? 0) ? [media.introStartSec ?? 0, media.introEndSec] : null
  );
  const unsupported = $derived($sess.error === 'unsupported' || !!$err?.unsupported);
  const errorMsg = $derived($err?.msg ?? ($sess.error && $sess.error !== 'unsupported' && $sess.error !== 'gesture' ? $sess.error : null));

  // New media → fresh UI state (subs, lock, offset).
  let lastKey = '';
  $effect(() => {
    if (media.key !== lastKey) {
      lastKey = media.key;
      ui.reset();
      countdown = null;
      autoSubs();
    }
  });

  /** Settings → Subtitles → turn on automatically in the preferred language. */
  function autoSubs() {
    if (!settings.s.subsAuto) return;
    const want = settings.s.subLang;
    const i = media.subtitles.findIndex((s) => s.lang.toLowerCase().startsWith(want));
    if (i < 0) return;
    void ui.selectSub(`side:${i}`, media.subtitles);
  }

  /** Svelte action: host the shared element here while mounted. */
  function host(node: HTMLElement) {
    const detach = engine.attach(node);
    return { destroy: detach };
  }

  onMount(() => {
    if (!stage) return;
    ui.stage = stage;
    ui.show();
    if ($tvEnabled) stage.focus({ preventScroll: true });
    // Settings → Playback: gestures off = taps only; double-tap step configurable.
    const offGestures = attachGestures(stage, {
      locked: () => ui.locked || !settings.s.gestures,
      tap: (type) => (type === 'mouse' ? ui.togglePlay() : ui.toggleControls()),
      dblclick: () => void ui.toggleFullscreen(),
      doubleTapSeek: (side, total, step) => side !== 'center' && ui.doubleTap(side, total, step),
      holdStart: () => ui.holdStart(),
      holdEnd: () => ui.holdEnd(),
      time: () => engine.video?.currentTime ?? 0,
      duration: () => engine.video?.duration || 0,
      seekPreview: (target, delta) => {
        ui.drag = { target, delta };
      },
      seekCommit: (target) => {
        ui.drag = null;
        ui.seekTo(target);
      },
      levelGet: (k: LevelKind) => (k === 'brightness' ? ui.brightness : (engine.video?.volume ?? 1)),
      levelSet: (k: LevelKind, v: number) => {
        if (k === 'brightness') ui.setBrightness(v);
        else if (iosVolume) ui.flash('Use the device volume buttons', 1600);
        else {
          ui.setVolume(v);
          ui.showHud('volume', v);
        }
      },
      levelEnd: () => {},
      swipeV: live && onZap ? (dir) => zap(dir) : undefined
    }, settings.s.doubleTapSec);
    const fs = () => (ui.fullscreen = document.fullscreenElement === stage);
    document.addEventListener('fullscreenchange', fs);
    return () => {
      offGestures();
      document.removeEventListener('fullscreenchange', fs);
      if (document.fullscreenElement === stage) document.exitFullscreen().catch(() => {});
      ui.dispose();
    };
  });

  // Autoplay next: 10 s countdown card when the episode ends (not with "sleep: end of episode").
  const ended = $derived($clock.ended);
  $effect(() => {
    if (!ended || !$nextUp || !settings.s.autoplayNext || $sleep.mode === 'end' || $sess.mode !== 'full') {
      countdown = null;
      return;
    }
    countdown = 10;
    const go = $nextUp.go;
    const id = setInterval(() => {
      if (countdown === null) return;
      countdown -= 1;
      if (countdown <= 0) {
        clearInterval(id);
        countdown = null;
        go();
      }
    }, 1000);
    return () => clearInterval(id);
  });

  /** TV / remote: ←/→ ±10 s, Enter play/pause, ↑ shows controls + focuses them, Back minimizes. */
  function onTvKey(e: KeyboardEvent): boolean {
    if (!$tvEnabled || !stage || e.ctrlKey || e.metaKey || e.altKey) return false;
    // Already handled by spatial nav (e.g. Back inside the controls refocused the stage).
    if (e.defaultPrevented) return true;
    const k = navKey(e);
    if (!k) return false;
    const a = document.activeElement;
    // Focus inside the controls → spatial navigation moves between buttons.
    if (a && a !== stage && a !== document.body && stage.contains(a)) {
      ui.show();
      return true;
    }
    if (document.querySelector('dialog[open]')) return true;
    e.preventDefault();
    if (k.type === 'back') {
      onBack();
      return true;
    }
    if (k.type === 'enter') {
      ui.togglePlay();
      ui.show();
      return true;
    }
    if (k.dir === 'left' || k.dir === 'right') {
      ui.seek(k.dir === 'left' ? -10 : 10);
      ui.show();
      return true;
    }
    ui.show();
    if (k.dir === 'up') {
      const btn = stage.querySelector<HTMLElement>('.ctl .play') ?? stage.querySelector<HTMLElement>('.ctl button');
      btn?.focus({ preventScroll: true });
    }
    return true;
  }

  function zap(dir: 1 | -1) {
    if (!onZap) return;
    ui.flash(dir > 0 ? 'Channel ▲' : 'Channel ▼', 700);
    onZap(dir);
  }

  /** Live TV: channel up/down. ↑/↓ only zap while the controls are hidden (else volume / focus). */
  function onLiveKey(e: KeyboardEvent): boolean {
    if (!live || !onZap || e.defaultPrevented || e.ctrlKey || e.metaKey || e.altKey) return false;
    const t = e.target as HTMLElement | null;
    if (t?.closest('input, textarea, select, [contenteditable="true"]') || document.querySelector('dialog[open]')) return false;
    const k = e.key;
    let dir: 0 | 1 | -1 = 0;
    if (k === 'PageUp' || k === 'ChannelUp') dir = 1;
    else if (k === 'PageDown' || k === 'ChannelDown') dir = -1;
    else if (!ui.visible && !ui.locked && (k === 'ArrowUp' || k === 'ArrowDown')) {
      const a = document.activeElement;
      if (a && stage && a !== stage && stage.contains(a)) return false;
      dir = k === 'ArrowUp' ? 1 : -1;
    }
    if (!dir) return false;
    e.preventDefault();
    zap(dir);
    return true;
  }

  function onKey(e: KeyboardEvent) {
    if (onLiveKey(e)) return;
    if (onTvKey(e)) return;
    const t = e.target as HTMLElement | null;
    if (t && (t.closest('input, textarea, select, [contenteditable="true"]') || (t.getAttribute('role') === 'slider' && e.key.startsWith('Arrow')))) return;
    const a = keyAction(e);
    if (!a || ui.locked) return;
    e.preventDefault();
    switch (a.type) {
      case 'toggle':
        return ui.togglePlay();
      case 'seek':
        ui.seek(a.by);
        return ui.show();
      case 'seekPct':
        if (engine.video?.duration) ui.seekTo((engine.video.duration * a.pct) / 100);
        return;
      case 'volume': {
        if (iosVolume) return ui.flash('Use the device volume buttons');
        const v = Math.min(1, Math.max(0, (engine.video?.volume ?? 1) + a.by));
        ui.setVolume(v);
        return ui.showHud('volume', v);
      }
      case 'fullscreen':
        return void ui.toggleFullscreen();
      case 'mute':
        ui.toggleMute();
        return ui.flash(engine.video?.muted ? 'Muted' : 'Sound on');
      case 'captions':
        return ui.toggleCaptions(media.subtitles);
      case 'speed':
        return ui.stepRate(a.dir);
      case 'next':
        return $nextUp?.go();
    }
  }

  const ext = $derived(media.source ? externalPlayerHref(media.source.url, typeof navigator !== 'undefined' ? navigator.userAgent : '') : '');
  const pctLevel = (v: number) => `${Math.round(v * 100)}%`;
</script>

<svelte:window onkeydown={onKey} />

<div
  class="stage"
  bind:this={stage}
  data-aspect={ui.aspect}
  class:hide-cursor={!ui.visible && !ui.settings}
  style:--bright={ui.brightness}
  style:--cue-size="{ui.subStyle.size}%"
  style:--cue-color={ui.subStyle.color}
  style:--cue-bg="rgb(0 0 0 / {ui.subStyle.bg})"
  onpointermove={(e) => e.pointerType === 'mouse' && ui.show()}
  role="region"
  aria-label="Video player"
  data-tv-player
  tabindex="-1"
>
  <div class="host" use:host></div>
  {#if live}
    <div class="live-badge" class:dim={!ui.visible} aria-label="Live">
      <span class="dot" aria-hidden="true"></span>LIVE{#if liveInfo}<small>{liveInfo}</small>{/if}
    </div>
  {/if}
  <Subtitles {ui} raised={ui.visible} />

  {#if ui.ripple}
    {#key ui.ripple.n}
      <div class="ripple {ui.ripple.side}" aria-hidden="true"><span>{ui.ripple.side === 'left' ? '«' : '»'} {ui.ripple.text}</span></div>
    {/key}
  {/if}
  {#if ui.holding}<div class="pill" aria-live="polite">{settings.s.holdSpeed}× ▸▸</div>{/if}
  {#if ui.drag}<div class="pill center">{ui.dragLabel()}</div>{/if}
  {#if ui.hud}
    <div class="hud {ui.hud.kind}" aria-hidden="true">
      <span>{ui.hud.kind === 'volume' ? '🔊' : '☀'}</span>
      <i style:height={pctLevel(ui.hud.value)}></i>
      <small>{ui.hud.kind === 'brightness' ? pctLevel((ui.brightness - BRIGHTNESS_MIN) / (BRIGHTNESS_MAX - BRIGHTNESS_MIN)) : pctLevel(ui.hud.value)}</small>
    </div>
  {/if}
  {#if ui.hint}<div class="pill center" role="status">{ui.hint}</div>{/if}

  {#if unsupported}
    <div class="msg glass" data-no-gesture role="alert">
      <strong>Can't play this format here</strong>
      <p>{$err?.msg ?? 'This browser cannot decode this file (e.g. MKV / HEVC).'}</p>
      {#if media.source}
        <div class="acts">
          <a class="btn btn-primary" href={ext} target="_blank" rel="noopener noreferrer">Open in external player</a>
          <button class="btn btn-ghost" onclick={() => media.source && navigator.clipboard?.writeText(media.source.url)}>Copy link</button>
        </div>
      {/if}
    </div>
  {:else if errorMsg}
    <div class="msg glass" data-no-gesture role="alert">
      <strong>Playback problem</strong>
      <p>{errorMsg}</p>
      <div class="acts"><button class="btn btn-primary" onclick={() => session.play(media, { start: engine.video?.currentTime ?? 0 })}>Retry</button></div>
    </div>
  {:else if $sess.error === 'gesture'}
    <button
      class="tapplay"
      data-no-gesture
      onclick={() => {
        session.clearError();
        ui.togglePlay();
      }}
      aria-label="Play"
    >
      <svg viewBox="0 0 24 24" width="48" height="48" aria-hidden="true"><path fill="currentColor" d="M8 5.5v13l10.5-6.5z" /></svg>
    </button>
  {:else if $sess.loading}
    <div class="loading" aria-label="Loading"><span></span></div>
  {/if}

  {#if countdown !== null && $nextUp}
    <div class="next glass" data-no-gesture>
      <span>Next: <strong>{$nextUp.title}</strong> in {countdown}s</span>
      <div class="acts">
        <button class="btn btn-primary" onclick={() => $nextUp?.go()}>Play now</button>
        <button class="btn btn-ghost" onclick={() => (countdown = null)}>Cancel</button>
      </div>
    </div>
  {/if}

  {#if !unsupported}
    <Controls {ui} title={media.title} subtitle={media.subtitle} subs={media.subtitles} {intro} next={$nextUp} {onBack} />
  {/if}
</div>

<style>
  .live-badge {
    position: absolute;
    z-index: 4;
    top: calc(12px + var(--safe-t, 0px));
    right: 14px;
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 4px 10px;
    border-radius: 8px;
    background: #e50914;
    color: #fff;
    font-weight: 800;
    font-size: 0.75rem;
    letter-spacing: 0.06em;
    pointer-events: none;
    transition: opacity 0.3s;
  }
  .live-badge.dim {
    opacity: 0.75;
  }
  .live-badge small {
    font-weight: 600;
    letter-spacing: 0;
    max-width: 40vw;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .live-badge .dot {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: #fff;
    animation: livepulse 1.4s ease-in-out infinite;
  }
  @keyframes livepulse {
    50% { opacity: 0.3; }
  }
  .stage:focus,
  .stage:focus-visible {
    outline: none;
  }
  .stage {
    position: relative;
    width: 100%;
    aspect-ratio: 16 / 9;
    max-height: 100dvh;
    background: #000;
    overflow: hidden;
    touch-action: none;
    user-select: none;
    -webkit-user-select: none;
    -webkit-touch-callout: none;
    container-type: inline-size;
  }
  .stage.hide-cursor {
    cursor: none;
  }
  .stage:fullscreen {
    aspect-ratio: auto;
    width: 100vw;
    height: 100vh;
  }
  .host {
    position: absolute;
    inset: 0;
  }
  .host :global(video) {
    width: 100%;
    height: 100%;
    display: block;
    object-fit: contain;
    background: #000;
    filter: brightness(var(--bright, 1));
  }
  .stage[data-aspect='zoom'] .host :global(video) {
    object-fit: cover;
  }
  .stage[data-aspect='fill'] .host :global(video) {
    object-fit: fill;
  }
  /* Embedded (in-stream) text tracks rendered natively: same style settings. */
  .host :global(video::cue) {
    font-size: calc(var(--cue-size, 100%) * 1);
    color: var(--cue-color, #fff);
    background: var(--cue-bg, rgb(0 0 0 / 0.5));
  }
  .ripple {
    position: absolute;
    top: 0;
    bottom: 0;
    width: 38%;
    display: grid;
    place-items: center;
    z-index: 4;
    pointer-events: none;
    background: radial-gradient(circle at var(--rx) 50%, rgb(255 255 255 / 0.18), transparent 65%);
    animation: rip 750ms ease-out forwards;
  }
  .ripple.left {
    left: 0;
    --rx: 30%;
    border-radius: 0 50% 50% 0 / 0 100% 100% 0;
  }
  .ripple.right {
    right: 0;
    --rx: 70%;
    border-radius: 50% 0 0 50% / 100% 0 0 100%;
  }
  .ripple span {
    font-weight: 800;
    font-size: 1.05rem;
    text-shadow: 0 1px 4px rgb(0 0 0 / 0.8);
  }
  @keyframes rip {
    from {
      opacity: 1;
    }
    70% {
      opacity: 1;
    }
    to {
      opacity: 0;
    }
  }
  .pill {
    position: absolute;
    top: calc(14px + var(--safe-t));
    left: 50%;
    transform: translateX(-50%);
    z-index: 6;
    padding: 6px 14px;
    border-radius: 99px;
    background: rgb(0 0 0 / 0.65);
    font-weight: 800;
    font-variant-numeric: tabular-nums;
    pointer-events: none;
    white-space: nowrap;
  }
  .pill.center {
    top: 50%;
    transform: translate(-50%, -50%);
    font-size: 1.1rem;
  }
  .hud {
    position: absolute;
    top: 50%;
    transform: translateY(-50%);
    z-index: 6;
    width: 46px;
    height: 150px;
    padding: 8px 0;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: space-between;
    border-radius: 23px;
    background: rgb(0 0 0 / 0.6);
    pointer-events: none;
    overflow: hidden;
  }
  .hud.volume {
    right: 8%;
  }
  .hud.brightness {
    left: 8%;
  }
  .hud i {
    position: absolute;
    left: 0;
    right: 0;
    bottom: 0;
    background: color-mix(in srgb, var(--accent) 55%, transparent);
    z-index: -1;
  }
  .hud span,
  .hud small {
    position: relative;
    font-size: 0.75rem;
    font-weight: 700;
  }
  .msg,
  .next {
    position: absolute;
    z-index: 7;
    left: 50%;
    top: 50%;
    transform: translate(-50%, -50%);
    width: min(440px, calc(100% - 32px));
    padding: 18px;
    text-align: center;
  }
  .msg p {
    margin: 6px 0 12px;
    color: var(--text-secondary);
    font-size: 0.9rem;
  }
  .acts {
    display: flex;
    gap: 10px;
    justify-content: center;
    flex-wrap: wrap;
  }
  .next {
    top: auto;
    bottom: 18%;
    transform: translateX(-50%);
    display: grid;
    gap: 10px;
  }
  .tapplay {
    position: absolute;
    z-index: 7;
    inset: 0;
    margin: auto;
    width: 88px;
    height: 88px;
    border: 0;
    border-radius: 50%;
    background: var(--accent);
    color: #fff;
    cursor: pointer;
    box-shadow: 0 10px 40px -8px var(--accent);
  }
  .loading {
    position: absolute;
    inset: 0;
    display: grid;
    place-items: center;
    pointer-events: none;
    z-index: 2;
  }
  .loading span {
    width: 42px;
    height: 42px;
    border-radius: 50%;
    border: 3px solid rgb(255 255 255 / 0.2);
    border-top-color: var(--accent);
    animation: spin 0.8s linear infinite;
  }
  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }
</style>
