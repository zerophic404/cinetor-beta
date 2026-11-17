<script lang="ts">
  // This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer,
  // scrape or hack anything without Mythbrix's explicit permission.
  import { music } from './player.svelte';
  import { qualityShortLabel } from './gateway-music';

  let { hidden = false }: { hidden?: boolean } = $props();

  const cur = $derived(music.current);
  const pct = $derived(music.dur > 0 ? Math.min(100, Math.max(0, (music.t / music.dur) * 100)) : 0);
  const badgeText = $derived(
    cur?.hasFlac === false ? '320K' : qualityShortLabel(music.quality).toUpperCase()
  );
</script>

{#if cur && !hidden && !music.expanded}
  <div
    class="bar glass glass-bar"
    style:--c={cur.color}
    style:--a={cur.accent || 'var(--accent)'}
    role="region"
    aria-label="Now playing"
  >
    <button
      type="button"
      class="open"
      onclick={() => (music.expanded = true)}
      aria-label="Open now playing: {cur.title} by {cur.artist}"
    >
      {#if cur.art}
        <img src={cur.art} alt="" width="44" height="44" decoding="async" />
      {:else}
        <span class="art-ph" aria-hidden="true">♪</span>
      {/if}
      <span class="txt">
        <b>{cur.title}</b>
        <small>
          {#if music.error}
            <span class="err-txt">{music.error}</span>
          {:else}
            {cur.artist} · <span class="q-tag">{badgeText}</span>
          {/if}
        </small>
      </span>
    </button>

    <button
      type="button"
      class="ic"
      onclick={() => music.toggle()}
      aria-label={music.playing ? 'Pause' : 'Play'}
    >
      {#if music.loading}
        <span class="spin" aria-hidden="true"></span>
      {:else if music.playing}
        <svg viewBox="0 0 24 24" width="24" height="24" aria-hidden="true">
          <path fill="currentColor" d="M7 5h3.5v14H7zM13.5 5H17v14h-3.5z" />
        </svg>
      {:else}
        <svg viewBox="0 0 24 24" width="24" height="24" aria-hidden="true">
          <path fill="currentColor" d="M8 5.5v13l10.5-6.5z" />
        </svg>
      {/if}
    </button>

    <button type="button" class="ic" onclick={() => music.next()} aria-label="Next track">
      <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
        <path fill="currentColor" d="M6 6l8.5 6L6 18zM16 6h2.2v12H16z" />
      </svg>
    </button>

    <button type="button" class="ic x" onclick={() => music.stop()} aria-label="Close music player">✕</button>

    <span class="prog" aria-hidden="true"><i style:width="{pct}%"></i></span>
  </div>
{/if}

<style>
  .bar {
    position: fixed;
    z-index: 49;
    left: calc(14px + var(--safe-l));
    right: calc(14px + var(--safe-r));
    bottom: calc(var(--nav-h) + 18px + var(--safe-b));
    display: flex;
    align-items: center;
    gap: 4px;
    height: 62px;
    padding: 0 8px 0 10px;
    border-radius: 20px;
    overflow: hidden;
    color: var(--text-primary);
    background: linear-gradient(90deg, color-mix(in srgb, var(--c) 24%, transparent), transparent 76%), var(--glass-fill);
  }
  @media (min-width: 900px) {
    .bar {
      left: auto;
      right: calc(16px + var(--safe-r));
      bottom: calc(16px + var(--safe-b));
      width: 430px;
    }
  }
  .open {
    flex: 1;
    display: flex;
    align-items: center;
    gap: 10px;
    min-width: 0;
    border: 0;
    background: none;
    text-align: left;
    cursor: pointer;
    padding: 0;
  }
  img,
  .art-ph {
    width: 44px;
    height: 44px;
    border-radius: 10px;
    object-fit: cover;
    flex: none;
    background: var(--surface-elevated);
  }
  .art-ph {
    display: grid;
    place-items: center;
    color: var(--text-tertiary);
    font-size: 1.05rem;
  }
  .txt {
    display: grid;
    gap: 2px;
    min-width: 0;
  }
  b,
  small {
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  b {
    font-size: 0.88rem;
    font-weight: 700;
  }
  small {
    font-size: 0.75rem;
    color: var(--text-secondary);
  }
  .q-tag {
    color: var(--accent);
    font-weight: 700;
  }
  .err-txt {
    color: var(--error, #ff3b30);
  }
  .ic {
    flex: none;
    width: 40px;
    height: 40px;
    display: grid;
    place-items: center;
    border: 0;
    border-radius: 50%;
    background: none;
    color: var(--text-primary);
    cursor: pointer;
  }
  .ic:hover,
  .ic:focus-visible {
    background: var(--glass-fill);
  }
  .x {
    font-size: 0.85rem;
    color: var(--text-secondary);
  }
  .spin {
    width: 18px;
    height: 18px;
    border-radius: 50%;
    border: 2px solid color-mix(in srgb, var(--accent) 28%, transparent);
    border-top-color: var(--accent);
    animation: spin 0.75s linear infinite;
  }
  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }
  .prog {
    position: absolute;
    left: 0;
    right: 0;
    bottom: 0;
    height: 2.5px;
    background: var(--divider);
  }
  .prog i {
    display: block;
    height: 100%;
    background: var(--accent);
    transition: width 180ms linear;
  }
</style>
