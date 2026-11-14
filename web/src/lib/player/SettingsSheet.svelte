<script lang="ts">
  import { engine } from './engine';
  import { clock, setSleep, sleep } from './controller';
  import { SPEEDS } from './keys';
  import { OFFSET_LIMIT, OFFSET_STEP } from './subs';
  import type { PlayerUI } from './ui.svelte';
  import type { Sub } from '$lib/data/types';

  let { ui, subs }: { ui: PlayerUI; subs: Sub[] } = $props();
  type Pane = 'main' | 'quality' | 'speed' | 'audio' | 'subs' | 'style' | 'sleep';
  let pane = $state<Pane>('main');
  let fileInput: HTMLInputElement | undefined = $state();
  const tracks = engine.tracks;

  const SLEEP_OPTS: (number | 'end' | null)[] = [null, 15, 30, 60, 'end'];
  const COLORS = ['#ffffff', '#ffe14d', '#00e5ff', '#7CFC00', '#ff5c5c'];
  const choices = $derived.by(() => {
    void $tracks;
    void ui.extSubs;
    return ui.choices(subs);
  });
  const own = $derived(ui.subId.startsWith('side:') || ui.subId.startsWith('ext:'));
  const qLabel = $derived($tracks.auto ? `Auto${$tracks.activeHeight ? ` (${$tracks.activeHeight}p)` : ''}` : `${$tracks.activeHeight ?? '—'}p`);
  const subLabel = $derived(ui.subId === 'off' ? 'Off' : (choices.find((c) => c.id === ui.subId)?.label ?? 'On'));
  const sleepLabel = $derived($sleep.mode === null ? 'Off' : $sleep.mode === 'end' ? 'End of episode' : `${$sleep.mode} min`);
  const audioLabel = $derived($tracks.audios.find((a) => a.language === $tracks.audio)?.label ?? 'Default');

  function close() {
    ui.settings = false;
    pane = 'main';
  }
  const ms = (s: number) => `${s > 0 ? '+' : ''}${s.toFixed(2)} s`;
</script>

