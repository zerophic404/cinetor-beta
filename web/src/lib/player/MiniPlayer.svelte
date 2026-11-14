<script lang="ts">
  // In-app floating mini player: draggable, snaps to corners, tap expands, swipe off stops.
  // Lives in the /app layout → survives route changes. Shares the single <video>.
  import { goto } from '$app/navigation';
  import { engine } from './engine';
  import { clock, session, togglePiP, pipSupported } from './controller';
  import { cornerPos, isSwipeOff, snapCorner, type Corner } from './mini-math';
  import { watchHref } from '$lib/ui/handoff';

  let { hidden = false }: { hidden?: boolean } = $props();
  const sess = session.state;

  let corner = $state<Corner>('br');
  let vw = $state(typeof window !== 'undefined' ? window.innerWidth : 400);
  let vh = $state(typeof window !== 'undefined' ? window.innerHeight : 800);
  let drag = $state<{ x: number; y: number } | null>(null);
  let leaving = $state(false);

  const w = $derived(Math.round(Math.min(340, Math.max(180, vw * 0.42))));
  const h = $derived(Math.round((w * 9) / 16));
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
    const v = engine.video;
    if (!v) return;
    if (v.paused) v.play().catch(() => {});
    else v.pause();
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
      {#if pipSupported()}
        <button onclick={() => togglePiP()} aria-label="Picture in picture">
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><rect x="3" y="5" width="18" height="14" rx="2" /><rect x="12" y="12" width="7" height="5" rx="1" fill="currentColor" /></svg>
        </button>
      {/if}
      <button onclick={expand} aria-label="Expand player">
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M4 9V4h5M20 15v5h-5M4 4l6 6M20 20l-6-6" /></svg>
      </button>
      <button onclick={() => session.stop()} aria-label="Close player">
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2.2" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18" /></svg>
      </button>
    </div>
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
