<script lang="ts">
  // Custom Liquid Glass title bar for the frameless desktop window (Tauri on Windows & Linux).
  // Drag anywhere on the bar, double-click to maximize, min / max / close on the right.
  import { theme } from '$lib/design/theme.svelte';
  import { desktop } from './desktop.svelte';
</script>

<header class="titlebar" data-theme-light={theme.isLight ? '1' : undefined} data-tauri-drag-region>
  <div class="brand" data-tauri-drag-region>
    <svg class="mark" viewBox="0 0 100 100" width="18" height="18" aria-hidden="true">
      <g stroke="var(--bg, #09090B)" stroke-width="3" stroke-linejoin="round">
        <path d="M42.50,62.99L19.72,76.14A40,40 0,0 1,42.50,10.71L42.50,37.01Z" fill="#E50914" />
        <path d="M42.50,37.01L42.50,10.71A40,40 0,0 1,87.78,63.15L65.00,50.00Z" fill="#C8102E" />
        <path d="M65.00,50.00L87.78,63.15A40,40 0,0 1,19.72,76.14L42.50,62.99Z" fill="#FF2D3B" />
      </g>
      <path d="M65.00,50.00L42.50,62.99L42.50,37.01Z" fill="#FFFFFF" />
    </svg>
    <span class="wordmark" data-tauri-drag-region>
      <span class="cine">CINE</span><span class="tor">TOR</span>
    </span>
  </div>
  <div class="ctl">
    <button class="win-btn" type="button" onclick={() => desktop.minimize()} aria-label="Minimize" title="Minimize">
      <svg viewBox="0 0 12 12" width="12" height="12" aria-hidden="true"><path d="M2 6.5h8" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" /></svg>
    </button>
    <button
      class="win-btn"
      type="button"
      onclick={() => desktop.toggleMaximize()}
      aria-label={desktop.maximized ? 'Restore' : 'Maximize'}
      title={desktop.maximized ? 'Restore' : 'Maximize'}
    >
      {#if desktop.maximized}
        <svg viewBox="0 0 12 12" width="12" height="12" fill="none" stroke="currentColor" stroke-width="1.1" aria-hidden="true"><rect x="2" y="3.6" width="6.4" height="6.4" rx="1" /><path d="M4 3.6V2.6a.8.8 0 0 1 .8-.8h4.6a.8.8 0 0 1 .8.8v4.6a.8.8 0 0 1-.8.8h-1" /></svg>
      {:else}
        <svg viewBox="0 0 12 12" width="12" height="12" fill="none" stroke="currentColor" stroke-width="1.1" aria-hidden="true"><rect x="2" y="2" width="8" height="8" rx="1.2" /></svg>
      {/if}
    </button>
    <button class="win-btn close" type="button" onclick={() => desktop.close()} aria-label="Close" title="Close">
      <svg viewBox="0 0 12 12" width="12" height="12" aria-hidden="true"><path d="M2.5 2.5l7 7M9.5 2.5l-7 7" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" /></svg>
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
    color: var(--text-primary);
    background:
      linear-gradient(180deg, rgb(255 255 255 / 0.06), transparent 65%),
      linear-gradient(90deg, color-mix(in srgb, var(--tint, var(--accent)) 12%, transparent), transparent 46%),
      color-mix(in srgb, var(--surface) 74%, transparent);
    -webkit-backdrop-filter: blur(var(--blur, 20px)) saturate(165%);
    backdrop-filter: blur(var(--blur, 20px)) saturate(165%);
    box-shadow:
      inset 0 1px 0 rgb(255 255 255 / 0.16),
      0 6px 20px -10px rgb(0 0 0 / 0.55);
    border-bottom: 1px solid var(--glass-stroke);
  }

  /* Windows 11 Mica / Acrylic lets native OS vibrancy breathe through lighter glass. */
  :global(html.tauri-glass) .titlebar {
    background:
      linear-gradient(180deg, rgb(255 255 255 / 0.07), transparent 65%),
      linear-gradient(90deg, color-mix(in srgb, var(--tint, var(--accent)) 10%, transparent), transparent 46%),
      color-mix(in srgb, var(--surface) 48%, transparent);
  }

  /* Matte: crisp solid chrome with zero specular glare or blur. */
  :global([data-theme='matte']) .titlebar {
    -webkit-backdrop-filter: none;
    backdrop-filter: none;
    background: var(--surface);
    box-shadow: none;
    border-bottom: 1px solid var(--divider);
  }

  /* Pearl: warm daylight frosted chrome with crisp dark controls (≥ 14:1 contrast). */
  :global([data-theme='pearl']) .titlebar {
    background:
      linear-gradient(180deg, rgb(255 255 255 / 0.86), rgb(255 255 255 / 0.42)),
      linear-gradient(90deg, color-mix(in srgb, var(--tint, var(--accent)) 7%, transparent), transparent 46%),
      color-mix(in srgb, var(--surface) 84%, transparent);
    box-shadow:
      inset 0 1px 0 rgb(255 255 255 / 0.92),
      0 4px 14px -8px rgb(28 22 18 / 0.12);
    border-bottom: 1px solid rgb(20 20 22 / 0.1);
  }

  /* WebKitGTK on Linux & LOW performance tier: avoid expensive live blur, keep rich theme tint. */
  :global(html.tauri-linux) .titlebar,
  :global([data-blur='none']) .titlebar {
    -webkit-backdrop-filter: none;
    backdrop-filter: none;
    background:
      linear-gradient(90deg, color-mix(in srgb, var(--tint, var(--accent)) 10%, transparent), transparent 46%),
      color-mix(in srgb, var(--surface) 95%, var(--bg));
  }
  :global(html.tauri-linux[data-theme='pearl']) .titlebar,
  :global([data-blur='none'][data-theme='pearl']) .titlebar {
    background: color-mix(in srgb, var(--surface) 96%, var(--bg));
  }

  .brand {
    display: flex;
    align-items: center;
    gap: 8px;
    padding-left: 12px;
    height: 100%;
    flex: 1;
    min-width: 0;
  }
  .mark {
    display: block;
    flex: none;
    pointer-events: none;
  }
  .wordmark {
    display: inline-flex;
    align-items: baseline;
    font-size: 11.5px;
    letter-spacing: 0.34em;
    text-transform: uppercase;
    color: color-mix(in srgb, var(--text-primary) 92%, var(--text-secondary));
    pointer-events: none;
  }
  .cine {
    font-weight: 300;
    pointer-events: none;
  }
  .tor {
    font-weight: 700;
    pointer-events: none;
  }
  .ctl {
    display: flex;
    height: 100%;
    flex: none;
  }
  .win-btn {
    width: 46px;
    height: 100%;
    min-height: 0;
    padding: 0;
    border-radius: 0;
    display: grid;
    place-items: center;
    border: 0;
    background: transparent;
    color: color-mix(in srgb, var(--text-primary) 88%, var(--text-secondary));
    cursor: default;
    transition:
      background-color 120ms ease,
      color 120ms ease;
  }
  .win-btn:hover {
    background: color-mix(in srgb, var(--text-primary) 10%, transparent);
    color: var(--text-primary);
  }
  .win-btn:active {
    background: color-mix(in srgb, var(--text-primary) 16%, transparent);
  }
  .win-btn.close:hover {
    background: #e11d2e;
    color: #ffffff;
  }
  .win-btn.close:active {
    background: #b00610;
    color: #ffffff;
  }
  .win-btn:focus-visible {
    outline: 2px solid var(--accent, #e11d2e);
    outline-offset: -2px;
    border-radius: 0;
  }
</style>
