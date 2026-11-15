<script lang="ts">
  import Sheet from '$lib/components/Sheet.svelte';
  import type { Media } from '$lib/player/sources';
  import { probeAdaptive, type AdaptiveProbe } from './adaptive';
  import { downloadKindOf, downloads } from './manager.svelte';
  import { estimateSize, formatBytes, formatDuration, MAX_CONNECTIONS } from './math';
  import { probe } from './progressive';
  import { isIOS } from './store';
  import type { TrackChoice } from './types';
  import { isLazySource } from '$lib/data/gateway-play';
  import { resolveMedia } from '$lib/player/controller';

  let { open = $bindable(false), media, onstarted }: { open?: boolean; media: Media; onstarted?: () => void } = $props();

  /** Gateway titles: placeholder → proxy MP4 variants (resolved when the sheet opens). */
  let resolved = $state.raw<Media | null>(null);
  let vi = $state(0);
  const variants = $derived(resolved?.variants ?? []);
  const cur = $derived<Media | null>(resolved ? (variants.length ? { ...resolved, source: variants[vi] ?? resolved.source } : resolved) : null);
  const kind = $derived(downloadKindOf(cur?.source ?? media.source));
  let loading = $state(true);
  let err = $state('');
  let info = $state.raw<AdaptiveProbe | null>(null);
  let fileSize = $state(0);
  let ranges = $state<boolean | null>(null);
  let qi = $state(0);
  let audio = $state<string | undefined>(undefined);
  let texts = $state<string[]>([]);
  let connections = $state(4);
  let busy = $state(false);
  const ios = typeof navigator !== 'undefined' && isIOS(navigator.userAgent, navigator.maxTouchPoints);

  $effect(() => {
    if (!open) return;
    const m0 = media;
    let alive = true;
    loading = true;
    err = '';
    resolved = null;
    connections = downloads.defaultConnections;
    void downloads.refreshQuota();
    (async () => {
      try {
        const m = isLazySource(m0.source) ? await resolveMedia(m0) : m0;
        if (!alive) return;
        if (m.noSource || !m.source) throw new Error('No source available');
        const vs = m.variants ?? [];
        vi = Math.max(0, vs.findIndex((x) => x.url === m.source?.url));
        resolved = m;
      } catch (e) {
        if (alive) {
          err = (e as Error).message || 'No source available';
          loading = false;
        }
      }
    })();
    return () => {
      alive = false;
    };
  });

  $effect(() => {
    const m = cur;
    if (!open || !m?.source) return;
    let alive = true;
    loading = true;
    err = '';
    info = null;
    fileSize = 0;
    ranges = null;
    (async () => {
      try {
        const src = m.source!;
        if (downloadKindOf(src) === 'adaptive') {
          const p = await probeAdaptive(src.url, src.kind === 'DASH' ? 'application/dash+xml' : 'application/x-mpegurl', m.durationSec ?? 0);
          if (!alive) return;
          if (p.live) throw new Error('Live streams cannot be downloaded');
          info = p;
          const i720 = p.qualities.findIndex((q) => q.height && q.height <= 720);
          qi = i720 >= 0 ? i720 : 0;
          audio = p.audios[0]?.language;
          texts = p.texts.map((t) => t.language);
        } else {
          const r = await probe(globalThis.fetch.bind(globalThis), src.url, undefined, src.headers);
          if (!alive) return;
          fileSize = r.total;
          ranges = r.ranges;
        }
      } catch (e) {
        if (alive) err = (e as Error).message || 'Could not read the source';
      } finally {
        if (alive) loading = false;
      }
    })();
    return () => {
      alive = false;
    };
  });

  const estimate = $derived(kind === 'adaptive' ? (info?.qualities[qi]?.estimatedBytes ?? 0) + texts.length * 60_000 : fileSize || estimateSize(1_500_000, media.durationSec ?? 0));
  const free = $derived(Math.max(0, downloads.quota.quota - downloads.quota.usage));
  const tooBig = $derived(downloads.quota.quota > 0 && estimate > free);

  async function start() {
    busy = true;
    try {
      const m = cur;
      if (!m?.source) throw new Error('No source available');
      const q = info?.qualities[qi];
      const choice: TrackChoice =
        kind === 'adaptive' && q
          ? { quality: q.label, height: q.height, bandwidth: q.bandwidth, codecs: q.codecs, audio, texts: [...texts] }
          : m.source.height
            ? { quality: m.source.label ?? `${m.source.height}p`, height: m.source.height, texts: [] }
            : { quality: `Original (${m.source.kind ?? 'file'})`, texts: [] };
      await downloads.start({ media: m, choice, estimatedBytes: estimate, connections, durationSec: info?.durationSec || m.durationSec, lazyUrl: isLazySource(media.source) ? media.source?.url : undefined });
      open = false;
      onstarted?.();
    } catch (e) {
      err = (e as Error).message;
    } finally {
      busy = false;
    }
  }

  function toggleText(lang: string) {
    texts = texts.includes(lang) ? texts.filter((t) => t !== lang) : [...texts, lang];
  }
