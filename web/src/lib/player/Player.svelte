<script lang="ts">
  // This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer,
  // scrape or hack anything without Mythbrix's explicit permission.
  // Full & inline player: the persistent <video> / chromeless YouTube stage is MOVED into .stage (instant start, buffer kept).
  import { onMount } from 'svelte';
  import { engine } from './engine';
  import { clock, mediaFor, nextUp, prepareMedia, session, sleep } from './controller';
  import { attachGestures, volumeReadOnly, type LevelKind } from './Gestures';
  import { BRIGHTNESS_MAX, BRIGHTNESS_MIN } from './gesture-math';
  import { keyAction } from './keys';
  import { navKey, tvEnabled } from '../tv/spatial';
  import { externalPlayerHref, isLocalMediaUrl, type Media } from './sources';
  import { PlayerUI, ui as sharedUi } from './ui.svelte';
  import Controls from './Controls.svelte';
  import Subtitles from './Subtitles.svelte';
  import { settings } from '$lib/settings/store.svelte';
  import type { Episode, Item } from '$lib/data/types';
  import { base } from '$app/paths';

  interface Props {
    media?: Media;
    item?: Item | null;
    season?: number | null;
    episode?: Episode | number | null;
    onBack?: () => void;
    inline?: boolean;
    onExitInline?: () => void;
    /** Live TV mode: LIVE badge + channel zapping (PageUp/PageDown, ↑/↓ while controls are hidden, centre swipe). */
    live?: boolean;
    liveInfo?: string;
    onZap?: (dir: 1 | -1) => void;
  }
  let {
    media: propMedia,
    item = null,
    season = null,
    episode = null,
    onBack,
    inline = false,
    onExitInline,
    live = false,
    liveInfo = '',
    onZap
  }: Props = $props();

  const ui = new PlayerUI();
  const sess = session.state;
  const err = engine.error;
  let stage: HTMLDivElement | undefined = $state();
  let countdown = $state<number | null>(null);
  let triedUrls = $state.raw<string[]>([]);
  const iosVolume = volumeReadOnly();

  const resolvedEp = $derived.by((): Episode | undefined => {
    if (episode && typeof episode === 'object') return episode;
    if (!item?.seasons?.length) return undefined;
    const sObj = season != null ? (item.seasons.find((s) => s.n === season) ?? item.seasons[0]) : item.seasons[0];
    if (!sObj) return undefined;
    if (typeof episode === 'number') return sObj.episodes.find((e) => e.n === episode) ?? sObj.episodes[0];
    return sObj.episodes[0];
  });

  const activeMedia = $derived.by((): Media | undefined => {
    if (propMedia) {
      if ($sess.media && $sess.media.key === propMedia.key) return $sess.media;
      return propMedia;
    }
    if ($sess.media && (!item || $sess.media.item.id === item.id)) return $sess.media;
    if (item) return mediaFor(item, resolvedEp);
    return undefined;
  });

  const handleBack = () => {
    if (inline && onExitInline) {
      onExitInline();
      return;
    }
    if (onBack) {
      onBack();
      return;
    }
    session.minimize();
  };

  // When <Player {item} {season} {episode} inline={true} /> is mounted without pre-passed `media`, auto-prepare & play.
  let autoPreparedKey = '';
  $effect(() => {
    if (propMedia || !item) return;
    const targetKey = resolvedEp ? `${item.id}#${resolvedEp.id}` : item.id;
    if (autoPreparedKey === targetKey || $sess.media?.key === targetKey) return;
    autoPreparedKey = targetKey;
    const targetItem = item;
    const targetEp = resolvedEp;
    void prepareMedia(targetItem, targetEp).then((m) => {
      if (autoPreparedKey === targetKey) void session.play(m);
    });
  });

  const intro = $derived<[number, number] | null>(
    activeMedia?.introEndSec && activeMedia.introEndSec > (activeMedia.introStartSec ?? 0)
      ? [activeMedia.introStartSec ?? 0, activeMedia.introEndSec]
      : null
  );
  const unsupported = $derived($sess.error === 'unsupported' || !!$err?.unsupported);
  const noSource = $derived($sess.error === 'nosource' || !!activeMedia?.noSource);
  const errorMsg = $derived(
    $err?.msg ??
      ($sess.error && $sess.error !== 'unsupported' && $sess.error !== 'gesture' && $sess.error !== 'nosource'
        ? $sess.error
        : null)
  );

  // Sync shared inlineMounted state so <MiniPlayer> hides while inline player is visible on /app/title.
  $effect(() => {
    if (!inline) return;
    sharedUi.inlineMounted = true;
    return () => {
      sharedUi.inlineMounted = false;
    };
  });

  // New media → fresh UI state (subs, lock, offset).
  let lastKey = '';
  $effect(() => {
    const k = activeMedia?.key ?? '';
    if (k && k !== lastKey) {
      lastKey = k;
      ui.reset();
      countdown = null;
      triedUrls = [];
      autoSubs();
    }
  });

  function tryAnotherSource() {
    if (!activeMedia) return;
    const variants = activeMedia.variants ?? [];
    const curUrl = $sess.media?.source?.url ?? activeMedia.source?.url ?? '';
    const seen = curUrl && !triedUrls.includes(curUrl) ? [...triedUrls, curUrl] : triedUrls;
    triedUrls = seen;
    const alt = variants.find((v) => v.url && !seen.includes(v.url)) ?? variants.find((v) => v.url && v.url !== curUrl);
    session.clearError();
    if (alt) {
      void session.play({ ...activeMedia, source: alt, playable: true }, { start: engine.currentTime });
    } else {
      triedUrls = [];
      void session.play(activeMedia, { start: engine.currentTime });
    }
  }

  /** Settings → Subtitles → turn on automatically in the preferred language. */
  function autoSubs() {
    if (!settings.s.subsAuto || !activeMedia) return;
    const want = settings.s.subLang;
    const i = activeMedia.subtitles.findIndex((s) => s.lang.toLowerCase().startsWith(want));
    if (i < 0) return;
    void ui.selectSub(`side:${i}`, activeMedia.subtitles);
  }

  /** Svelte action: host the shared element here while mounted. */
  function host(node: HTMLElement) {
    const detach = engine.attach(node);
    return { destroy: detach };
  }

  onMount(() => {
    if (inline) sharedUi.inlineMounted = true;
    if (!stage) {
      return () => {
        if (inline) {
          sharedUi.inlineMounted = false;
          if (session.snapshot.media && session.snapshot.mode === 'full') session.minimize();
        }
      };
    }
    ui.stage = stage;
    ui.show();
    if ($tvEnabled && !inline) stage.focus({ preventScroll: true });
    // Settings → Playback: gestures off = taps only; double-tap step configurable.
    const offGestures = attachGestures(
      stage,
      {
        locked: () => ui.locked || !settings.s.gestures,
        tap: (type) => (type === 'mouse' ? ui.togglePlay() : ui.toggleControls()),
        dblclick: () => void ui.toggleFullscreen(),
        doubleTapSeek: (side, total, step) => side !== 'center' && ui.doubleTap(side, total, step),
        holdStart: () => ui.holdStart(),
        holdEnd: () => ui.holdEnd(),
        time: () => engine.currentTime,
        duration: () => engine.duration,
        seekPreview: (target, delta) => {
          ui.drag = { target, delta };
        },
        seekCommit: (target) => {
          ui.drag = null;
          ui.seekTo(target);
        },
        levelGet: (k: LevelKind) => (k === 'brightness' ? ui.brightness : engine.volume),
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
      },
      settings.s.doubleTapSec
    );
    const fs = () => (ui.fullscreen = document.fullscreenElement === stage);
    document.addEventListener('fullscreenchange', fs);
    return () => {
      offGestures();
      document.removeEventListener('fullscreenchange', fs);
      if (document.fullscreenElement === stage) document.exitFullscreen().catch(() => {});
      ui.dispose();
      if (inline) {
        sharedUi.inlineMounted = false;
        // Navigating away from /app/title while inline playback is active → float seamlessly in <MiniPlayer>!
        if (session.snapshot.media && session.snapshot.mode === 'full') {
          const pref = settings.s as typeof settings.s & { autoPip?: boolean };
          if (pref.autoPip !== false) session.minimize();
          else void session.stop();
        }
      }
    };
  });

  // Autoplay next: 10 s countdown card when the episode ends (not with "sleep: end of episode").
  const ended = $derived($clock.ended);
  $effect(() => {
    if (!ended || !$nextUp || !settings.s.autoplayNext || $sleep.mode === 'end' || ($sess.mode !== 'full' && !inline)) {
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
    if (inline && document.fullscreenElement !== stage && !stage.contains(document.activeElement)) return false;
    if (e.defaultPrevented) return true;
    const k = navKey(e);
    if (!k) return false;
    const a = document.activeElement;
    if (a && a !== stage && a !== document.body && stage.contains(a)) {
      ui.show();
      return true;
    }
    if (document.querySelector('dialog[open]')) return true;
    e.preventDefault();
    if (k.type === 'back') {
      handleBack();
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
    const t = e.target instanceof Element ? e.target : null;
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
    if (!activeMedia) return;
    if (onLiveKey(e)) return;
    if (onTvKey(e)) return;
    const t = e.target as HTMLElement | null;
    if (
      t &&
      (t.closest('input, textarea, select, [contenteditable="true"]') ||
        (t.getAttribute('role') === 'slider' && e.key.startsWith('Arrow')) ||
        document.querySelector('dialog[open]'))
    ) {
      return;
    }
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
        if (engine.duration > 0) ui.seekTo((engine.duration * a.pct) / 100);
        return;
      case 'volume': {
        if (iosVolume) return ui.flash('Use the device volume buttons');
        const v = Math.min(1, Math.max(0, engine.volume + a.by));
        ui.setVolume(v);
        return ui.showHud('volume', v);
      }
      case 'fullscreen':
        return void ui.toggleFullscreen();
      case 'mute':
        ui.toggleMute();
        return ui.flash(engine.muted ? 'Muted' : 'Sound on');
      case 'captions':
        return ui.toggleCaptions(activeMedia.subtitles);
      case 'speed':
        return ui.stepRate(a.dir);
      case 'next':
        return $nextUp?.go();
    }
  }

  // §2: a stream URL must never reach a link, share intent or the clipboard. Only an already
  // downloaded (local) file may be handed to an external player; otherwise we offer the app link.
  const extOk = $derived(isLocalMediaUrl(activeMedia?.source?.url));
  const ext = $derived(
    extOk && activeMedia?.source
      ? externalPlayerHref(activeMedia.source.url, typeof navigator !== 'undefined' ? navigator.userAgent : '')
      : ''
  );
  let copied = $state(false);
  /** Copies Cinetor's own watch link (never a stream URL) — §2. */
  async function copyAppLink() {
    try {
      await navigator.clipboard?.writeText(location.href);
      copied = true;
      setTimeout(() => (copied = false), 1800);
    } catch {
      /* clipboard denied — nothing to report, and never a URL in an error */
    }
  }
  const pctLevel = (v: number) => `${Math.round(v * 100)}%`;
</script>

<svelte:window onkeydown={onKey} />

<div
  class="stage player"
  class:inline
  bind:this={stage}
  data-aspect={ui.aspect}
  class:hide-cursor={!ui.visible && !ui.settings}
  style:--bright={ui.brightness}
  style:--cue-size="{ui.subStyle.size}%"
  style:--cue-color={ui.subStyle.color}
  style:--cue-bg={ui.subStyle.bg <= 0.01 ? 'transparent' : `rgb(0 0 0 / ${ui.subStyle.bg})`}
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

  {#if noSource}
    <div class="msg glass" data-no-gesture role="alert">
      <strong>No source available</strong>
      <p>This title has no playable stream right now. It will be hidden from lists.</p>
      <div class="acts"><button class="btn btn-ghost" onclick={handleBack}>Back</button></div>
    </div>
  {:else if unsupported}
    <div class="msg glass" data-no-gesture role="alert">
      <strong>Can't play this format here</strong>
      <p>{$err?.msg ?? 'This browser cannot decode this stream format.'}</p>
      {#if activeMedia?.source}
        <div class="acts">
          {#if activeMedia.variants && activeMedia.variants.length > 1}
            <button class="btn btn-primary" onclick={tryAnotherSource}>Try another source</button>
          {/if}
          {#if extOk}
            <a class="btn btn-primary" href={ext} target="_blank" rel="noopener noreferrer">Open in external player</a>
          {:else}
            <a class="btn {activeMedia.variants && activeMedia.variants.length > 1 ? 'btn-ghost' : 'btn-primary'}" href="{base}/app/downloads">Downloads</a>
          {/if}
          <button class="btn btn-ghost" onclick={copyAppLink}>{copied ? 'Link copied' : 'Copy app link'}</button>
        </div>
        {#if !extOk}
          <p class="hint">Download this title first — a downloaded file can be opened in another player.</p>
        {/if}
      {/if}
    </div>
  {:else if errorMsg && activeMedia}
    <div class="msg glass" data-no-gesture role="alert">
      <strong>Playback problem</strong>
      <p>{errorMsg}</p>
      <div class="acts">
        <button class="btn btn-primary" onclick={() => activeMedia && session.play(activeMedia, { start: engine.currentTime })}>Retry</button>
        {#if activeMedia.variants && activeMedia.variants.length > 1}
          <button class="btn btn-ghost" onclick={tryAnotherSource}>Try another source</button>
        {/if}
      </div>
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
  {:else if $sess.loading || !activeMedia}
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

  {#if !unsupported && activeMedia}
    <Controls
      {ui}
      media={activeMedia}
      title={activeMedia.title}
      subtitle={activeMedia.subtitle}
      subs={activeMedia.subtitles}
      {intro}
      next={$nextUp}
      {inline}
      onBack={handleBack}
    />
  {/if}
</div>

<style>
  .live-badge {
    position: absolute;
    z-index: 4;
    top: calc(56px + var(--safe-t, 0px));
    left: 16px;
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
  .player.inline {
    position: relative;
    width: 100%;
    height: 100%;
    max-height: none;
    border-radius: inherit;
  }
  .stage.hide-cursor {
    cursor: none;
  }
  .stage:fullscreen {
    aspect-ratio: auto;
    width: 100vw;
    height: 100vh;
    border-radius: 0;
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
  .host :global(.cinetor-yt-wrap) {
    filter: brightness(var(--bright, 1));
    pointer-events: none;
  }
  .stage[data-aspect='zoom'] .host :global(.cinetor-yt-slot) {
    transform: scale(1.34);
  }
  .stage[data-aspect='fill'] .host :global(.cinetor-yt-slot) {
    transform: scale(1.18, 1);
  }
  /* Embedded (in-stream) text tracks rendered natively: default 0 % transparent bg + crisp drop shadow. */
  .host :global(video::cue) {
    font-size: calc(var(--cue-size, 100%) * 1);
    color: var(--cue-color, #fff);
    background: var(--cue-bg, transparent);
    text-shadow:
      0 2px 6px rgba(0, 0, 0, 0.92),
      0 0 2px rgba(0, 0, 0, 0.95);
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
  .hint {
    margin: 10px 0 0;
    font-size: 0.78rem;
    opacity: 0.75;
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
