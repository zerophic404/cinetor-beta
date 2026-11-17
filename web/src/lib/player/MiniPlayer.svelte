<script lang="ts">
  // This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer,
  // scrape or hack anything without Mythbrix's explicit permission.
  // In-app floating mini player: draggable, snaps to corners, tap expands, swipe off stops.
  // Lives in the /app layout → survives route changes. Shares the single <video>.
  import { goto } from '$app/navigation';
  import { engine } from './engine';
  import { clock, session, togglePiP, pipSupported } from './controller';
  import { cornerPos, isSwipeOff, snapCorner, clampMiniWidth, miniFrac, miniHeight, resizeWidth, MINI_DEFAULT_FRAC, MINI_WIDTH_KEY, type Corner } from './mini-math';
  import { watchHref } from '$lib/ui/handoff';
  import { desktop } from '$lib/desktop/desktop.svelte';
  import { localKV } from '$lib/data/kv';

  let { hidden = false }: { hidden?: boolean } = $props();
  const sess = session.state;
  const ytMode = engine.isYouTube;

  let corner = $state<Corner>('br');
  let vw = $state(typeof window !== 'undefined' ? window.innerWidth : 400);
  let vh = $state(typeof window !== 'undefined' ? window.innerHeight : 800);
  let drag = $state<{ x: number; y: number } | null>(null);
  let leaving = $state(false);

  // Persisted size (fraction of the viewport width, clamped 25–80 % — aspect stays 16:9).
  const kv = localKV();
  const storedFrac = (() => {
    const v = Number(kv.get(MINI_WIDTH_KEY));
    return Number.isFinite(v) && v > 0 ? v : MINI_DEFAULT_FRAC;
  })();
  let widthFrac = $state(storedFrac);
  let resizing = $state(false);

  const w = $derived(clampMiniWidth(vw * widthFrac, vw));
  const h = $derived(miniHeight(w));
  const insets = $derived({ top: 16, right: 12, left: vw >= 900 ? 100 : 12, bottom: vw >= 900 ? 16 : 84 });
  const rest = $derived(cornerPos(corner, w, h, vw, vh, insets));
  const pos = $derived(drag ?? rest);
  const show = $derived($sess.mode === 'mini' && !!$sess.media && !hidden);

  function host(node: HTMLElement) {
    const detach = engine.attach(node);
    return { destroy: detach };
  }

  let start = { px: 0, py: 0, x: 0, y: 0, t: 0, lastX: 0, lastT: 0, vx: 0, moved: false };
  function down(e: PointerEvent) {
    if ((e.target as Element).closest('button')) return;
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    start = { px: e.clientX, py: e.clientY, x: pos.x, y: pos.y, t: performance.now(), lastX: e.clientX, lastT: performance.now(), vx: 0, moved: false };
  }
  function move(e: PointerEvent) {
    if (!start.t) return;
    const dx = e.clientX - start.px;
    const dy = e.clientY - start.py;
    if (!start.moved && Math.hypot(dx, dy) < 8) return;
    start.moved = true;
    const now = performance.now();
    start.vx = (e.clientX - start.lastX) / Math.max(1, now - start.lastT);
    start.lastX = e.clientX;
    start.lastT = now;
    drag = { x: start.x + dx, y: Math.min(vh - h, Math.max(0, start.y + dy)) };
  }
  function up() {
    if (!start.t) return;
    start.t = 0;
    if (!start.moved) {
      drag = null;
      expand();
      return;
    }
    const d = drag;
    if (d && isSwipeOff({ x: d.x, y: d.y, w, h }, vw, start.vx)) {
      leaving = true;
      setTimeout(() => {
        leaving = false;
        drag = null;
        void session.stop();
      }, 180);
      drag = { x: start.vx < 0 || d.x < 0 ? -w - 20 : vw + 20, y: d.y };
      return;
    }
    if (d) corner = snapCorner({ x: d.x, y: d.y, w, h }, vw, vh, start.vx, 0);
    drag = null;
  }
  function expand() {
    const m = $sess.media;
    if (!m) return;
    session.expand();
    goto(watchHref(m.item.id, m.episode?.id));
  }
  function toggle() {
    engine.togglePlay();
  }

  // ---- corner resize handle (pointer events; 16:9 kept, size persisted) ----
  let rs = { px: 0, w: 0, on: false };
  function resizeDown(e: PointerEvent) {
    e.stopPropagation();
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    rs = { px: e.clientX, w, on: true };
    resizing = true;
  }
  function resizeMove(e: PointerEvent) {
    if (!rs.on) return;
    e.stopPropagation();
    widthFrac = miniFrac(resizeWidth(rs.w, e.clientX - rs.px, corner, vw), vw);
  }
  function resizeUp(e: PointerEvent) {
    if (!rs.on) return;
    e.stopPropagation();
    rs.on = false;
    resizing = false;
    kv.set(MINI_WIDTH_KEY, String(widthFrac));
  }
  /** Keyboard resize for TV / a11y: ±5 % steps. */
  function resizeKey(e: KeyboardEvent) {
    const step = e.key === 'ArrowRight' || e.key === 'ArrowUp' ? 0.05 : e.key === 'ArrowLeft' || e.key === 'ArrowDown' ? -0.05 : 0;
    if (!step) return;
    e.preventDefault();
    widthFrac = miniFrac(vw * (widthFrac + step), vw);
    kv.set(MINI_WIDTH_KEY, String(widthFrac));
  }
