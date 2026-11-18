<script lang="ts">
  import { engine } from './engine';
  import { clock, searchOnlineSubtitlesForMedia, setSleep, sleep, switchMediaDub } from './controller';
  import { SPEEDS } from './keys';
  import type { Media } from './sources';
  import {
    OFFSET_LIMIT,
    OFFSET_STEP,
    POPULAR_SUBTITLE_LANGUAGES,
    audioDubOfSource,
    formatTrackLanguage,
    fullLanguageName
  } from './subs';
  import type { SettingsPane, SiblingDubRef } from './types';
  import type { PlayerUI } from './ui.svelte';
  import type { Item, Sub } from '$lib/data/types';

  let { ui, subs, media }: { ui: PlayerUI; subs: Sub[]; media?: Media } = $props();
  let pane = $state<SettingsPane>(ui.settingsPane);
  let fileInput: HTMLInputElement | undefined = $state();
  const tracks = engine.tracks;

  $effect(() => {
    pane = ui.settingsPane;
  });

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

  interface DubEntry {
    language: string;
    fromVariants: boolean;
    sibling?: SiblingDubRef;
  }

  const dubEntries = $derived.by((): DubEntry[] => {
    const map = new Map<string, DubEntry>();
    const variants = media?.variants ?? (media?.source ? [media.source] : []);
    for (const v of variants) {
      const lang = audioDubOfSource(v);
      if (!lang) continue;
      const key = lang.toLowerCase();
      if (!map.has(key)) map.set(key, { language: lang, fromVariants: true });
    }
    for (const a of $tracks.audios) {
      if (!a.language.startsWith('dub:')) continue;
      const lang = fullLanguageName(a.label || a.language.slice(4));
      const key = lang.toLowerCase();
      if (!map.has(key)) map.set(key, { language: lang, fromVariants: true });
    }
    const itemWithDubs = media?.item as (Item & { dubs?: SiblingDubRef[] }) | undefined;
    for (const d of itemWithDubs?.dubs ?? []) {
      const lang = fullLanguageName(d.languageName || d.languageCode);
      const key = lang.toLowerCase();
      const prev = map.get(key);
      if (!prev) map.set(key, { language: lang, fromVariants: false, sibling: d });
      else if (!prev.sibling) map.set(key, { ...prev, sibling: d });
    }
    const priority = ['hindi', 'original', 'english', 'bangla', 'dual audio'];
    return [...map.values()].sort((a, b) => {
      const ia = priority.indexOf(a.language.toLowerCase());
      const ib = priority.indexOf(b.language.toLowerCase());
      const pa = ia >= 0 ? ia : priority.length;
      const pb = ib >= 0 ? ib : priority.length;
      return pa !== pb ? pa - pb : a.language.localeCompare(b.language);
    });
  });

  const streamAudioTracks = $derived($tracks.audios.filter((a) => !a.language.startsWith('dub:')));
  const activeDubName = $derived.by(() => {
    const fromCur = audioDubOfSource(media?.source);
    if (fromCur) return fromCur;
    if ($tracks.audio?.startsWith('dub:')) return fullLanguageName($tracks.audio.slice(4));
    return null;
  });

  const audioLabel = $derived.by(() => {
    if (activeDubName) return activeDubName;
    const tr = streamAudioTracks.find((a) => a.language === $tracks.audio);
    if (tr) return formatTrackLanguage(tr.label, tr.language, 'Original');
    return dubEntries[0]?.language ?? 'Original';
  });

  // Online subtitle search state
  let onlineQuery = $state('');
  let onlineLang = $state<string | null>(null);
  let onlineResults = $state.raw<Sub[]>([]);
  let onlineLoading = $state(false);
  let onlineSearched = $state(false);
  let onlineErr = $state<string | null>(null);
  let seededKey = '';

  $effect(() => {
    const k = media?.key ?? '';
    if (k !== seededKey) {
      seededKey = k;
      onlineQuery = media?.title ?? '';
      onlineResults = [];
      onlineSearched = false;
      onlineErr = null;
    }
  });

  async function runOnlineSearch(queryOverride = onlineQuery, langOverride = onlineLang) {
    const q = (queryOverride || media?.title || '').trim();
    if (!q) return;
    onlineLoading = true;
    onlineSearched = true;
    onlineErr = null;
    try {
      onlineResults = await searchOnlineSubtitlesForMedia({ query: q, media, lang: langOverride });
    } catch {
      onlineResults = [];
      onlineErr = "Couldn't search subtitles right now.";
    } finally {
      onlineLoading = false;
    }
  }

  $effect(() => {
    if (pane === 'subs' && !onlineSearched && (media?.title ?? '').trim()) {
      void runOnlineSearch(media?.title ?? '', onlineLang);
    }
  });

  function close() {
    ui.settings = false;
    ui.settingsPane = 'main';
    pane = 'main';
  }

  function backOrClose() {
    if (ui.settingsPane === 'main') pane = 'main';
    else close();
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
    <div class="sheet-head">
      <button class="back" onclick={backOrClose}>‹ {ui.settingsPane === 'main' ? 'Back' : 'Done'}</button>
      <button class="close-x" onclick={close} aria-label="Close settings">✕</button>
    </div>
    {#if pane === 'quality'}
      <ul class="list">
        <li>
          <button aria-pressed={$tracks.auto} onclick={() => { engine.selectQuality(null); close(); }}>
            <span>Auto</span>{#if $tracks.auto && $tracks.activeHeight}<em>{$tracks.activeHeight}p</em>{/if}
          </button>
        </li>
        {#each $tracks.qualities as q (q.height)}
          <li>
            <button aria-pressed={!$tracks.auto && $tracks.activeHeight === q.height} onclick={() => { engine.selectQuality(q.height); close(); }}>
              <span>{q.height}p</span>{#if q.bandwidth > 0}<em>{(q.bandwidth / 1e6).toFixed(1)} Mbps</em>{/if}
            </button>
          </li>
        {/each}
        {#if !$tracks.qualities.length}<li class="muted">Single quality source</li>{/if}
      </ul>
    {:else if pane === 'speed'}
      <div class="grid">
        {#each SPEEDS as s (s)}
          <button class="chip" aria-pressed={$clock.rate === s} onclick={() => { ui.setRate(s); close(); }}>{s}×</button>
        {/each}
      </div>
    {:else if pane === 'audio'}
      <ul class="list">
        {#if dubEntries.length > 0}
          <li class="sec">Audio / Dub language</li>
          {#each dubEntries as entry (entry.language)}
            {@const isSel = activeDubName ? activeDubName.toLowerCase() === entry.language.toLowerCase() : dubEntries.length === 1}
            <li>
              <button
                aria-pressed={isSel}
                onclick={() => {
                  if (media) void switchMediaDub(media, entry.language, entry.sibling);
                  else engine.selectAudio(`dub:${entry.language}`);
                  close();
                }}
              >
                <span>{entry.language}</span>
                <em>{entry.language === 'Original' ? 'Original' : 'Dub'}</em>
              </button>
            </li>
          {/each}
        {/if}
        {#if streamAudioTracks.length > 0}
          {#if dubEntries.length > 0}<li class="sec">Stream audio tracks</li>{/if}
          {#each streamAudioTracks as a (a.language)}
            <li>
              <button
                aria-pressed={$tracks.audio === a.language}
                onclick={() => {
                  engine.selectAudio(a.language);
                  close();
                }}
              >
                <span>{formatTrackLanguage(a.label, a.language, 'Original')}</span>
                <em>Track</em>
              </button>
            </li>
          {/each}
        {/if}
        {#if dubEntries.length === 0 && streamAudioTracks.length === 0}
          <li>
            <button aria-pressed={true} onclick={close}>
              <span>{activeDubName ?? 'Original'}</span>
              <em>Original</em>
            </button>
          </li>
        {/if}
      </ul>
    {:else if pane === 'subs'}
      <ul class="list">
        <li>
          <button
            aria-pressed={ui.subId === 'off'}
            onclick={() => {
              void ui.selectSub('off', subs);
              close();
            }}
          >
            <span>Off</span>
          </button>
        </li>
        {#each choices as c (c.id)}
          <li>
            <button
              aria-pressed={ui.subId === c.id}
              onclick={() => {
                void ui.selectSub(c.id, subs);
                close();
              }}
            >
              <span>{c.label}</span>
              <em>{c.kind === 'ext' ? 'Loaded' : c.kind === 'emb' ? 'Stream' : ''}</em>
            </button>
          </li>
        {/each}
      </ul>

      <div class="online-box">
        <span class="sec">Search online subtitles</span>
        <form
          class="search-row"
          onsubmit={(e) => {
            e.preventDefault();
            void runOnlineSearch(onlineQuery, onlineLang);
          }}
        >
          <input
            type="search"
            class="sub-input"
            placeholder="Movie or series title…"
            value={onlineQuery}
            oninput={(e) => {
              if (e.currentTarget instanceof HTMLInputElement) onlineQuery = e.currentTarget.value;
            }}
            aria-label="Search online subtitles"
          />
          <button type="submit" class="search-btn" disabled={onlineLoading}>
            {onlineLoading ? '…' : 'Search'}
          </button>
        </form>
        <div class="lang-chips" role="group" aria-label="Popular subtitle languages">
          <button
            type="button"
            class="lang-chip"
            aria-pressed={onlineLang === null}
            onclick={() => {
              onlineLang = null;
              void runOnlineSearch(onlineQuery, null);
            }}
          >
            All
          </button>
          {#each POPULAR_SUBTITLE_LANGUAGES as l (l.code)}
            <button
              type="button"
              class="lang-chip"
              aria-pressed={onlineLang === l.code}
              onclick={() => {
                const next = onlineLang === l.code ? null : l.code;
                onlineLang = next;
                void runOnlineSearch(onlineQuery, next);
              }}
            >
              {l.name}
            </button>
          {/each}
        </div>

        {#if onlineErr}
          <p class="err" role="alert">{onlineErr}</p>
        {:else if !onlineLoading && onlineSearched && onlineResults.length === 0}
          <p class="muted">No matching online subtitles found.</p>
        {/if}

        {#if onlineResults.length > 0}
          <ul class="list online-list">
            {#each onlineResults as hit, idx (`${hit.url}:${idx}`)}
              <li>
                <button
                  aria-pressed={ui.subId === `ext:${hit.url}`}
                  onclick={() => {
                    void ui.loadRemoteSub(hit, subs);
                    close();
                  }}
                >
                  <span>{formatTrackLanguage(hit.label, hit.lang, 'Original')}</span>
                  <em>Online</em>
                </button>
              </li>
            {/each}
          </ul>
        {/if}
      </div>

      <ul class="list">
        <li>
          <button onclick={() => fileInput?.click()}><span>Load subtitle file…</span><em>.srt / .vtt</em></button>
          <input
            bind:this={fileInput}
            type="file"
            accept=".srt,.vtt,text/vtt,application/x-subrip"
            hidden
            onchange={(e) => {
              if (!(e.currentTarget instanceof HTMLInputElement)) return;
              const f = e.currentTarget.files?.[0];
              if (f) void ui.loadFile(f, subs);
              e.currentTarget.value = '';
            }}
          />
        </li>
        <li>
          <button onclick={() => (pane = 'style')}><span>Subtitle style &amp; sync</span><em>{ms(ui.subOffset)}</em></button>
        </li>
      </ul>
      {#if ui.subError}<p class="err" role="alert">{ui.subError}</p>{/if}
    {:else if pane === 'style'}
      <div class="form">
        <label>Size <output>{ui.subStyle.size}%</output>
          <input
            type="range"
            min="50"
            max="200"
            step="10"
            value={ui.subStyle.size}
            oninput={(e) => {
              if (e.currentTarget instanceof HTMLInputElement) ui.setStyle({ size: +e.currentTarget.value });
            }}
          />
        </label>
        <div class="row" role="group" aria-label="Text color">
          {#each COLORS as c (c)}
            <button class="swatch" style:background={c} aria-pressed={ui.subStyle.color === c} aria-label="Color {c}" onclick={() => ui.setStyle({ color: c })}></button>
          {/each}
        </div>
        <label>Background <output>{Math.round(ui.subStyle.bg * 100)}%</output>
          <input
            type="range"
            min="0"
            max="1"
            step="0.05"
            value={ui.subStyle.bg}
            oninput={(e) => {
              if (e.currentTarget instanceof HTMLInputElement) ui.setStyle({ bg: +e.currentTarget.value });
            }}
          />
        </label>
        <label>Sync offset <output>{ms(ui.subOffset)}</output>
          <input
            type="range"
            min={-OFFSET_LIMIT}
            max={OFFSET_LIMIT}
            step={OFFSET_STEP}
            value={ui.subOffset}
            disabled={!own}
            oninput={(e) => {
              if (e.currentTarget instanceof HTMLInputElement) ui.setOffset(+e.currentTarget.value);
            }}
          />
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
            <button aria-pressed={$sleep.mode === m} onclick={() => { setSleep(m); close(); }}>
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
    background: rgb(0 0 0 / 0.38);
  }
  .sheet {
    position: absolute;
    z-index: 9;
    right: 12px;
    bottom: 12px;
    width: min(380px, calc(100% - 24px));
    max-height: calc(100% - 24px);
    overflow: auto;
    padding: 8px;
    border-radius: var(--radius-lg, 16px);
    background: rgb(12 12 14 / 0.92);
    color: #fff;
  }
  .sheet-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
  }
  .close-x {
    width: 36px;
    height: 36px;
    display: grid;
    place-items: center;
    border: 0;
    border-radius: 50%;
    background: none;
    color: var(--text-secondary);
    cursor: pointer;
  }
  .close-x:hover {
    background: rgb(255 255 255 / 0.08);
    color: #fff;
  }
  .sec {
    display: block;
    font-size: 0.74rem;
    font-weight: 700;
    letter-spacing: 0.03em;
    color: var(--accent);
    padding: 8px 12px 4px;
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
    min-height: 42px;
    padding: 7px 12px;
    border: 0;
    border-radius: 12px;
    background: none;
    color: #fff;
    text-align: left;
    cursor: pointer;
  }
  .back {
    width: auto;
    font-weight: 700;
    color: var(--text-secondary);
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
    font-size: 0.82rem;
    flex-shrink: 0;
  }
  .online-box {
    display: grid;
    gap: 8px;
    padding: 4px 8px 8px;
    border-top: 1px solid rgb(255 255 255 / 0.08);
    border-bottom: 1px solid rgb(255 255 255 / 0.08);
    margin: 4px 0;
  }
  .online-box .sec {
    padding: 4px 4px 0;
  }
  .search-row {
    display: flex;
    gap: 6px;
  }
  .sub-input {
    flex: 1;
    min-width: 0;
    height: 36px;
    padding: 0 10px;
    border-radius: 10px;
    border: 1px solid rgb(255 255 255 / 0.16);
    background: rgb(255 255 255 / 0.06);
    color: #fff;
    font-size: 0.85rem;
  }
  .sub-input:focus {
    outline: none;
    border-color: var(--accent);
  }
  .search-btn {
    height: 36px;
    padding: 0 12px;
    border: 0;
    border-radius: 10px;
    background: var(--accent);
    color: #fff;
    font-weight: 700;
    font-size: 0.82rem;
    cursor: pointer;
  }
  .search-btn:disabled {
    opacity: 0.6;
  }
  .lang-chips {
    display: flex;
    gap: 6px;
    overflow-x: auto;
    padding-bottom: 2px;
    scrollbar-width: none;
  }
  .lang-chips::-webkit-scrollbar {
    display: none;
  }
  .lang-chip {
    flex-shrink: 0;
    height: 28px;
    padding: 0 10px;
    border-radius: 99px;
    border: 1px solid rgb(255 255 255 / 0.16);
    background: rgb(255 255 255 / 0.06);
    color: #fff;
    font-size: 0.76rem;
    font-weight: 600;
    cursor: pointer;
  }
  .lang-chip[aria-pressed='true'] {
    background: var(--accent);
    border-color: var(--accent);
  }
  .online-list {
    max-height: 150px;
    overflow-y: auto;
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
    padding: 6px 12px;
    margin: 0;
  }
  .err {
    color: #ff7b7b;
    padding: 4px 12px;
    margin: 0;
    font-size: 0.85rem;
  }
</style>