</script>

<Sheet bind:open title="Download">
  <p class="what"><b>{media.title}</b>{#if media.subtitle}<span> · {media.subtitle}</span>{/if}</p>

  {#if loading}
    <div class="loading" aria-busy="true"><span class="spin"></span> Reading {kind === 'adaptive' ? 'stream tracks' : 'file size'}…</div>
  {:else if err && (!resolved || (!info && !fileSize && kind === 'adaptive'))}
    <p class="err" role="alert">{err}</p>
  {:else}
    {#if kind === 'adaptive' && info}
      <fieldset>
        <legend>Quality</legend>
        {#each info.qualities as q, i (q.label + q.bandwidth)}
          <label class="opt">
            <input type="radio" name="dlq" checked={qi === i} onchange={() => (qi = i)} />
            <span class="lbl">{q.label}</span>
            <span class="sub">{(q.bandwidth / 1e6).toFixed(1)} Mbps{q.codecs ? ` · ${q.codecs}` : ''}</span>
            <span class="size">≈ {formatBytes(q.estimatedBytes)}</span>
          </label>
        {/each}
      </fieldset>
      {#if info.audios.length > 1}
        <fieldset>
          <legend>Audio</legend>
          {#each info.audios as a (a.language)}
            <label class="opt"><input type="radio" name="dla" checked={audio === a.language} onchange={() => (audio = a.language)} /><span class="lbl">{a.label}</span></label>
          {/each}
        </fieldset>
      {/if}
      {#if info.texts.length}
        <fieldset>
          <legend>Subtitles</legend>
          {#each info.texts as t (t.language)}
            <label class="opt"><input type="checkbox" checked={texts.includes(t.language)} onchange={() => toggleText(t.language)} /><span class="lbl">{t.label}</span></label>
          {/each}
        </fieldset>
      {/if}
      <p class="note">Duration {formatDuration(info.durationSec)} · stored in the browser's offline database (IndexedDB).</p>
    {:else}
      {#if variants.length > 1}
        <fieldset>
          <legend>Quality</legend>
          {#each variants as v, i (v.url)}
            <label class="opt">
              <input type="radio" name="dlv" checked={vi === i} onchange={() => (vi = i)} />
              <span class="lbl">{v.label ?? `${v.height}p`}</span>
              <span class="sub">MP4</span>
            </label>
          {/each}
        </fieldset>
      {/if}
      <fieldset>
        <legend>File</legend>
        <div class="opt static">
          <span class="lbl">{cur?.source?.label && cur.source.height ? `${cur.source.label} · MP4` : `Original · ${cur?.source?.kind ?? media.source?.kind}`}</span>
          <span class="size">{fileSize ? formatBytes(fileSize) : `≈ ${formatBytes(estimate)} (size unknown)`}</span>
        </div>
      </fieldset>
      <fieldset>
        <legend>Connections <b>{ranges === false ? 1 : connections}</b></legend>
        <input class="range" type="range" min="1" max={MAX_CONNECTIONS} step="1" bind:value={connections} disabled={ranges === false} aria-label="Parallel connections" />
        <p class="note">
          {#if ranges === false}Server doesn't support byte ranges → single connection, resume restarts.{:else}Parallel HTTP Range requests into private file storage (OPFS); resumes after reload.{/if}
        </p>
      </fieldset>
      {#if err}<p class="err" role="alert">{err}</p>{/if}
    {/if}

    <div class="sum">
      <span>Estimated size <b>{formatBytes(estimate)}</b></span>
      {#if downloads.quota.quota}<span>Free <b>{formatBytes(free)}</b></span>{/if}
    </div>
    {#if tooBig}<p class="err">Not enough browser storage for this download.</p>{/if}
    {#if ios}<p class="ios">iOS: web storage is limited and may be cleared by the system. Add Cinetor to the Home Screen to keep downloads longer.</p>{/if}
    <button class="btn btn-primary go" onclick={start} disabled={busy || tooBig || (kind === 'adaptive' && !info)}>
      {busy ? 'Starting…' : 'Start download'}
    </button>
  {/if}
</Sheet>

<style>
  .what {
    margin: 0 0 10px;
    color: var(--text-secondary);
    font-size: 0.9rem;
  }
  .what b {
    color: var(--text-primary);
  }
  .loading {
    display: flex;
    gap: 10px;
    align-items: center;
    padding: 24px 4px;
    color: var(--text-secondary);
  }
  .spin {
    width: 18px;
    height: 18px;
    border-radius: 50%;
    border: 2px solid var(--glass-stroke);
    border-top-color: var(--accent);
    animation: spin 0.8s linear infinite;
  }
  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }
  fieldset {
    border: 0;
    margin: 0 0 12px;
    padding: 0;
    display: grid;
    gap: 6px;
  }
  legend {
    font-size: 0.75rem;
    font-weight: 800;
    letter-spacing: 0.1em;
    text-transform: uppercase;
    color: var(--text-tertiary);
    margin-bottom: 6px;
  }
  .opt {
    display: grid;
    grid-template-columns: auto 1fr auto;
    grid-template-areas: 'i l s' 'i u s';
    align-items: center;
    gap: 0 10px;
    padding: 10px 12px;
    border-radius: 14px;
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
    cursor: pointer;
  }
  .opt.static {
    grid-template-columns: 1fr auto;
    grid-template-areas: 'l s';
    cursor: default;
  }
  .opt:has(input:checked) {
    box-shadow: inset 0 0 0 2px var(--accent);
  }
  .opt input {
    grid-area: i;
    accent-color: var(--accent);
    width: 18px;
    height: 18px;
  }
  .lbl {
    grid-area: l;
    font-weight: 700;
  }
  .sub {
    grid-area: u;
    font-size: 0.75rem;
    color: var(--text-secondary);
  }
  .size {
    grid-area: s;
    font-variant-numeric: tabular-nums;
    color: var(--text-secondary);
  }
  .range {
    width: 100%;
    accent-color: var(--accent);
  }
  .note {
    margin: 4px 0 0;
    font-size: 0.75rem;
    color: var(--text-secondary);
  }
  .sum {
    display: flex;
    justify-content: space-between;
    gap: 10px;
    margin: 6px 0;
    font-size: 0.85rem;
    color: var(--text-secondary);
  }
  .sum b {
    color: var(--text-primary);
  }
  .err {
    color: #ff8a8a;
    font-size: 0.85rem;
  }
  .ios {
    font-size: 0.78rem;
    padding: 8px 10px;
    border-radius: 12px;
    background: color-mix(in srgb, #f5a623 16%, transparent);
  }
  .go {
    width: 100%;
    margin-top: 8px;
  }
  .go[disabled] {
    opacity: 0.55;
  }
</style>