<div class="scrim" role="presentation" onclick={close} data-no-gesture></div>
<div class="sheet glass" role="dialog" aria-label="Player settings" data-no-gesture>
  {#if pane === 'main'}
    <ul class="list">
      <li><button onclick={() => (pane = 'quality')}><span>Quality</span><em>{qLabel}</em></button></li>
      <li><button onclick={() => (pane = 'speed')}><span>Speed</span><em>{$clock.rate}×</em></button></li>
      <li><button onclick={() => (pane = 'audio')}><span>Audio</span><em>{audioLabel}</em></button></li>
      <li><button onclick={() => (pane = 'subs')}><span>Subtitles</span><em>{subLabel}</em></button></li>
      <li><button onclick={() => (pane = 'style')}><span>Subtitle style &amp; sync</span><em>{ms(ui.subOffset)}</em></button></li>
      <li><button onclick={() => (pane = 'sleep')}><span>Sleep timer</span><em>{sleepLabel}</em></button></li>
    </ul>
  {:else}
    <button class="back" onclick={() => (pane = 'main')}>‹ Back</button>
    {#if pane === 'quality'}
      <ul class="list">
        <li><button aria-pressed={$tracks.auto} onclick={() => engine.selectQuality(null)}><span>Auto</span>{#if $tracks.auto && $tracks.activeHeight}<em>{$tracks.activeHeight}p</em>{/if}</button></li>
        {#each $tracks.qualities as q (q.height)}
          <li>
            <button aria-pressed={!$tracks.auto && $tracks.activeHeight === q.height} onclick={() => engine.selectQuality(q.height)}>
              <span>{q.height}p</span><em>{(q.bandwidth / 1e6).toFixed(1)} Mbps</em>
            </button>
          </li>
        {/each}
        {#if !$tracks.qualities.length}<li class="muted">Single quality source</li>{/if}
      </ul>
    {:else if pane === 'speed'}
      <div class="grid">
        {#each SPEEDS as s (s)}
          <button class="chip" aria-pressed={$clock.rate === s} onclick={() => ui.setRate(s)}>{s}×</button>
        {/each}
      </div>
    {:else if pane === 'audio'}
      <ul class="list">
        {#each $tracks.audios as a (a.language)}
          <li><button aria-pressed={$tracks.audio === a.language} onclick={() => engine.selectAudio(a.language)}><span>{a.label}</span><em>{a.language}</em></button></li>
        {:else}
          <li class="muted">Only one audio track</li>
        {/each}
      </ul>
    {:else if pane === 'subs'}
      <ul class="list">
        <li><button aria-pressed={ui.subId === 'off'} onclick={() => ui.selectSub('off', subs)}><span>Off</span></button></li>
        {#each choices as c (c.id)}
          <li><button aria-pressed={ui.subId === c.id} onclick={() => ui.selectSub(c.id, subs)}><span>{c.label}</span><em>{c.kind === 'ext' ? 'file' : c.kind === 'emb' ? 'stream' : ''}</em></button></li>
        {/each}
        <li>
          <button onclick={() => fileInput?.click()}><span>Load subtitle file…</span><em>.srt / .vtt</em></button>
          <input
            bind:this={fileInput}
            type="file"
            accept=".srt,.vtt,text/vtt,application/x-subrip"
            hidden
            onchange={(e) => {
              const f = (e.currentTarget as HTMLInputElement).files?.[0];
              if (f) ui.loadFile(f, subs);
              (e.currentTarget as HTMLInputElement).value = '';
            }}
          />
        </li>
      </ul>
      {#if ui.subError}<p class="err" role="alert">{ui.subError}</p>{/if}
    {:else if pane === 'style'}
      <div class="form">
        <label>Size <output>{ui.subStyle.size}%</output>
          <input type="range" min="50" max="200" step="10" value={ui.subStyle.size} oninput={(e) => ui.setStyle({ size: +e.currentTarget.value })} />
        </label>
        <div class="row" role="group" aria-label="Text color">
          {#each COLORS as c (c)}
            <button class="swatch" style:background={c} aria-pressed={ui.subStyle.color === c} aria-label="Color {c}" onclick={() => ui.setStyle({ color: c })}></button>
          {/each}
        </div>
        <label>Background <output>{Math.round(ui.subStyle.bg * 100)}%</output>
          <input type="range" min="0" max="1" step="0.05" value={ui.subStyle.bg} oninput={(e) => ui.setStyle({ bg: +e.currentTarget.value })} />
        </label>
        <label>Sync offset <output>{ms(ui.subOffset)}</output>
          <input type="range" min={-OFFSET_LIMIT} max={OFFSET_LIMIT} step={OFFSET_STEP} value={ui.subOffset} disabled={!own} oninput={(e) => ui.setOffset(+e.currentTarget.value)} />
        </label>
        <div class="row">
          <button class="chip" disabled={!own} onclick={() => ui.setOffset(ui.subOffset - OFFSET_STEP)}>−50 ms</button>
          <button class="chip" disabled={!own} onclick={() => ui.setOffset(ui.subOffset + OFFSET_STEP)}>+50 ms</button>
          <button class="chip" disabled={!own} onclick={() => ui.setOffset(0)}>Reset</button>
        </div>
        {#if !own}<p class="muted">Sync works for loaded subtitle files and catalog subtitles (not in-stream tracks).</p>{/if}
      </div>
    {:else if pane === 'sleep'}
      <ul class="list">
        {#each SLEEP_OPTS as m (m)}
          <li>
            <button aria-pressed={$sleep.mode === m} onclick={() => setSleep(m)}>
              <span>{m === null ? 'Off' : m === 'end' ? 'End of episode' : `${m} minutes`}</span>
            </button>
          </li>
        {/each}
      </ul>
    {/if}
  {/if}
</div>

<style>
  .scrim {
    position: absolute;
    inset: 0;
    z-index: 8;
    background: rgb(0 0 0 / 0.35);
  }
  .sheet {
    position: absolute;
    z-index: 9;
    right: 12px;
    bottom: 12px;
    width: min(360px, calc(100% - 24px));
    max-height: calc(100% - 24px);
    overflow: auto;
    padding: 8px;
    border-radius: var(--radius-lg);
    background: rgb(12 12 14 / 0.86);
  }
  .list {
    list-style: none;
    margin: 0;
    padding: 0;
  }
  .list button,
  .back {
    width: 100%;
    display: flex;
    justify-content: space-between;
    gap: 12px;
    align-items: center;
    min-height: 44px;
    padding: 8px 12px;
    border: 0;
    border-radius: 12px;
    background: none;
    text-align: left;
    cursor: pointer;
  }
  .list button:hover,
  .back:hover {
    background: rgb(255 255 255 / 0.08);
  }
  .list button[aria-pressed='true'] span::before {
    content: '✓ ';
    color: var(--accent);
  }
  em {
    font-style: normal;
    color: var(--text-secondary);
    font-size: 0.85rem;
  }
  .back {
    font-weight: 700;
    color: var(--text-secondary);
  }
  .grid {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: 8px;
    padding: 8px;
  }
  .form {
    display: grid;
    gap: 12px;
    padding: 8px 12px 12px;
  }
  label {
    display: grid;
    gap: 4px;
    font-size: 0.9rem;
  }
  label output {
    justify-self: end;
    margin-top: -1.4em;
    color: var(--text-secondary);
  }
  input[type='range'] {
    width: 100%;
    accent-color: var(--accent);
  }
  .row {
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
  }
  .swatch {
    width: 30px;
    height: 30px;
    border-radius: 50%;
    border: 2px solid rgb(255 255 255 / 0.25);
    cursor: pointer;
  }
  .swatch[aria-pressed='true'] {
    border-color: var(--accent);
    box-shadow: 0 0 0 2px rgb(0 0 0), 0 0 0 4px var(--accent);
  }
  .chip[disabled] {
    opacity: 0.45;
  }
  .muted {
    color: var(--text-secondary);
    font-size: 0.8rem;
    padding: 8px 12px;
    margin: 0;
  }
  .err {
    color: #ff7b7b;
    padding: 4px 12px;
    margin: 0;
    font-size: 0.85rem;
  }
</style>
