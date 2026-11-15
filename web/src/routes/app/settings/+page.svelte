<script lang="ts">
  import { base } from '$app/paths';
  import Sheet from '$lib/components/Sheet.svelte';
  import PinPad from '$lib/adult/PinPad.svelte';
  import { adult } from '$lib/adult/store.svelte';
  import { THEME_NAMES, theme } from '$lib/design/theme.svelte';
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

  const tiers: { v: TierOverride; l: string }[] = [
    { v: 'AUTO', l: 'Auto' },
    { v: 'LOW', l: 'Low' },
    { v: 'MID', l: 'Medium' },
    { v: 'HIGH', l: 'High' }
  ];
  let latencyOn = $state(repo.latency.enabled);
  let notice = $state('');

  function pickTheme(name: ThemeName) {
    theme.set(name);
    profiles.rememberTheme(name);
  }

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
    <h2 id="ap-h">Appearance</h2>
    <div class="themes" role="radiogroup" aria-label="Theme">
      {#each THEME_NAMES as name (name)}
        {@const t = themes[name]}
        <button class="theme-card" data-theme={name} role="radio" aria-checked={theme.current === name} onclick={() => pickTheme(name)}>
          <span class="preview">
            <span class="pv-hero"></span>
            <span class="pv-glass glass glass-bar"><i></i><i></i><i></i></span>
          </span>
          <span class="name">{t.name}</span>
        </button>
      {/each}
    </div>
    <h3>Effects quality</h3>
    <div class="row-chips" role="radiogroup" aria-label="Effects quality">
      {#each tiers as t (t.v)}
        <button class="chip" class:active={perf.override === t.v} role="radio" aria-checked={perf.override === t.v} onclick={() => perf.setOverride(t.v)}>{t.l}</button>
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
        <button class="chip" class:active={settings.s.doubleTapSec === s} role="radio" aria-checked={settings.s.doubleTapSec === s} onclick={() => settings.set('doubleTapSec', s)}>{s}s</button>
      {/each}
    </div>
    <h3>Hold speed</h3>
    <div class="row-chips" role="radiogroup" aria-label="Hold speed">
      {#each HOLD_SPEED_OPTIONS as s (s)}
        <button class="chip" class:active={settings.s.holdSpeed === s} role="radio" aria-checked={settings.s.holdSpeed === s} onclick={() => settings.set('holdSpeed', s)}>{s}×</button>
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
        <button class="sw" style:background={c} role="radio" aria-checked={sub.color === c} aria-label="Colour {c}" onclick={() => setSub({ color: c })}></button>
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
      <button class="btn btn-ghost" onclick={clearCache}>Clear cache</button>
      <button class="btn btn-ghost" onclick={clearHistory}>Clear history</button>
    </div>
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
        <button class="btn btn-ghost" onclick={openChange}>Change PIN</button>
        {#if adult.unlocked}<button class="btn btn-ghost" onclick={() => adult.lock()}>Lock now</button>{/if}
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
      <button class="btn btn-primary" disabled={!ageOk} onclick={() => (step = 'pin')}>Continue</button>
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
    max-width: 820px;
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
    background: color-mix(in srgb, var(--accent) 18%, transparent);
  }
  .card {
    display: grid;
    gap: 10px;
    padding: 16px 18px;
    border-radius: 22px;
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
  .themes {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 12px;
  }
  .theme-card {
    display: grid;
    gap: 6px;
    padding: 8px;
    border: 0;
    border-radius: 18px;
    background: var(--bg);
    color: var(--text-primary);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
    cursor: pointer;
    text-align: left;
  }
  .theme-card[aria-checked='true'] {
    box-shadow: 0 0 0 2px var(--accent), 0 10px 30px -12px var(--accent-glow);
  }
  .preview {
    position: relative;
    display: block;
    aspect-ratio: 4 / 3;
    border-radius: 12px;
    overflow: hidden;
    background: var(--surface);
  }
  .pv-hero {
    position: absolute;
    inset: 0;
    background: radial-gradient(80% 70% at 20% 10%, var(--accent), transparent 70%), radial-gradient(60% 60% at 90% 30%, var(--accent-end), transparent 70%);
    opacity: 0.85;
  }
  .pv-glass {
    position: absolute !important;
    left: 8%;
    right: 8%;
    bottom: 8%;
    height: 26%;
    border-radius: 10px;
    display: flex;
    align-items: center;
    justify-content: space-around;
  }
  .pv-glass i {
    width: 14%;
    height: 34%;
    border-radius: 99px;
    background: var(--text-secondary);
  }
  .pv-glass i:first-child {
    background: var(--accent);
  }
  .name {
    font-weight: 750;
    font-size: 0.85rem;
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
  select {
    min-height: 40px;
    border-radius: 12px;
    border: 0;
    padding: 0 10px;
    background: var(--glass-fill);
    color: var(--text-primary);
  }
  input[type='range'] {
    accent-color: var(--accent);
  }
  .sw {
    width: 32px;
    height: 32px;
    border-radius: 50%;
    border: 0;
    cursor: pointer;
  }
  .sw[aria-checked='true'] {
    box-shadow: 0 0 0 3px var(--bg, #000), 0 0 0 5px var(--accent);
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
