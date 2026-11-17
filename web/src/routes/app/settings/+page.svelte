<script lang="ts">
  import { base } from '$app/paths';
  import Sheet from '$lib/components/Sheet.svelte';
  import PinPad from '$lib/adult/PinPad.svelte';
  import { adult } from '$lib/adult/store.svelte';
  import { THEME_NAMES, theme, themeDescriptor } from '$lib/design/theme.svelte';
  import { themes, type ThemeName } from '$lib/design/tokens';
  import { perf } from '$lib/perf/tier.svelte';
  import type { TierOverride } from '$lib/perf/tier';
  import { repo, setSimulatedLatency } from '$lib/data/repo';
  import { localKV, readJSON, writeJSON } from '$lib/data/kv';
  import { profiles, clearProfileHistory } from '$lib/profile/store.svelte';
  import { initials } from '$lib/profile/profiles';
  import { settings } from '$lib/settings/store.svelte';
  import { DOUBLE_TAP_OPTIONS, HOLD_SPEED_OPTIONS, SUB_LANGS } from '$lib/settings/settings';
  import { DEFAULT_SUB_STYLE, type SubStyle } from '$lib/player/subs';
  import { gatewayBase, normalizeBase, setGatewayBase } from '$lib/data/gateway';
  import { debugLog } from '$lib/errors';

  const tiers: { v: TierOverride; l: string }[] = [
    { v: 'AUTO', l: 'Auto' },
    { v: 'LOW', l: 'Low' },
    { v: 'MID', l: 'Medium' },
    { v: 'HIGH', l: 'High' }
  ];
  let latencyOn = $state(repo.latency.enabled);
  let notice = $state('');

  // ---- Developer: gateway base URL (value hidden by default, never logged) ----
  let gw = $state(gatewayBase());
  let gwShow = $state(false);
  let gwMsg = $state('');
  let devLog = $state(debugLog.enabled);
  let logOpen = $state(false);
  function toggleDevLog(on: boolean) {
    debugLog.setEnabled(on);
    devLog = debugLog.enabled;
    if (!on) logOpen = false;
  }
  function saveGateway(v: string | null) {
    if (v !== null && normalizeBase(v) === null) {
      gwMsg = 'Enter an http(s) URL (empty = off)';
      return;
    }
    setGatewayBase(v);
    gw = gatewayBase();
    gwMsg = 'Saved — reloading…';
    setTimeout(() => location.reload(), 400);
  }

  function pickTheme(name: ThemeName) {
    theme.set(name);
    profiles.rememberTheme(name);
  }

  const activeThemeNote = $derived(themeDescriptor(theme.current).note);

  // ---- subtitles (per-profile style shared with the player) ----
  const styleKey = `cinetor.substyle.${profiles.activeId}`;
  let sub = $state<SubStyle>({ ...DEFAULT_SUB_STYLE, ...readJSON<Partial<SubStyle>>(localKV(), styleKey, {}) });
  function setSub(patch: Partial<SubStyle>) {
    sub = { ...sub, ...patch };
    writeJSON(localKV(), styleKey, sub);
  }
  const SUB_COLORS = ['#ffffff', '#ffe14d', '#7ef9ff', '#a6ff7e'];

  // ---- data ----
  async function clearCache() {
    try {
      if (typeof caches !== 'undefined') for (const k of await caches.keys()) if (k.startsWith('cinetor-')) await caches.delete(k);
      if (typeof indexedDB !== 'undefined') indexedDB.deleteDatabase('cinetor');
      notice = 'Cache cleared — catalog and images reload from the network.';
    } catch {
      notice = "Couldn't clear the cache.";
    }
  }
  async function clearHistory() {
    if (!confirm('Clear watch history and search recents for this profile?')) return;
    await clearProfileHistory(profiles.activeId);
    notice = 'History cleared.';
  }

  // ---- content: 18+ ----
  let gateOpen = $state(false);
  let step = $state<'age' | 'pin' | 'confirm'>('age');
  let ageOk = $state(false);
  let firstPin = '';
  let changeOpen = $state(false);
  let changeStep = $state<'old' | 'new'>('old');
  let oldPin = '';

  function toggleAdult() {
    if (adult.enabled) {
      adult.disable();
      notice = '18+ section hidden.';
      return;
    }
    step = 'age';
    ageOk = false;
    gateOpen = true;
  }
  function pinStep(pin: string): true | string {
    firstPin = pin;
    step = 'confirm';
    return true;
  }
  async function confirmStep(pin: string): Promise<true | string> {
    if (pin !== firstPin) {
      step = 'pin';
      return "PINs didn't match — try again";
    }
    const r = await adult.enable(ageOk, pin);
    if (!r.ok) return r.error;
    gateOpen = false;
    notice = '18+ section enabled. It now appears in the Spaces menu.';
    return true;
  }
  function openChange() {
    changeStep = 'old';
    changeOpen = true;
  }
  async function changeOld(pin: string): Promise<true | string> {
    oldPin = pin;
    changeStep = 'new';
    return true;
  }
  async function changeNew(pin: string): Promise<true | string> {
    const r = await adult.changePin(oldPin, pin);
    if (!r.ok) {
      changeStep = 'old';
      return r.error;
    }
    changeOpen = false;
    notice = 'PIN changed.';
    return true;
  }
