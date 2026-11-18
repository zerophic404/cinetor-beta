<script lang="ts">
  // This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer,
  // scrape or hack anything without Mythbrix's explicit permission.
  import { music } from './player.svelte';
  import { activeLyricIndex, plainLyricParagraphs, qualityShortLabel } from './gateway-music';
  import { fmtDur } from './queue';

  type CenterTab = 'art' | 'lyrics';

  const cur = $derived(music.current);
  let centerTab = $state<CenterTab>('art');
  let showQueue = $state(false);
  let dragFrom = $state<number | null>(null);
  let dialog = $state<HTMLDialogElement>();
  let lyricsBox = $state<HTMLDivElement>();
  let manualScrollUntil = 0;

  const syncedLines = $derived(music.lyrics?.lines ?? []);
  const plainLines = $derived(plainLyricParagraphs(music.lyrics?.plainLyrics));
  const activeLine = $derived(activeLyricIndex(syncedLines, Math.floor(music.t * 1000)));
  const curDlStatus = $derived(cur ? music.downloadStatus(cur.id) : null);

  $effect(() => {
    if (!dialog) return;
    if (music.expanded && cur && !dialog.open) dialog.showModal();
    else if ((!music.expanded || !cur) && dialog.open) dialog.close();
  });

  $effect(() => {
    const idx = activeLine;
    const box = lyricsBox;
    if (centerTab !== 'lyrics' || idx < 0 || !box) return;
    if (Date.now() < manualScrollUntil) return;
    const row = box.querySelector<HTMLElement>(`[data-lyric-idx="${idx}"]`);
    if (!row) return;
    const reduced =
      typeof document !== 'undefined' &&
      (document.documentElement.dataset.motion === 'reduce' ||
        document.documentElement.dataset.tier === 'low' ||
        window.matchMedia?.('(prefers-reduced-motion: reduce)').matches);
    const targetTop = row.offsetTop - box.clientHeight / 2 + row.offsetHeight / 2;
    box.scrollTo({ top: Math.max(0, targetTop), behavior: reduced ? 'auto' : 'smooth' });
  });

  function pauseAutoScroll() {
    manualScrollUntil = Date.now() + 3500;
  }

  function seekToLyric(timeMs: number) {
    manualScrollUntil = 0;
    music.seek(timeMs / 1000);
    if (!music.playing) music.toggle();
  }

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
  style:--c={cur?.color ?? '#22080C'}
  style:--a={cur?.accent ?? 'var(--accent)'}
