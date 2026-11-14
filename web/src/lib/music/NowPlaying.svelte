<script lang="ts">
  import { music } from './player.svelte';
  import { fmtDur } from './queue';

  const cur = $derived(music.current);
  let showQueue = $state(false);
  let dragFrom = $state<number | null>(null);
  let dialog = $state<HTMLDialogElement>();

  $effect(() => {
    if (!dialog) return;
    if (music.expanded && cur && !dialog.open) dialog.showModal();
    else if ((!music.expanded || !cur) && dialog.open) dialog.close();
  });

  function itemKey(e: KeyboardEvent, i: number) {
    if (!e.altKey) return;
    if (e.key === 'ArrowUp' && i > 0) {
      e.preventDefault();
      music.move(i, i - 1);
    } else if (e.key === 'ArrowDown' && i < music.tracks.length - 1) {
      e.preventDefault();
      music.move(i, i + 1);
    }
  }
  function drop(i: number) {
    if (dragFrom !== null && dragFrom !== i) music.move(dragFrom, i);
    dragFrom = null;
  }
</script>

<dialog
  bind:this={dialog}
  class="np"
  aria-label="Now playing"
  onclose={() => (music.expanded = false)}
  style:--c={cur?.color ?? '#300'}
  style:--a={cur?.accent ?? 'var(--accent)'}