</script>

<svelte:window
  onresize={() => {
    vw = window.innerWidth;
    vh = window.innerHeight;
  }}
/>

{#if show && $sess.media}
  <div
    class="mini glass"
    class:dragging={!!drag && !leaving}
    class:resizing
    style:width="{w}px"
    style:height="{h}px"
    style:transform="translate({pos.x}px, {pos.y}px)"
    onpointerdown={down}
    onpointermove={move}
    onpointerup={up}
    onpointercancel={up}
    role="group"
    aria-label="Mini player: {$sess.media.title}"
  >
    <div class="host" use:host></div>
    <div class="bar" aria-hidden="true"><i style:width="{$clock.dur ? ($clock.t / $clock.dur) * 100 : 0}%"></i></div>
    <div class="btns">
      <button onclick={toggle} aria-label={$clock.paused ? 'Play' : 'Pause'}>
        {#if $clock.paused}
          <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><path fill="currentColor" d="M8 5.5v13l10.5-6.5z" /></svg>
        {:else}
          <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><path fill="currentColor" d="M7 5h3.5v14H7zM13.5 5H17v14h-3.5z" /></svg>
        {/if}
      </button>
      {#if !$ytMode && pipSupported()}
        <button onclick={() => togglePiP()} aria-label="Picture in picture">
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><rect x="3" y="5" width="18" height="14" rx="2" /><rect x="12" y="12" width="7" height="5" rx="1" fill="currentColor" /></svg>
        </button>
      {/if}
      {#if desktop.isTauri}
        <button onclick={() => desktop.setMini(true)} aria-label="Pop out (always-on-top mini window)" title="Pop out">
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M14 4h6v6M20 4l-8 8M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5" /></svg>
        </button>
      {/if}
      <button onclick={expand} aria-label="Expand player">
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M4 9V4h5M20 15v5h-5M4 4l6 6M20 20l-6-6" /></svg>
      </button>
      <button onclick={() => session.stop()} aria-label="Close player">
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2.2" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18" /></svg>
      </button>
    </div>
    <button
      class="grip"
      class:left={corner.endsWith('r')}
      onpointerdown={resizeDown}
      onpointermove={resizeMove}
      onpointerup={resizeUp}
      onpointercancel={resizeUp}
      onkeydown={resizeKey}
      aria-label="Resize mini player ({Math.round(widthFrac * 100)}% of screen width)"
      title="Drag to resize"
    >
      <svg viewBox="0 0 12 12" width="12" height="12" aria-hidden="true"><path fill="currentColor" d="M11 1v10H1v-2h8V1z" opacity=".9" /></svg>
    </button>
  </div>
{/if}

<style>
  .mini {
    position: fixed;
    left: 0;
    top: 0;
    z-index: 60;
    overflow: hidden;
    border-radius: 14px;
    background: #000;
    touch-action: none;
    cursor: grab;
    transition: transform 260ms var(--ease-emphasized, cubic-bezier(0.2, 0, 0, 1));
    box-shadow: 0 18px 40px -12px rgb(0 0 0 / 0.8), 0 0 0 1px rgb(255 255 255 / 0.12);
  }
  .mini.dragging {
    transition: none;
    cursor: grabbing;
  }
  .mini.resizing {
    transition: none;
  }
  .grip {
    position: absolute;
    bottom: 0;
    right: 0;
    width: 26px;
    height: 26px;
    display: grid;
    place-items: center;
    padding: 0;
    border: 0;
    background: rgb(0 0 0 / 0.45);
    color: #fff;
    border-top-left-radius: 10px;
    cursor: nwse-resize;
    touch-action: none;
    opacity: 0.75;
  }
  .grip.left {
    right: auto;
    left: 0;
    border-top-left-radius: 0;
    border-top-right-radius: 10px;
    cursor: nesw-resize;
  }
  .grip:hover,
  .grip:focus-visible {
    opacity: 1;
  }
  .host {
    position: absolute;
    inset: 0;
  }
  .host :global(video) {
    width: 100%;
    height: 100%;
    display: block;
    object-fit: cover;
    pointer-events: none;
  }
  .host :global(.cinetor-yt-wrap) {
    pointer-events: none;
  }
  .bar {
    position: absolute;
    left: 0;
    right: 0;
    bottom: 0;
    height: 3px;
    background: rgb(255 255 255 / 0.2);
  }
  .bar i {
    display: block;
    height: 100%;
    background: var(--accent);
  }
  .btns {
    position: absolute;
    top: 4px;
    right: 4px;
    display: flex;
    gap: 2px;
  }
  .btns button {
    width: 32px;
    height: 32px;
    display: grid;
    place-items: center;
    border: 0;
    border-radius: 50%;
    background: rgb(0 0 0 / 0.55);
    color: #fff;
    cursor: pointer;
  }
</style>