>
  {#if cur}
    {#if cur.art}
      <div class="bg" style:background-image="url({cur.art})" aria-hidden="true"></div>
    {/if}
    <div class="tint" aria-hidden="true"></div>

    <div class="wrap">
      <header class="topbar">
        <button type="button" class="ic" onclick={() => (music.expanded = false)} aria-label="Minimize player">
          <svg viewBox="0 0 24 24" width="26" height="26" aria-hidden="true">
            <path fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" d="M6 9l6 6 6-6" />
          </svg>
        </button>

        <div class="from">
          <span class="kicker">PLAYING FROM MUSIC</span>
          <b>{cur.album || music.qualityLabel}</b>
        </div>

        <div class="top-actions">
          <button
            type="button"
            class="ic"
            class:done={curDlStatus === 'completed'}
            onclick={() => void music.downloadTrack(cur)}
            aria-label={curDlStatus === 'completed'
              ? `${cur.title} downloaded`
              : `Download ${cur.title} in ${qualityShortLabel(music.quality)}`}
            title={curDlStatus === 'completed'
              ? 'Downloaded'
              : `Download (${qualityShortLabel(music.quality)})`}
          >
            {#if curDlStatus === 'resolving' || curDlStatus === 'queued' || curDlStatus === 'running'}
              <span class="spin" aria-hidden="true"></span>
            {:else if curDlStatus === 'completed'}
              <svg viewBox="0 0 24 24" width="21" height="21" aria-hidden="true">
                <path fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" d="M5 13l4 4L19 7" />
              </svg>
            {:else}
              <svg viewBox="0 0 24 24" width="21" height="21" aria-hidden="true">
                <path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" d="M12 4v11m0 0l-4-4m4 4l4-4M5 19h14" />
              </svg>
            {/if}
          </button>

          <button
            type="button"
            class="ic"
            class:on={showQueue}
            onclick={() => (showQueue = !showQueue)}
            aria-pressed={showQueue}
            aria-label="Toggle queue"
          >
            <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
              <path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" d="M4 6h16M4 12h16M4 18h10" />
            </svg>
          </button>
        </div>
      </header>

      <div class="mode-strip">
        <div class="seg" role="tablist" aria-label="View mode">
          <button
            type="button"
            role="tab"
            aria-selected={centerTab === 'art'}
            class:active={centerTab === 'art'}
            onclick={() => (centerTab = 'art')}
          >
            Artwork
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={centerTab === 'lyrics'}
            class:active={centerTab === 'lyrics'}
            onclick={() => {
              manualScrollUntil = 0;
              centerTab = 'lyrics';
            }}
          >
            Lyrics
            {#if syncedLines.length > 0}<span class="dot" aria-hidden="true"></span>{/if}
          </button>
        </div>

        <div class="seg quality-seg" role="group" aria-label="Streaming and download quality">
          <button
            type="button"
            class:active={music.quality === 'flac' && cur.hasFlac !== false}
            disabled={cur.hasFlac === false}
            aria-pressed={music.quality === 'flac' && cur.hasFlac !== false}
            onclick={() => music.setQuality('flac')}
            title={cur.hasFlac === false ? 'FLAC unavailable for this track' : 'Stream & download in lossless FLAC'}
          >
            FLAC
          </button>
          <button
            type="button"
            class:active={music.quality === '320kbps' || cur.hasFlac === false}
            aria-pressed={music.quality === '320kbps' || cur.hasFlac === false}
            onclick={() => music.setQuality('320kbps')}
            title="Stream & download in 320kbps Studio Master"
          >
            320kbps
          </button>
        </div>
      </div>

      <div class="main" class:queue-open={showQueue}>
        <section class="now">
          {#if centerTab === 'lyrics'}
            <div
              class="lyrics-panel"
              bind:this={lyricsBox}
              onwheel={pauseAutoScroll}
              ontouchmove={pauseAutoScroll}
              aria-label="Track lyrics"
            >
              {#if music.lyricsLoading}
                <div class="lyr-skeleton" aria-busy="true" aria-label="Loading lyrics">
                  <span class="sk-line w70"></span>
                  <span class="sk-line w90"></span>
                  <span class="sk-line w55"></span>
                  <span class="sk-line w80"></span>
                  <span class="sk-line w60"></span>
                </div>
              {:else if syncedLines.length > 0}
                <div class="lyr-list">
                  {#each syncedLines as line, idx (`${line.timeMs}-${idx}`)}
                    <button
                      type="button"
                      class="lyr-line"
                      class:current={idx === activeLine}
                      class:past={activeLine > idx}
                      data-lyric-idx={idx}
                      onclick={() => seekToLyric(line.timeMs)}
                    >
                      {line.text}
                    </button>
                  {/each}
                </div>
              {:else if plainLines.length > 0}
                <div class="lyr-plain">
                  {#each plainLines as para, idx (idx)}
                    <p>{para}</p>
                  {/each}
                </div>
              {:else}
                <div class="lyr-empty">
                  <p>Lyrics unavailable for this track.</p>
                </div>
              {/if}
            </div>
          {:else}
            {#key cur.id}
              {#if cur.art}
                <img class="art" referrerpolicy="no-referrer" src={cur.art} alt="{cur.title} artwork" width="340" height="340" />
              {:else}
                <div class="art art-fallback" aria-hidden="true">♪</div>
              {/if}
            {/key}
          {/if}

          <div class="meta-row">
            <div class="meta">
              <h2>{cur.title}</h2>
              <p>{cur.artist}{cur.year ? ` · ${cur.year}` : ''}</p>
            </div>
            <span class="q-pill">{cur.hasFlac === false ? '320K MASTER' : music.quality === 'flac' ? 'FLAC LOSSLESS' : '320K MASTER'}</span>
          </div>

          {#if music.error}
            <p class="err-banner" role="alert">{music.error}</p>
          {/if}

          <div class="seek">
            <input
              type="range"
              min="0"
              max={music.dur || 1}
              step="0.25"
              value={music.t}
              oninput={(e) => music.seek(Number(e.currentTarget.value))}
              aria-label="Seek position"
              style:--p="{music.dur ? Math.min(100, Math.max(0, (music.t / music.dur) * 100)) : 0}%"
            />
            <div class="times">
              <span>{fmtDur(music.t)}</span>
              <span>{fmtDur(music.dur || cur.durationSec)}</span>
            </div>
          </div>

          <div class="ctl">
            <button
              type="button"
              class="ic sm"
              class:on={music.shuffle}
              aria-pressed={music.shuffle}
              onclick={() => music.setShuffle(!music.shuffle)}
              aria-label="Shuffle"
            >
              <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
                <path fill="none" stroke="currentColor" stroke-width="2" d="M4 7h3c4 0 6 10 10 10h3M4 17h3c1.6 0 2.8-1.6 3.8-3.4M14 9.4C15 8 16 7 17 7h3M18 4l3 3-3 3M18 14l3 3-3 3" />
              </svg>
            </button>

            <button type="button" class="ic" onclick={() => music.prev()} aria-label="Previous track">
              <svg viewBox="0 0 24 24" width="30" height="30" aria-hidden="true">
                <path fill="currentColor" d="M18 6l-8.5 6L18 18zM6 6h2.2v12H6z" />
              </svg>
            </button>

            <button
              type="button"
              class="play"
              onclick={() => music.toggle()}
              aria-label={music.playing ? 'Pause' : 'Play'}
            >
              {#if music.loading}
                <span class="spin lg" aria-hidden="true"></span>
              {:else if music.playing}
                <svg viewBox="0 0 24 24" width="32" height="32" aria-hidden="true">
                  <path fill="currentColor" d="M7 5h3.5v14H7zM13.5 5H17v14h-3.5z" />
                </svg>
              {:else}
                <svg viewBox="0 0 24 24" width="32" height="32" aria-hidden="true">
                  <path fill="currentColor" d="M8 5.5v13l10.5-6.5z" />
                </svg>
              {/if}
            </button>

            <button type="button" class="ic" onclick={() => music.next()} aria-label="Next track">
              <svg viewBox="0 0 24 24" width="30" height="30" aria-hidden="true">
                <path fill="currentColor" d="M6 6l8.5 6L6 18zM16 6h2.2v12H16z" />
              </svg>
            </button>

            <button
              type="button"
              class="ic sm"
              class:on={music.repeat !== 'off'}
              onclick={() => music.cycleRepeat()}
              aria-label="Repeat: {music.repeat}"
            >
              <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
                <path fill="none" stroke="currentColor" stroke-width="2" d="M17 2l3 3-3 3M4 11V9a4 4 0 0 1 4-4h12M7 22l-3-3 3-3M20 13v2a4 4 0 0 1-4 4H4" />
              </svg>
              {#if music.repeat === 'one'}<i class="one">1</i>{/if}
            </button>
          </div>

          <div class="dl-bar">
            {#if cur.hasFlac !== false}
              <button
                type="button"
                class="dl-pill"
                onclick={() => void music.downloadTrack(cur, 'flac')}
              >
                ⬇ Download FLAC
              </button>
            {/if}
            <button
              type="button"
              class="dl-pill"
              onclick={() => void music.downloadTrack(cur, '320kbps')}
            >
              ⬇ Download 320kbps
            </button>
          </div>
        </section>

        {#if showQueue}
          <section class="queue" aria-label="Up next queue">
            <h3>Up next <small>drag or Alt+↑/↓ to reorder</small></h3>
            <ol>
              {#each music.tracks as t, i (`${t.id}-${i}`)}
                <li
                  class:current={i === music.index}
                  class:over={dragFrom !== null && dragFrom !== i}
                  draggable="true"
                  ondragstart={() => (dragFrom = i)}
                  ondragover={(e) => e.preventDefault()}
                  ondrop={() => drop(i)}
                  ondragend={() => (dragFrom = null)}
                >
                  <button
                    type="button"
                    class="row"
                    onclick={() => music.playIndex(i)}
                    onkeydown={(e) => itemKey(e, i)}
                    aria-current={i === music.index ? 'true' : undefined}
                  >
                    {#if t.art}
                      <img referrerpolicy="no-referrer" src={t.art} alt="" width="40" height="40" loading="lazy" decoding="async" />
                    {:else}
                      <span class="q-ph" aria-hidden="true">♪</span>
                    {/if}
                    <span>
                      <b>{t.title}</b>
                      <small>{t.artist} · {t.durationLabel || fmtDur(t.durationSec)}</small>
                    </span>
                  </button>
                  <span class="mv">
                    <button type="button" disabled={i === 0} onclick={() => music.move(i, i - 1)} aria-label="Move {t.title} up">▲</button>
                    <button type="button" disabled={i === music.tracks.length - 1} onclick={() => music.move(i, i + 1)} aria-label="Move {t.title} down">▼</button>
                    <button type="button" onclick={() => music.remove(i)} aria-label="Remove {t.title} from queue">✕</button>
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
    color: #f6f6f8;
    background: #09090b;
    overflow: hidden;
  }
  .np[open] {
    animation: rise calc(var(--dur-long, 360ms) * var(--dur-scale, 1)) var(--ease-emphasized, ease);
  }
  @keyframes rise {
    from {
      transform: translateY(36px);
      opacity: 0;
    }
  }
  .np::backdrop {
    background: #09090b;
  }
  .bg {
    position: absolute;
    inset: -60px;
    background-size: cover;
    background-position: center;
    filter: blur(58px) saturate(1.45);
    opacity: 0.62;
  }
  :global([data-tier='low']) .bg {
    filter: none;
    opacity: 0.16;
  }
  .tint {
    position: absolute;
    inset: 0;
    background:
      radial-gradient(80% 50% at 50% 18%, color-mix(in srgb, var(--c) 60%, transparent), transparent 72%),
      linear-gradient(180deg, rgb(9 9 11 / 0.42), rgb(9 9 11 / 0.92) 78%);
  }
  .wrap {
    position: relative;
    height: 100%;
    display: flex;
    flex-direction: column;
    gap: 10px;
    padding: calc(10px + var(--safe-t, 0px)) 18px calc(18px + var(--safe-b, 0px));
    box-sizing: border-box;
  }
  .topbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  }
  .from {
    flex: 1;
    min-width: 0;
    display: grid;
    justify-items: center;
    text-align: center;
  }
  .kicker {
    font-size: 0.66rem;
    font-weight: 800;
    letter-spacing: 0.12em;
    color: rgb(255 255 255 / 0.64);
  }
  .from b {
    max-width: 100%;
    font-size: 0.84rem;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .top-actions {
    display: flex;
    align-items: center;
    gap: 2px;
  }
  .mode-strip {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: center;
    gap: 10px;
  }
  .seg {
    display: inline-flex;
    padding: 3px;
    border-radius: 999px;
    background: rgb(255 255 255 / 0.08);
    box-shadow: inset 0 0 0 1px rgb(255 255 255 / 0.14);
  }
  .seg button {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 5px 14px;
    border: 0;
    border-radius: 999px;
    background: none;
    color: rgb(255 255 255 / 0.72);
    font-size: 0.78rem;
    font-weight: 700;
    cursor: pointer;
    transition: background 160ms ease, color 160ms ease;
  }
  .seg button:disabled {
    opacity: 0.35;
    cursor: not-allowed;
  }
  .seg button.active {
    background: #e11d2e;
    color: #fff;
    box-shadow: 0 4px 14px -4px rgb(225 29 46 / 0.65);
  }
  .dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: currentColor;
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
      align-items: center;
    }
  }
  .now {
    display: grid;
    align-content: center;
    justify-items: center;
    gap: 16px;
    max-width: 460px;
    width: 100%;
    margin: 0 auto;
  }
  .art {
    width: min(76vw, 330px, 38dvh);
    height: auto;
    aspect-ratio: 1;
    border-radius: 22px;
    object-fit: cover;
    box-shadow:
      0 30px 60px -20px rgb(0 0 0 / 0.85),
      0 0 80px -28px var(--a),
      inset 0 0 0 1px rgb(255 255 255 / 0.16);
    animation: pop 0.36s var(--ease-emphasized, ease);
  }
  .art-fallback {
    display: grid;
    place-items: center;
    font-size: 4rem;
    color: rgb(255 255 255 / 0.45);
    background: rgb(255 255 255 / 0.06);
  }
  @keyframes pop {
    from {
      transform: scale(0.95);
      opacity: 0.45;
    }
  }
  .lyrics-panel {
    width: 100%;
    height: min(42dvh, 330px);
    min-height: 220px;
    border-radius: 22px;
    padding: 20px 16px;
    overflow-y: auto;
    overscroll-behavior: contain;
    background: rgb(14 14 18 / 0.72);
    box-shadow:
      inset 0 1px 0 rgb(255 255 255 / 0.14),
      inset 0 0 0 1px rgb(255 255 255 / 0.1);
    scrollbar-width: thin;
  }
  .lyr-list {
    display: grid;
    gap: 6px;
    padding: 64px 0;
  }
  .lyr-line {
    width: 100%;
    padding: 8px 10px;
    border: 0;
    border-radius: 12px;
    background: none;
    color: rgb(255 255 255 / 0.46);
    font-size: 0.98rem;
    font-weight: 600;
    line-height: 1.42;
    text-align: center;
    cursor: pointer;
    transition: color 200ms ease, transform 200ms ease, background 200ms ease;
  }
  .lyr-line:hover {
    color: rgb(255 255 255 / 0.85);
    background: rgb(255 255 255 / 0.05);
  }
  .lyr-line.past {
    color: rgb(255 255 255 / 0.36);
  }
  .lyr-line.current {
    color: #fff;
    font-size: 1.16rem;
    font-weight: 800;
    transform: scale(1.03);
    background: color-mix(in srgb, #e11d2e 20%, transparent);
    box-shadow: inset 0 0 0 1px color-mix(in srgb, #e11d2e 45%, transparent);
  }
  .lyr-plain {
    display: grid;
    gap: 10px;
    text-align: center;
    padding: 12px 6px;
    color: rgb(255 255 255 / 0.88);
    line-height: 1.55;
  }
  .lyr-plain p {
    margin: 0;
  }
  .lyr-empty {
    height: 100%;
    display: grid;
    place-items: center;
    color: rgb(255 255 255 / 0.56);
    text-align: center;
  }
  .lyr-skeleton {
    height: 100%;
    display: grid;
    align-content: center;
    justify-items: center;
    gap: 14px;
  }
  .sk-line {
    display: block;
    height: 16px;
    border-radius: 8px;
    background: linear-gradient(100deg, rgb(255 255 255 / 0.08) 30%, rgb(255 255 255 / 0.18) 50%, rgb(255 255 255 / 0.08) 70%);
    background-size: 300% 100%;
    animation: lyr-shimmer 1.3s linear infinite;
  }
  .w70 { width: 70%; }
  .w90 { width: 88%; }
  .w55 { width: 55%; }
  .w80 { width: 78%; }
  .w60 { width: 62%; }
  @keyframes lyr-shimmer {
    from { background-position: 100% 0; }
    to { background-position: -50% 0; }
  }
  .meta-row {
    width: 100%;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  }
  .meta {
    min-width: 0;
    flex: 1;
  }
  h2 {
    margin: 0;
    font-size: clamp(1.2rem, 3.6vw, 1.5rem);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .meta p {
    margin: 3px 0 0;
    color: rgb(255 255 255 / 0.74);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .q-pill {
    flex: none;
    padding: 4px 10px;
    border-radius: 999px;
    font-size: 0.66rem;
    font-weight: 800;
    letter-spacing: 0.06em;
    color: #fff;
    background: color-mix(in srgb, #e11d2e 28%, rgb(255 255 255 / 0.08));
    box-shadow: inset 0 0 0 1px color-mix(in srgb, #e11d2e 65%, transparent);
  }
  .err-banner {
    width: 100%;
    margin: 0;
    padding: 8px 12px;
    border-radius: 12px;
    font-size: 0.82rem;
    text-align: center;
    background: rgb(255 59 48 / 0.18);
    box-shadow: inset 0 0 0 1px rgb(255 59 48 / 0.42);
  }
  .seek {
    width: 100%;
  }
  input[type='range'] {
    width: 100%;
    appearance: none;
    height: 5px;
    border-radius: 5px;
    background: linear-gradient(90deg, #e11d2e var(--p), rgb(255 255 255 / 0.22) var(--p));
    outline-offset: 6px;
    cursor: pointer;
  }
  input[type='range']::-webkit-slider-thumb {
    appearance: none;
    width: 15px;
    height: 15px;
    border-radius: 50%;
    background: #fff;
    box-shadow: 0 2px 8px rgb(0 0 0 / 0.5);
  }
  input[type='range']::-moz-range-thumb {
    width: 15px;
    height: 15px;
    border: 0;
    border-radius: 50%;
    background: #fff;
  }
  .times {
    display: flex;
    justify-content: space-between;
    font-size: 0.73rem;
    color: rgb(255 255 255 / 0.72);
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
    width: 44px;
    height: 44px;
    display: grid;
    place-items: center;
    border: 0;
    border-radius: 50%;
    background: none;
    color: inherit;
    cursor: pointer;
  }
  .ic:hover {
    background: rgb(255 255 255 / 0.08);
  }
  .ic.sm {
    opacity: 0.68;
  }
  .ic.on {
    opacity: 1;
    color: #ff4d5a;
  }
  .ic.done {
    color: #22c55e;
  }
  .one {
    position: absolute;
    bottom: 7px;
    right: 7px;
    font-size: 0.6rem;
    font-style: normal;
    font-weight: 800;
  }
  .play {
    width: 68px;
    height: 68px;
    border-radius: 50%;
    border: 0;
    display: grid;
    place-items: center;
    background: linear-gradient(135deg, #e11d2e, #b80f1e);
    color: #fff;
    cursor: pointer;
    box-shadow: 0 12px 32px -8px rgb(225 29 46 / 0.75);
  }
  .dl-bar {
    display: flex;
    flex-wrap: wrap;
    justify-content: center;
    gap: 8px;
    width: 100%;
  }
  .dl-pill {
    padding: 7px 14px;
    border-radius: 999px;
    border: 0;
    background: rgb(255 255 255 / 0.09);
    box-shadow: inset 0 0 0 1px rgb(255 255 255 / 0.16);
    color: #f4f4f6;
    font-size: 0.76rem;
    font-weight: 700;
    cursor: pointer;
    transition: background 150ms ease;
  }
  .dl-pill:hover {
    background: rgb(255 255 255 / 0.16);
  }
  .spin {
    width: 18px;
    height: 18px;
    border-radius: 50%;
    border: 2px solid rgb(255 255 255 / 0.25);
    border-top-color: #fff;
    animation: spin 0.75s linear infinite;
  }
  .spin.lg {
    width: 26px;
    height: 26px;
    border-width: 2.6px;
  }
  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }
  .queue {
    background: rgb(14 14 18 / 0.68);
    box-shadow: inset 0 0 0 1px rgb(255 255 255 / 0.1);
    border-radius: 22px;
    padding: 14px;
    min-height: 0;
    max-height: 62dvh;
    overflow-y: auto;
  }
  .queue h3 {
    margin: 0 0 10px;
    font-size: 0.98rem;
  }
  .queue h3 small {
    font-weight: 500;
    font-size: 0.72rem;
    color: rgb(255 255 255 / 0.6);
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
    background: color-mix(in srgb, #e11d2e 24%, transparent);
  }
  li.over:hover {
    box-shadow: inset 0 2px 0 #e11d2e;
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
  .row img,
  .q-ph {
    width: 40px;
    height: 40px;
    border-radius: 8px;
    object-fit: cover;
    flex: none;
    background: rgb(255 255 255 / 0.08);
  }
  .q-ph {
    display: grid;
    place-items: center;
    color: rgb(255 255 255 / 0.5);
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
    color: rgb(255 255 255 / 0.68);
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
    opacity: 0.72;
    cursor: pointer;
    font-size: 0.72rem;
  }
  .mv button:disabled {
    opacity: 0.22;
  }
</style>
