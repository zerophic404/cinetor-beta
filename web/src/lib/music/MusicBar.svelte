<script lang="ts">
  import { music } from './player.svelte';

  let { hidden = false }: { hidden?: boolean } = $props();
  const cur = $derived(music.current);
  const pct = $derived(music.dur > 0 ? (music.t / music.dur) * 100 : 0);
</script>

{#if cur && !hidden && !music.expanded}
  <div class="bar glass glass-bar" style:--c={cur.color} style:--a={cur.accent} role="region" aria-label="Now playing">
    <button class="open" onclick={() => (music.expanded = true)} aria-label="Open now playing: {cur.title} by {cur.artist}">
      <img src={cur.art} alt="" width="44" height="44" />
      <span class="txt"><b>{cur.title}</b><small>{cur.artist}</small></span>
    </button>
    <button class="ic" onclick={() => music.toggle()} aria-label={music.playing ? 'Pause' : 'Play'}>
      {#if music.playing}
        <svg viewBox="0 0 24 24" width="26" height="26" aria-hidden="true"><path fill="currentColor" d="M7 5h3.5v14H7zM13.5 5H17v14h-3.5z" /></svg>
      {:else}
        <svg viewBox="0 0 24 24" width="26" height="26" aria-hidden="true"><path fill="currentColor" d="M8 5.5v13l10.5-6.5z" /></svg>
      {/if}
    </button>
    <button class="ic" onclick={() => music.next()} aria-label="Next track">
      <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true"><path fill="currentColor" d="M6 6l8.5 6L6 18zM16 6h2.2v12H16z" /></svg>
    </button>
    <button class="ic x" onclick={() => music.stop()} aria-label="Close player">✕</button>
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
    height: 60px;
    padding: 0 8px;
    border-radius: 20px;
    overflow: hidden;
    background: linear-gradient(90deg, color-mix(in srgb, var(--c) 55%, transparent), transparent 80%), var(--glass-fill);
  }
  @media (min-width: 900px) {
    .bar {
      left: auto;
      right: calc(16px + var(--safe-r));
      bottom: calc(16px + var(--safe-b));
      width: 420px;
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
  img {
    width: 44px;
    height: 44px;
    border-radius: 10px;
    object-fit: cover;
    flex: none;
  }
  .txt {
    display: grid;
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
  }
  small {
    font-size: 0.75rem;
    color: var(--text-secondary);
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
    cursor: pointer;
  }
  .ic:hover {
    background: var(--glass-fill);
  }
  .x {
    font-size: 0.85rem;
    color: var(--text-secondary);
  }
  .prog {
    position: absolute;
    left: 0;
    right: 0;
    bottom: 0;
    height: 2px;
    background: rgb(255 255 255 / 0.1);
  }
  .prog i {
    display: block;
    height: 100%;
    background: var(--a, var(--accent));
  }
</style>
