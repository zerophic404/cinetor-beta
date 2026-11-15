<script lang="ts">
  // Custom glass title bar for the frameless desktop window (Tauri). Drag anywhere on the bar,
  // double-click to maximize (native drag-region behaviour), min / max / close on the right.
  import { base } from '$app/paths';
  import { desktop } from './desktop.svelte';
</script>

<header class="titlebar" data-tauri-drag-region>
  <div class="brand" data-tauri-drag-region>
    <img src="{base}/favicon.svg" alt="" width="16" height="16" data-tauri-drag-region />
    <span data-tauri-drag-region>Cinetor</span>
  </div>
  <div class="ctl">
    <button class="btn" onclick={() => desktop.minimize()} aria-label="Minimize" title="Minimize">
      <svg viewBox="0 0 12 12" width="12" height="12" aria-hidden="true"><path d="M2 6.5h8" stroke="currentColor" stroke-width="1.2" /></svg>
    </button>
    <button class="btn" onclick={() => desktop.toggleMaximize()} aria-label={desktop.maximized ? 'Restore' : 'Maximize'} title={desktop.maximized ? 'Restore' : 'Maximize'}>
      {#if desktop.maximized}
        <svg viewBox="0 0 12 12" width="12" height="12" fill="none" stroke="currentColor" stroke-width="1.1" aria-hidden="true"><rect x="2" y="3.6" width="6.4" height="6.4" rx="1" /><path d="M4 3.6V2.6a.8.8 0 0 1 .8-.8h4.6a.8.8 0 0 1 .8.8v4.6a.8.8 0 0 1-.8.8h-1" /></svg>
      {:else}
        <svg viewBox="0 0 12 12" width="12" height="12" fill="none" stroke="currentColor" stroke-width="1.1" aria-hidden="true"><rect x="2" y="2" width="8" height="8" rx="1.2" /></svg>
      {/if}
    </button>
    <button class="btn close" onclick={() => desktop.close()} aria-label="Close" title="Close">
      <svg viewBox="0 0 12 12" width="12" height="12" aria-hidden="true"><path d="M2.5 2.5l7 7M9.5 2.5l-7 7" stroke="currentColor" stroke-width="1.2" /></svg>
    </button>
  </div>
</header>

<style>
  .titlebar {
    position: fixed;
    inset: 0 0 auto 0;
    height: var(--titlebar-h, 36px);
    z-index: 1000;
    display: flex;
    align-items: center;
    justify-content: space-between;
    user-select: none;
    -webkit-user-select: none;
    background: linear-gradient(180deg, rgb(0 0 0 / 0.55), rgb(0 0 0 / 0.3));
    -webkit-backdrop-filter: blur(18px) saturate(1.4);
    backdrop-filter: blur(18px) saturate(1.4);
    border-bottom: 1px solid rgb(255 255 255 / 0.06);
  }
  :global(html.tauri-linux) .titlebar {
    /* WebKitGTK: backdrop blur is expensive → near-opaque glass tint. */
    -webkit-backdrop-filter: none;
    backdrop-filter: none;
    background: rgb(10 10 12 / 0.94);
  }
  .brand {
    display: flex;
    align-items: center;
    gap: 8px;
    padding-left: 12px;
    height: 100%;
    flex: 1;
    font-size: 12.5px;
    font-weight: 600;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: rgb(255 255 255 / 0.82);
  }
  .brand img {
    display: block;
    pointer-events: none;
  }
  .ctl {
    display: flex;
    height: 100%;
  }
  .btn {
    width: 46px;
    height: 100%;
    display: grid;
    place-items: center;
    border: 0;
    background: transparent;
    color: rgb(255 255 255 / 0.85);
    cursor: default;
    transition: background 120ms ease;
  }
  .btn:hover {
    background: rgb(255 255 255 / 0.1);
  }
  .btn.close:hover {
    background: #e50914;
    color: #fff;
  }
  .btn:focus-visible {
    outline: 2px solid var(--accent, #e50914);
    outline-offset: -2px;
  }
</style>