>
  {#if cur}
    <div class="bg" style:background-image="url({cur.art})" aria-hidden="true"></div>
    <div class="tint" aria-hidden="true"></div>
    <div class="wrap">
      <header>
        <button class="ic" onclick={() => (music.expanded = false)} aria-label="Minimize">
          <svg viewBox="0 0 24 24" width="26" height="26" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="2" d="M6 9l6 6 6-6" /></svg>
        </button>
        <span class="from">Playing from <b>{cur.album}</b></span>
        <button class="ic" class:on={showQueue} onclick={() => (showQueue = !showQueue)} aria-pressed={showQueue} aria-label="Queue">
          <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="2" d="M4 6h16M4 12h16M4 18h10" /></svg>
        </button>
      </header>

      <div class="main" class:queue-open={showQueue}>
        <section class="now">
          {#key cur.id}<img class="art" src={cur.art} alt="{cur.album} artwork" width="360" height="360" />{/key}
          <div class="meta">
            <h2>{cur.title}</h2>
            <p>{cur.artist}</p>
          </div>
          <div class="seek">
            <input
              type="range"
              min="0"
              max={music.dur || 1}
              step="0.5"
              value={music.t}
              oninput={(e) => music.seek(Number(e.currentTarget.value))}
              aria-label="Seek"
              style:--p="{music.dur ? (music.t / music.dur) * 100 : 0}%"
            />
            <div class="times"><span>{fmtDur(music.t)}</span><span>{fmtDur(music.dur)}</span></div>
          </div>
          <div class="ctl">
            <button class="ic sm" class:on={music.shuffle} aria-pressed={music.shuffle} onclick={() => music.setShuffle(!music.shuffle)} aria-label="Shuffle">
              <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="2" d="M4 7h3c4 0 6 10 10 10h3M4 17h3c1.6 0 2.8-1.6 3.8-3.4M14 9.4C15 8 16 7 17 7h3M18 4l3 3-3 3M18 14l3 3-3 3" /></svg>
            </button>
            <button class="ic" onclick={() => music.prev()} aria-label="Previous">
              <svg viewBox="0 0 24 24" width="30" height="30" aria-hidden="true"><path fill="currentColor" d="M18 6l-8.5 6L18 18zM6 6h2.2v12H6z" /></svg>
            </button>
            <button class="play" onclick={() => music.toggle()} aria-label={music.playing ? 'Pause' : 'Play'}>
              {#if music.playing}
                <svg viewBox="0 0 24 24" width="34" height="34" aria-hidden="true"><path fill="currentColor" d="M7 5h3.5v14H7zM13.5 5H17v14h-3.5z" /></svg>
              {:else}
                <svg viewBox="0 0 24 24" width="34" height="34" aria-hidden="true"><path fill="currentColor" d="M8 5.5v13l10.5-6.5z" /></svg>
              {/if}
            </button>
            <button class="ic" onclick={() => music.next()} aria-label="Next">
              <svg viewBox="0 0 24 24" width="30" height="30" aria-hidden="true"><path fill="currentColor" d="M6 6l8.5 6L6 18zM16 6h2.2v12H16z" /></svg>
            </button>
            <button class="ic sm" class:on={music.repeat !== 'off'} onclick={() => music.cycleRepeat()} aria-label="Repeat: {music.repeat}">
              <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="2" d="M17 2l3 3-3 3M4 11V9a4 4 0 0 1 4-4h12M7 22l-3-3 3-3M20 13v2a4 4 0 0 1-4 4H4" /></svg>
              {#if music.repeat === 'one'}<i class="one">1</i>{/if}
            </button>
          </div>
        </section>

        {#if showQueue}
          <section class="queue" aria-label="Queue">
            <h3>Up next <small>drag or Alt+↑/↓ to reorder</small></h3>
            <ol>
              {#each music.tracks as t, i (t.id)}
                <li
                  class:current={i === music.index}
                  class:over={dragFrom !== null && dragFrom !== i}
                  draggable="true"
                  ondragstart={() => (dragFrom = i)}
                  ondragover={(e) => e.preventDefault()}
                  ondrop={() => drop(i)}
                  ondragend={() => (dragFrom = null)}
                >
                  <button class="row" onclick={() => music.playIndex(i)} onkeydown={(e) => itemKey(e, i)} aria-current={i === music.index ? 'true' : undefined}>
                    <img src={t.art} alt="" width="40" height="40" loading="lazy" />
                    <span><b>{t.title}</b><small>{t.artist} · {fmtDur(t.durationSec)}</small></span>
                  </button>
                  <span class="mv">
                    <button disabled={i === 0} onclick={() => music.move(i, i - 1)} aria-label="Move {t.title} up">▲</button>
                    <button disabled={i === music.tracks.length - 1} onclick={() => music.move(i, i + 1)} aria-label="Move {t.title} down">▼</button>
                    <button onclick={() => music.remove(i)} aria-label="Remove {t.title} from queue">✕</button>
                  </span>
                </li>
              {/each}
            </ol>
          </section>
        {/if}
      </div>
    </div>
  {/if}
</dialog>

<style>
  .np {
    position: fixed;
    inset: 0;
    width: 100vw;
    height: 100dvh;
    max-width: none;
    max-height: none;
    margin: 0;
    padding: 0;
    border: 0;
    color: #fff;
    background: #000;
    overflow: hidden;
  }
  .np[open] {
    animation: rise calc(var(--dur-long, 400ms) * var(--dur-scale, 1)) var(--ease-emphasized, ease);
  }
  @keyframes rise {
    from { transform: translateY(40px); opacity: 0; }
  }
  .np::backdrop {
    background: #000;
  }
  .bg {
    position: absolute;
    inset: -60px;
    background-size: cover;
    background-position: center;
    filter: blur(60px) saturate(1.4);
    opacity: 0.7;
  }
  :global([data-tier='low']) .bg {
    filter: none;
    opacity: 0.2;
  }
  .tint {
    position: absolute;
    inset: 0;
    background: linear-gradient(180deg, color-mix(in srgb, var(--c) 70%, transparent), rgb(0 0 0 / 0.88) 75%);
  }
  .wrap {
    position: relative;
    height: 100%;
    display: flex;
    flex-direction: column;
    padding: calc(10px + var(--safe-t, 0px)) 18px calc(18px + var(--safe-b, 0px));
    box-sizing: border-box;
  }
  header {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }
  .from {
    font-size: 0.78rem;
    opacity: 0.8;
  }
  .main {
    flex: 1;
    min-height: 0;
    display: grid;
    gap: 20px;
    overflow-y: auto;
  }
  @media (min-width: 900px) {
    .main.queue-open {
      grid-template-columns: 1fr 1fr;
    }
  }
  .now {
    display: grid;
    align-content: center;
    justify-items: center;
    gap: 18px;
    max-width: 440px;
    width: 100%;
    margin: 0 auto;
  }
  .art {
    width: min(78vw, 360px, 42dvh);
    height: auto;
    aspect-ratio: 1;
    border-radius: 18px;
    object-fit: cover;
    box-shadow: 0 30px 60px -20px rgb(0 0 0 / 0.8), 0 0 80px -30px var(--a);
    animation: pop 0.4s var(--ease-emphasized, ease);
  }
  @keyframes pop {
    from { transform: scale(0.94); opacity: 0.4; }
  }
  .meta {
    width: 100%;
  }
  h2 {
    margin: 0;
    font-size: 1.5rem;
  }
  .meta p {
    margin: 4px 0 0;
    opacity: 0.75;
  }
  .seek {
    width: 100%;
  }
  input[type='range'] {
    width: 100%;
    appearance: none;
    height: 4px;
    border-radius: 4px;
    background: linear-gradient(90deg, #fff var(--p), rgb(255 255 255 / 0.25) var(--p));
    outline-offset: 6px;
  }
  input[type='range']::-webkit-slider-thumb {
    appearance: none;
    width: 14px;
    height: 14px;
    border-radius: 50%;
    background: #fff;
  }
  input[type='range']::-moz-range-thumb {
    width: 14px;
    height: 14px;
    border: 0;
    border-radius: 50%;
    background: #fff;
  }
  .times {
    display: flex;
    justify-content: space-between;
    font-size: 0.72rem;
    opacity: 0.7;
    margin-top: 6px;
    font-variant-numeric: tabular-nums;
  }
  .ctl {
    display: flex;
    align-items: center;
    justify-content: space-between;
    width: 100%;
  }
  .ic {
    position: relative;
    width: 48px;
    height: 48px;
    display: grid;
    place-items: center;
    border: 0;
    border-radius: 50%;
    background: none;
    color: inherit;
    cursor: pointer;
  }
  .ic.sm {
    opacity: 0.6;
  }
  .ic.on {
    opacity: 1;
    color: var(--a);
  }
  .one {
    position: absolute;
    bottom: 8px;
    right: 8px;
    font-size: 0.6rem;
    font-style: normal;
    font-weight: 800;
  }
  .play {
    width: 72px;
    height: 72px;
    border-radius: 50%;
    border: 0;
    display: grid;
    place-items: center;
    background: #fff;
    color: #000;
    cursor: pointer;
    box-shadow: 0 10px 30px -10px var(--a);
  }
  .queue {
    background: rgb(0 0 0 / 0.35);
    border-radius: 20px;
    padding: 12px;
    min-height: 0;
    overflow-y: auto;
  }
  .queue h3 {
    margin: 0 0 8px;
    font-size: 1rem;
  }
  .queue h3 small {
    font-weight: 500;
    font-size: 0.7rem;
    opacity: 0.6;
    margin-left: 6px;
  }
  ol {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    gap: 4px;
  }
  li {
    display: flex;
    align-items: center;
    gap: 6px;
    border-radius: 12px;
  }
  li.current {
    background: color-mix(in srgb, var(--a) 22%, transparent);
  }
  li.over:hover {
    box-shadow: inset 0 2px 0 var(--a);
  }
  .row {
    flex: 1;
    min-width: 0;
    display: flex;
    gap: 10px;
    align-items: center;
    padding: 6px;
    border: 0;
    background: none;
    color: inherit;
    text-align: left;
    cursor: pointer;
  }
  .row img {
    width: 40px;
    height: 40px;
    border-radius: 8px;
    object-fit: cover;
  }
  .row span {
    display: grid;
    min-width: 0;
  }
  .row b,
  .row small {
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .row small {
    opacity: 0.65;
    font-size: 0.75rem;
  }
  .mv {
    display: flex;
    flex: none;
  }
  .mv button {
    width: 30px;
    height: 30px;
    border: 0;
    background: none;
    color: inherit;
    opacity: 0.7;
    cursor: pointer;
    font-size: 0.7rem;
  }
  .mv button:disabled {
    opacity: 0.2;
  }
</style>
