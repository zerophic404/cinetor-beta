<script lang="ts">
  // Desktop mini-player window content: the whole (480x270, always-on-top) window is the video.
  // Drag anywhere to move; hover shows play/pause, restore and close.
  import { engine } from '$lib/player/engine';
  import { clock, session } from '$lib/player/controller';
  import { desktop } from './desktop.svelte';

  const sess = session.state;
  const show = $derived(desktop.mini && !!$sess.media);

  // Playback ended / stopped while popped out → back to the normal window.
  $effect(() => {
    if (desktop.mini && !$sess.media) void desktop.setMini(false);
  });

  function host(node: HTMLElement) {
    const detach = engine.attach(node);
    return { destroy: detach };
  }
  function down(e: PointerEvent) {
    if (e.button !== 0 || (e.target as Element).closest('button')) return;
    void desktop.startDrag();
  }
  function toggle() {
    const v = engine.video;
    if (!v) return;
    if (v.paused) v.play().catch(() => {});
    else v.pause();
  }
  async function close() {
    await desktop.setMini(false);
    void session.stop();
  }
</script>

{#if show}
  <div class="dmini" role="group" aria-label="Mini player" onpointerdown={down} ondblclick={() => desktop.setMini(false)}>
    <div class="host" use:host></div>
    <div class="bar" aria-hidden="true"><i style:width="{$clock.dur ? ($clock.t / $clock.dur) * 100 : 0}%"></i></div>
    <div class="ui">
      <span class="title">{$sess.media?.title}</span>
      <div class="btns">
        <button onclick={toggle} aria-label={$clock.paused ? 'Play' : 'Pause'}>
          {#if $clock.paused}
            <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><path fill="currentColor" d="M8 5.5v13l10.5-6.5z" /></svg>
          {:else}
            <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><path fill="currentColor" d="M7 5h3.5v14H7zM13.5 5H17v14h-3.5z" /></svg>
          {/if}
        </button>
        <button onclick={() => desktop.setMini(false)} aria-label="Restore window" title="Restore window">
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><rect x="3" y="5" width="18" height="14" rx="2" /><path d="M14 10l-4 4M10 10v4h4" /></svg>
        </button>
        <button onclick={close} aria-label="Close player" title="Close">
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2.2" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18" /></svg>
        </button>
      </div>
    </div>
  </div>
{/if}

<style>
  .dmini {
    position: fixed;
    inset: 0;
    z-index: 2000;
    background: #000;
    overflow: hidden;
    cursor: move;
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
    background: var(--accent, #e50914);
  }
  .ui {
    position: absolute;
    inset: 0 0 auto 0;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    padding: 6px 6px 18px 12px;
    background: linear-gradient(180deg, rgb(0 0 0 / 0.7), transparent);
    opacity: 0;
    transition: opacity 160ms ease;
  }
  .dmini:hover .ui,
  .dmini:focus-within .ui {
    opacity: 1;
  }
  .title {
    font-size: 13px;
    font-weight: 600;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    color: #fff;
  }
  .btns {
    display: flex;
    gap: 4px;
    flex: none;
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
  .btns button:hover {
    background: var(--accent, #e50914);
  }
</style>