</script>

<svelte:head><title>Settings · Cinetor</title></svelte:head>

<div class="settings">
  <h1>Settings</h1>
  {#if notice}<p class="notice" role="status">{notice}</p>{/if}

  <section aria-labelledby="ap-h" class="card glass">
    <div class="sec-head">
      <h2 id="ap-h">Appearance</h2>
      <p class="active-theme-note">{activeThemeNote}</p>
    </div>
    <div class="themes" role="radiogroup" aria-label="Theme">
      {#each THEME_NAMES as name (name)}
        {@const t = themes[name]}
        {@const meta = themeDescriptor(name)}
        {@const selected = theme.current === name}
        {@const key = String(name)}
        <button
          type="button"
          class="theme-card"
          class:is-matte={key === 'matte'}
          class:is-material={key === 'material'}
          class:is-aura={key === 'aura'}
          class:is-pearl={key === 'pearl'}
          data-theme={name}
          role="radio"
          aria-checked={selected}
          style:--pv-bg={t.colors.bg}
          style:--pv-surface={t.colors.surface}
          style:--pv-elev={t.colors.surfaceElevated}
          style:--pv-fill={t.colors.glassFill}
          style:--pv-stroke={t.colors.glassStroke}
          style:--pv-accent={t.colors.accent}
          style:--pv-accent-end={t.colors.accentEnd}
          style:--pv-text={t.colors.textPrimary}
          style:--pv-text2={t.colors.textSecondary}
          onclick={() => pickTheme(name)}
        >
          <span class="preview" aria-hidden="true">
            <span class="pv-glow"></span>
            <span class="pv-stage">
              <span class="pv-head">
                <span class="pv-title"></span>
                {#if meta.badge}<span class="pv-tag">{meta.badge}</span>{/if}
              </span>
              <span class="pv-posters">
                <span></span>
                <span></span>
                <span></span>
              </span>
              <span class="pv-actions">
                <span class="pv-cta"></span>
                <span class="pv-circle"></span>
              </span>
            </span>
            <span class="pv-glass">
              <i></i><i></i><i></i><i></i>
            </span>
          </span>
          <span class="meta-row">
            <span class="name-wrap">
              {#if selected}
                <svg class="chk" viewBox="0 0 16 16" width="14" height="14" fill="none" aria-hidden="true">
                  <path d="M3.2 8.4l3.1 3.1 6.5-6.6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
              {/if}
              <span class="name">{t.name}</span>
            </span>
            <span class="swatches" aria-hidden="true">
              <i style:background={t.colors.bg}></i>
              <i style:background={t.colors.surfaceElevated}></i>
              <i style:background={t.colors.accent}></i>
            </span>
          </span>
          <span class="theme-sub">{meta.note}</span>
        </button>
      {/each}
    </div>

    <h3>Effects quality</h3>
    <div class="row-chips" role="radiogroup" aria-label="Effects quality">
      {#each tiers as t (t.v)}
        <button type="button" class="chip" class:active={perf.override === t.v} role="radio" aria-checked={perf.override === t.v} onclick={() => perf.setOverride(t.v)}>{t.l}</button>
      {/each}
    </div>
    <p class="note">Active: <b>{perf.flags.tier}</b> · blur {perf.flags.blurScope} · animations ×{perf.flags.durationScale}</p>
    <label class="toggle"><input type="checkbox" checked={settings.s.reduceMotion} onchange={(e) => settings.set('reduceMotion', e.currentTarget.checked)} /><span>Reduce motion</span></label>
  </section>

  <section aria-labelledby="pb-h" class="card glass">
    <h2 id="pb-h">Playback</h2>
    <label class="toggle"><input type="checkbox" checked={settings.s.autoplayNext} onchange={(e) => settings.set('autoplayNext', e.currentTarget.checked)} /><span>Autoplay next episode</span></label>
    <label class="toggle"><input type="checkbox" checked={settings.s.gestures} onchange={(e) => settings.set('gestures', e.currentTarget.checked)} /><span>Player gestures (double-tap seek, swipe brightness/volume, hold for speed)</span></label>
    <h3>Double-tap seek</h3>
    <div class="row-chips" role="radiogroup" aria-label="Double-tap seconds">
      {#each DOUBLE_TAP_OPTIONS as s (s)}
        <button type="button" class="chip" class:active={settings.s.doubleTapSec === s} role="radio" aria-checked={settings.s.doubleTapSec === s} onclick={() => settings.set('doubleTapSec', s)}>{s}s</button>
      {/each}
    </div>
    <h3>Hold speed</h3>
    <div class="row-chips" role="radiogroup" aria-label="Hold speed">
      {#each HOLD_SPEED_OPTIONS as s (s)}
        <button type="button" class="chip" class:active={settings.s.holdSpeed === s} role="radio" aria-checked={settings.s.holdSpeed === s} onclick={() => settings.set('holdSpeed', s)}>{s}×</button>
      {/each}
    </div>
  </section>

  <section aria-labelledby="sb-h" class="card glass">
    <h2 id="sb-h">Subtitles</h2>
    <label class="toggle"><input type="checkbox" checked={settings.s.subsAuto} onchange={(e) => settings.set('subsAuto', e.currentTarget.checked)} /><span>Turn on automatically</span></label>
    <label class="field"><span>Preferred language</span>
      <select value={settings.s.subLang} onchange={(e) => settings.set('subLang', e.currentTarget.value)}>
        {#each SUB_LANGS as l (l.code)}<option value={l.code}>{l.label}</option>{/each}
      </select>
    </label>
    <label class="field"><span>Size · {sub.size}%</span><input type="range" min="50" max="200" step="10" value={sub.size} oninput={(e) => setSub({ size: Number(e.currentTarget.value) })} /></label>
    <div class="row-chips" role="radiogroup" aria-label="Subtitle colour">
      {#each SUB_COLORS as c (c)}
        <button type="button" class="sw" style:background={c} role="radio" aria-checked={sub.color === c} aria-label="Colour {c}" onclick={() => setSub({ color: c })}></button>
      {/each}
    </div>
    <label class="field"><span>Background · {Math.round(sub.bg * 100)}%</span><input type="range" min="0" max="1" step="0.1" value={sub.bg} oninput={(e) => setSub({ bg: Number(e.currentTarget.value) })} /></label>
    <div class="sample"><span style:font-size="{sub.size / 100}rem" style:color={sub.color} style:background="rgb(0 0 0 / {sub.bg})">The night is young, and so are we.</span></div>
  </section>

  <section aria-labelledby="dt-h" class="card glass">
    <h2 id="dt-h">Data</h2>
    <label class="toggle"><input type="checkbox" checked={settings.s.preloadDetails} onchange={(e) => settings.set('preloadDetails', e.currentTarget.checked)} /><span>Preload video on the details page (instant play)</span></label>
    <label class="toggle"><input type="checkbox" bind:checked={latencyOn} onchange={() => setSimulatedLatency(latencyOn)} /><span>Simulated network latency (skeleton preview)</span></label>
    <div class="acts">
      <button type="button" class="btn btn-ghost" onclick={clearCache}>Clear cache</button>
      <button type="button" class="btn btn-ghost" onclick={clearHistory}>Clear history</button>
    </div>
  </section>

  <section id="developer" aria-labelledby="dev-h" class="card glass">
    <h2 id="dev-h">Developer</h2>
    <label class="field">
      <span>Streaming gateway (catalog + sources for Home, Anime, Drama, 18+)</span>
      <input class="txt-in" type={gwShow ? 'url' : 'password'} inputmode="url" spellcheck="false" autocomplete="off" bind:value={gw} placeholder="Leave empty to turn the gateway off" />
    </label>
    <div class="acts">
      <button type="button" class="btn btn-ghost" onclick={() => (gwShow = !gwShow)} aria-pressed={gwShow}>{gwShow ? 'Hide' : 'Show'}</button>
      <button type="button" class="btn btn-ghost" onclick={() => saveGateway(gw)}>Save</button>
      <button type="button" class="btn btn-ghost" onclick={() => saveGateway(null)}>Reset to default</button>
    </div>
    {#if gwMsg}<p class="note" role="status">{gwMsg}</p>{/if}
    <label class="toggle">
      <input type="checkbox" checked={devLog} onchange={(e) => toggleDevLog(e.currentTarget.checked)} />
      <span>Keep a local debug log</span>
    </label>
    <p class="note">Off by default. Technical detail stays on this device and is never sent anywhere.</p>
    {#if devLog}
      <div class="acts">
        <button type="button" class="btn btn-ghost" onclick={() => (logOpen = !logOpen)} aria-expanded={logOpen}>{logOpen ? 'Hide log' : `Show log (${debugLog.entries.length})`}</button>
        <button type="button" class="btn btn-ghost" onclick={() => debugLog.clear()}>Clear log</button>
      </div>
      {#if logOpen}
        <ul class="log">
          {#each debugLog.entries.slice(-40) as e (e.t + e.tag + e.text)}
            <li><b>{e.tag}</b> {new Date(e.t).toLocaleTimeString()} — {e.text}</li>
          {:else}
            <li class="note">Nothing logged yet.</li>
          {/each}
        </ul>
      {/if}
    {/if}
  </section>

  <section id="content" aria-labelledby="ct-h" class="card glass">
    <h2 id="ct-h">Content</h2>
    <label class="toggle" class:off={!adult.canEnable}>
      <input type="checkbox" checked={adult.enabled} disabled={!adult.canEnable} onchange={(e) => { e.currentTarget.checked = adult.enabled; toggleAdult(); }} />
      <span>Show 18+ section</span>
    </label>
    {#if !adult.canEnable}
      <p class="note">Not available on Kids profiles.</p>
    {:else if adult.enabled}
      <p class="note">PIN required once per session; locks again after 5 minutes in the background. Never in search, recents or continue watching.</p>
      <div class="acts">
        <button type="button" class="btn btn-ghost" onclick={openChange}>Change PIN</button>
        {#if adult.unlocked}<button type="button" class="btn btn-ghost" onclick={() => adult.lock()}>Lock now</button>{/if}
      </div>
    {/if}
  </section>

  <section aria-labelledby="pf-h" class="card glass">
    <h2 id="pf-h">Profiles</h2>
    <a class="prof" href="{base}/app/me">
      <span class="av" style:background={profiles.active.color}>{initials(profiles.active.name)}</span>
      <span><b>{profiles.active.name}</b><small>{profiles.list.length} of 5 profiles · switch, add or edit</small></span>
      <span aria-hidden="true">›</span>
    </a>
  </section>

  <a class="about card glass" href="{base}/app/about"><b>About Cinetor</b><span aria-hidden="true">›</span></a>
</div>

<Sheet bind:open={gateOpen} title="Show 18+ section">
  {#if step === 'age'}
    <div class="age">
      <p>This section contains mature content intended for adults only.</p>
      <label class="toggle"><input type="checkbox" bind:checked={ageOk} /><span>I confirm I am 18 years or older</span></label>
      <button type="button" class="btn btn-primary" disabled={!ageOk} onclick={() => (step = 'pin')}>Continue</button>
    </div>
  {:else if step === 'pin'}
    <PinPad title="Set a 4-digit PIN" hint="You'll need it to open the 18+ section." onSubmit={pinStep} />
  {:else}
    <PinPad title="Confirm PIN" onSubmit={confirmStep} />
  {/if}
</Sheet>

<Sheet bind:open={changeOpen} title="Change 18+ PIN">
  {#if changeStep === 'old'}
    <PinPad title="Current PIN" onSubmit={changeOld} />
  {:else}
    <PinPad title="New PIN" onSubmit={changeNew} />
  {/if}
</Sheet>

<style>
  .settings {
    max-width: 860px;
    padding: 0 16px;
    display: grid;
    gap: 14px;
  }
  h1 {
    margin: 4px 0;
  }
  .notice {
    margin: 0;
    padding: 10px 14px;
    border-radius: 14px;
    color: var(--text-primary);
    background: color-mix(in srgb, var(--accent) 16%, var(--surface-elevated));
    box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--accent) 36%, transparent);
  }
  .card {
    display: grid;
    gap: 10px;
    padding: 16px 18px;
    border-radius: 22px;
  }
  .sec-head {
    display: flex;
    flex-wrap: wrap;
    align-items: baseline;
    justify-content: space-between;
    gap: 6px 12px;
  }
  .active-theme-note {
    margin: 0;
    font-size: 0.8rem;
    color: var(--text-secondary);
  }
  h2 {
    margin: 0 0 4px;
    font-size: 1.05rem;
  }
  h3 {
    margin: 6px 0 0;
    font-size: 0.85rem;
    color: var(--text-secondary);
    font-weight: 650;
  }

  /* 8-theme responsive showcase grid: 2×4 on mobile, 4×2 on tablet/desktop. */
  .themes {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
  }
  @media (min-width: 640px) {
    .themes {
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: 12px;
    }
  }

  .theme-card {
    display: grid;
    align-content: start;
    gap: 6px;
    padding: 8px;
    border: 0;
    border-radius: 18px;
    background: color-mix(in srgb, var(--surface-elevated) 62%, transparent);
    color: var(--text-primary);
    box-shadow: inset 0 0 0 1px var(--divider);
    cursor: pointer;
    text-align: left;
    transition:
      transform calc(var(--dur-short) * var(--dur-scale)) var(--ease-emphasized),
      box-shadow calc(var(--dur-medium) * var(--dur-scale)) var(--ease-standard),
      background calc(var(--dur-medium) * var(--dur-scale)) var(--ease-standard);
  }
  .theme-card:hover {
    background: color-mix(in srgb, var(--surface-elevated) 86%, transparent);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
  }
  .theme-card:active {
    transform: scale(0.98);
  }
  .theme-card[aria-checked='true'] {
    background: color-mix(in srgb, var(--accent) 8%, var(--surface-elevated));
    box-shadow:
      0 0 0 2px var(--accent),
      0 10px 28px -12px var(--accent-glow);
  }

  /* Miniature cinema viewport rendered in each theme's true material & palette. */
  .preview {
    position: relative;
    display: block;
    aspect-ratio: 16 / 11;
    border-radius: 12px;
    overflow: hidden;
    background: var(--pv-bg);
    box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--pv-text) 12%, transparent);
  }
  .pv-glow {
    position: absolute;
    inset: 0;
    background:
      radial-gradient(85% 70% at 22% 8%, color-mix(in srgb, var(--pv-accent) 44%, transparent), transparent 70%),
      radial-gradient(60% 55% at 92% 24%, color-mix(in srgb, var(--pv-accent-end) 28%, transparent), transparent 68%);
  }
  .theme-card.is-matte .pv-glow {
    opacity: 0;
  }
  .theme-card.is-material .pv-glow {
    background: radial-gradient(80% 60% at 50% 0%, color-mix(in srgb, var(--pv-accent) 16%, transparent), transparent 72%);
  }
  .theme-card.is-aura .pv-glow {
    background:
      radial-gradient(90% 80% at 20% 10%, rgb(225 29 46 / 0.42), transparent 68%),
      radial-gradient(80% 75% at 86% 28%, rgb(255 255 255 / 0.18), transparent 70%),
      linear-gradient(180deg, rgb(225 29 46 / 0.18), transparent 85%);
  }
  .theme-card.is-pearl .pv-glow {
    background:
      radial-gradient(85% 65% at 24% 8%, color-mix(in srgb, var(--pv-accent) 16%, transparent), transparent 70%),
      linear-gradient(180deg, rgb(255 255 255 / 0.7), transparent 60%);
  }

  .pv-stage {
    position: absolute;
    inset: 8px 8px 30%;
    display: grid;
    align-content: space-between;
    gap: 5px;
  }
  .pv-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 4px;
  }
  .pv-title {
    width: 38%;
    height: 4px;
    border-radius: 99px;
    background: var(--pv-text);
    opacity: 0.9;
  }
  .pv-tag {
    font-size: 0.58rem;
    font-weight: 700;
    line-height: 1;
    letter-spacing: 0.02em;
    padding: 2px 5px;
    border-radius: 99px;
    color: var(--pv-text);
    background: color-mix(in srgb, var(--pv-surface) 76%, transparent);
    box-shadow: inset 0 0 0 1px var(--pv-stroke);
  }
  .pv-posters {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 5px;
    flex: 1;
    min-height: 28px;
  }
  .pv-posters span {
    border-radius: 5px;
    background:
      linear-gradient(160deg, color-mix(in srgb, var(--pv-text) 12%, transparent), transparent 70%),
      color-mix(in srgb, var(--pv-elev) 78%, transparent);
    box-shadow:
      inset 0 1px 0 rgb(255 255 255 / 0.18),
      inset 0 0 0 1px var(--pv-stroke);
  }
  .theme-card.is-matte .pv-posters span {
    background: var(--pv-elev);
    box-shadow: none;
  }
  .theme-card.is-material .pv-posters span {
    border-radius: 9px;
    background: var(--pv-elev);
    box-shadow: inset 0 0 0 1px var(--pv-stroke);
  }
  .theme-card.is-pearl .pv-posters span {
    background: linear-gradient(180deg, rgb(255 255 255 / 0.92), color-mix(in srgb, var(--pv-elev) 85%, transparent));
    box-shadow:
      inset 0 1px 0 rgb(255 255 255 / 0.95),
      0 2px 6px -2px rgb(28 22 18 / 0.1),
      inset 0 0 0 1px rgb(20 20 22 / 0.1);
  }

  .pv-actions {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }
  .pv-cta {
    width: 42%;
    height: 9px;
    border-radius: 4px;
    background: linear-gradient(135deg, var(--pv-accent), var(--pv-accent-end));
  }
  .theme-card.is-material .pv-cta {
    border-radius: 99px;
  }
  .pv-circle {
    width: 10px;
    height: 10px;
    border-radius: 50%;
    background: color-mix(in srgb, var(--pv-elev) 85%, transparent);
    box-shadow: inset 0 0 0 1px var(--pv-stroke);
  }

  /* Mini Liquid Glass bottom dock (and compact floating capsule on Aura). */
  .pv-glass {
    position: absolute;
    left: 7%;
    right: 7%;
    bottom: 7%;
    height: 19%;
    border-radius: 8px;
    display: flex;
    align-items: center;
    justify-content: space-around;
    padding: 0 6px;
    background: color-mix(in srgb, var(--pv-surface) 78%, transparent);
    box-shadow:
      inset 0 1px 0 rgb(255 255 255 / 0.2),
      inset 0 0 0 1px var(--pv-stroke);
  }
  .theme-card.is-matte .pv-glass {
    background: var(--pv-surface);
    box-shadow: inset 0 0 0 1px rgb(255 255 255 / 0.07);
  }
  .theme-card.is-material .pv-glass {
    border-radius: 99px;
    background: var(--pv-elev);
  }
  .theme-card.is-aura .pv-glass {
    left: 16%;
    right: 16%;
    border-radius: 99px;
    background:
      radial-gradient(100% 140% at 50% 0%, rgb(225 29 46 / 0.28), transparent 70%),
      color-mix(in srgb, var(--pv-surface) 76%, transparent);
    box-shadow:
      inset 0 1px 0 rgb(255 255 255 / 0.32),
      inset 0 0 0 1px rgb(255 255 255 / 0.22),
      0 4px 10px -3px rgb(0 0 0 / 0.7);
  }
  .theme-card.is-pearl .pv-glass {
    background: rgb(255 255 255 / 0.88);
    box-shadow:
      inset 0 1px 0 #ffffff,
      inset 0 0 0 1px rgb(20 20 22 / 0.1),
      0 3px 8px -3px rgb(28 22 18 / 0.14);
  }
  .pv-glass i {
    width: 14%;
    height: 32%;
    border-radius: 99px;
    background: var(--pv-text2);
    opacity: 0.65;
  }
  .pv-glass i:first-child {
    width: 20%;
    background: var(--pv-accent);
    opacity: 1;
  }

  .meta-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 6px;
    padding-top: 2px;
  }
  .name-wrap {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    min-width: 0;
  }
  .chk {
    flex: none;
    color: var(--accent);
  }
  .name {
    font-weight: 750;
    font-size: 0.86rem;
    color: var(--text-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .swatches {
    display: inline-flex;
    align-items: center;
    flex: none;
  }
  .swatches i {
    width: 10px;
    height: 10px;
    border-radius: 50%;
    box-shadow: 0 0 0 1px rgb(128 128 128 / 0.32);
  }
  .swatches i + i {
    margin-left: -3px;
  }
  .theme-sub {
    font-size: 0.72rem;
    line-height: 1.32;
    color: var(--text-secondary);
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }

  .row-chips {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }
  .note {
    margin: 0;
    font-size: 0.78rem;
    color: var(--text-secondary);
  }
  .log {
    list-style: none;
    margin: 8px 0 0;
    padding: 8px 10px;
    max-height: 220px;
    overflow: auto;
    display: grid;
    gap: 4px;
    border-radius: 12px;
    background: var(--surface-elevated);
    color: var(--text-primary);
    box-shadow: inset 0 0 0 1px var(--divider);
    font-size: 0.72rem;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    overflow-wrap: anywhere;
  }
  .toggle {
    display: flex;
    gap: 12px;
    align-items: center;
    cursor: pointer;
  }
  .toggle.off {
    opacity: 0.5;
    cursor: not-allowed;
  }
  .toggle input {
    flex: none;
    width: 20px;
    height: 20px;
    accent-color: var(--accent);
  }
  .field {
    display: grid;
    gap: 6px;
    font-size: 0.85rem;
    color: var(--text-secondary);
  }
  select,
  .txt-in {
    min-height: 42px;
    border-radius: 12px;
    border: 0;
    padding: 0 12px;
    font: inherit;
    font-size: 0.9rem;
    background: var(--glass-fill);
    color: var(--text-primary);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
  }
  :global([data-theme='pearl']) select,
  :global([data-theme='pearl']) .txt-in {
    background: var(--surface-elevated);
    box-shadow: inset 0 0 0 1px rgb(20 20 22 / 0.16);
  }
  select:focus-visible,
  .txt-in:focus-visible {
    outline: 2px solid var(--focus-ring);
    outline-offset: 2px;
  }
  .txt-in::placeholder {
    color: var(--text-tertiary);
  }
  input[type='range'] {
    accent-color: var(--accent);
  }
  .sw {
    width: 32px;
    height: 32px;
    border-radius: 50%;
    border: 0;
    box-shadow: inset 0 0 0 1px rgb(20 20 22 / 0.24);
    cursor: pointer;
  }
  .sw[aria-checked='true'] {
    box-shadow:
      inset 0 0 0 1px rgb(20 20 22 / 0.24),
      0 0 0 3px var(--bg, #000),
      0 0 0 5px var(--accent);
  }
  .sample {
    display: grid;
    place-items: center;
    min-height: 90px;
    border-radius: 14px;
    background: linear-gradient(135deg, #2a1a1a, #101820);
    padding: 10px;
  }
  .sample span {
    padding: 2px 8px;
    border-radius: 4px;
    text-align: center;
  }
  .acts {
    display: flex;
    gap: 10px;
    flex-wrap: wrap;
  }
  .age {
    display: grid;
    gap: 14px;
  }
  .prof,
  .about {
    display: flex;
    align-items: center;
    gap: 12px;
    text-decoration: none;
  }
  .prof span:nth-child(2) {
    flex: 1;
    display: grid;
  }
  .prof small {
    color: var(--text-secondary);
  }
  .about {
    justify-content: space-between;
  }
  .av {
    width: 44px;
    height: 44px;
    display: grid;
    place-items: center;
    border-radius: 50%;
    font-weight: 800;
    color: #fff;
  }
</style>
