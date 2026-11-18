<script lang="ts">
  import { onMount } from 'svelte';
  import Sheet from '$lib/components/Sheet.svelte';
  import PinPad from '$lib/adult/PinPad.svelte';
  import { adult } from '$lib/adult/store.svelte';
  import { THEME_NAMES, theme, themeDescriptor } from '$lib/design/theme.svelte';
  import { themes, type ThemeName } from '$lib/design/tokens';
  import { perf } from '$lib/perf/tier.svelte';
  import { localKV, writeJSON } from '$lib/data/kv';
  import { history } from '$lib/data/search-client';
  import { downloads } from '$lib/download/manager.svelte';
  import { formatBytes } from '$lib/download/math';
  import { isTauriEnv, locationInfo, saveCopySupported } from '$lib/desktop/env';
  import { resume } from '$lib/player/controller';
  import { DEFAULT_SUB_STYLE } from '$lib/player/subs';
  import { profiles } from '$lib/profile/store.svelte';
  import { settings } from '$lib/settings/store.svelte';
  import { DEFAULT_SETTINGS, DOUBLE_TAP_OPTIONS, type Settings } from '$lib/settings/settings';

  const SEARCH_SCOPES = ['global', 'tv', 'music', 'comics', 'adult'] as const;

  let notice = $state('');
  let tauri = $state(false);
  let dlDir = $state<string | null>(null);
  let pickingDir = $state(false);

  // Confirmation sheets (no browser window.confirm prompts)
  let confirmAction = $state<'search' | 'watch' | 'reset' | null>(null);

  // 18+ PIN gate & change sheets
  let gateOpen = $state(false);
  let step = $state<'age' | 'pin' | 'confirm'>('age');
  let ageOk = $state(false);
  let firstPin = '';
  let changeOpen = $state(false);
  let changeStep = $state<'old' | 'new'>('old');
  let oldPin = '';

  const activeThemeNote = $derived(themeDescriptor(theme.current).note);
  const loc = $derived(locationInfo({ tauri, dir: dlDir, saveCopy: saveCopySupported() }));
  const usedLabel = $derived(formatBytes(downloads.usedBytes));
  const freeBytes = $derived(Math.max(0, downloads.quota.quota - downloads.quota.usage));
  const storageSub = $derived(
    downloads.quota.quota > 0
      ? `${usedLabel} used by Cinetor · ${formatBytes(freeBytes)} free`
      : `${usedLabel} used by Cinetor`
  );

  onMount(() => {
    tauri = isTauriEnv();
    void downloads.init().then(() => downloads.refreshQuota());
    if (tauri) void loadDesktopDir();
  });

  async function loadDesktopDir() {
    try {
      const { downloadsDir } = await import('$lib/desktop/native-dl');
      dlDir = await downloadsDir();
    } catch {
      dlDir = null;
    }
  }

  async function chooseDesktopDir() {
    pickingDir = true;
    try {
      const { pickDownloadsDir } = await import('$lib/desktop/native-dl');
      const picked = await pickDownloadsDir();
      if (picked) {
        dlDir = picked;
        notice = 'Download folder updated.';
      }
    } catch {
      notice = "Couldn't open the folder picker.";
    } finally {
      pickingDir = false;
    }
  }

  function pickTheme(name: ThemeName) {
    theme.set(name);
    profiles.rememberTheme(name);
  }

  function toggleHoldSpeed(on: boolean) {
    settings.set('holdForSpeed', on);
    settings.set('holdSpeed', on ? 2 : 1);
  }

  async function clearSearchHistoryNow() {
    const id = profiles.activeId;
    await Promise.all(SEARCH_SCOPES.map((s) => history.clear(id, s).catch(() => undefined)));
    confirmAction = null;
    notice = 'Search history cleared.';
  }

  async function clearWatchHistoryNow() {
    await resume.clearProfile(profiles.activeId).catch(() => 0);
    confirmAction = null;
    notice = 'Watch history cleared.';
  }

  async function clearCache() {
    try {
      if (typeof caches !== 'undefined') {
        for (const k of await caches.keys()) {
          if (k.startsWith('cinetor-')) await caches.delete(k);
        }
      }
      if (typeof indexedDB !== 'undefined') indexedDB.deleteDatabase('cinetor');
      notice = 'Image and stream cache cleared.';
    } catch {
      notice = "Couldn't clear the cache.";
    }
  }

  function resetAllSettingsNow() {
    for (const k of Object.keys(DEFAULT_SETTINGS) as (keyof Settings)[]) {
      settings.set(k, DEFAULT_SETTINGS[k]);
    }
    perf.setOverride('AUTO');
    writeJSON(localKV(), `cinetor.substyle.${profiles.activeId}`, { ...DEFAULT_SUB_STYLE, bg: 0 });
    confirmAction = null;
    notice = 'Settings restored to default.';
  }

  function toggleAdult() {
    if (!adult.canEnable) return;
    if (adult.enabled) {
      adult.disable();
      notice = '18+ section hidden.';
      return;
    }
    step = 'age';
    ageOk = false;
    firstPin = '';
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
    notice = '18+ section enabled under Me · Spaces.';
    return true;
  }

  function openChange() {
    changeStep = 'old';
    oldPin = '';
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
    notice = '18+ PIN updated.';
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
          aria-label="Theme {t.name}"
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
  </section>

  <section aria-labelledby="pl-h" class="card glass">
    <h2 id="pl-h">Player</h2>

    <div class="choice-row">
      <div class="row-copy">
        <b>Double-tap skip</b>
        <small>Jump backward or forward when double-tapping the video</small>
      </div>
      <div class="row-chips" role="radiogroup" aria-label="Double-tap skip">
        {#each DOUBLE_TAP_OPTIONS as sec (sec)}
          <button
            type="button"
            class="chip"
            class:active={settings.s.doubleTapSec === sec}
            role="radio"
            aria-checked={settings.s.doubleTapSec === sec}
            onclick={() => settings.set('doubleTapSec', sec)}
          >
            {sec}s
          </button>
        {/each}
      </div>
    </div>

    <div class="sep"></div>

    <div class="switch-row">
      <div class="row-copy">
        <b>Hold for 2× speed</b>
        <small>Press and hold anywhere on the video to play at 2× speed</small>
      </div>
      <button
        type="button"
        class="switch"
        class:on={settings.s.holdForSpeed}
        role="switch"
        aria-checked={settings.s.holdForSpeed}
        aria-label="Hold for 2× speed"
        onclick={() => toggleHoldSpeed(!settings.s.holdForSpeed)}
      >
        <span class="knob"></span>
      </button>
    </div>

    <div class="sep"></div>

    <div class="switch-row">
      <div class="row-copy">
        <b>Floating Mini-Player / PiP</b>
        <small>Keep video playing in a floating corner window while browsing</small>
      </div>
      <button
        type="button"
        class="switch"
        class:on={settings.s.autoPip}
        role="switch"
        aria-checked={settings.s.autoPip}
        aria-label="Floating Mini-Player / PiP"
        onclick={() => settings.set('autoPip', !settings.s.autoPip)}
      >
        <span class="knob"></span>
      </button>
    </div>
  </section>

  <section aria-labelledby="dl-h" class="card glass">
    <h2 id="dl-h">Downloads</h2>

    <div class="switch-row">
      <div class="row-copy">
        <b>Download on Wi-Fi only</b>
        <small>Pause queued downloads on metered mobile connections</small>
      </div>
      <button
        type="button"
        class="switch"
        class:on={settings.s.wifiOnly}
        role="switch"
        aria-checked={settings.s.wifiOnly}
        aria-label="Download on Wi-Fi only"
        onclick={() => settings.set('wifiOnly', !settings.s.wifiOnly)}
      >
        <span class="knob"></span>
      </button>
    </div>

    <div class="sep"></div>

    <div class="info-row">
      <div class="row-copy">
        <b>Downloaded media storage</b>
        <small>{storageSub}</small>
      </div>
    </div>

    <div class="sep"></div>

    <div class="info-row">
      <div class="row-copy">
        <b>Download location · {loc.label}</b>
        <small>{loc.note}</small>
      </div>
      {#if loc.changeable}
        <button type="button" class="btn btn-ghost sm" disabled={pickingDir} onclick={chooseDesktopDir}>
          {pickingDir ? 'Opening…' : 'Change folder'}
        </button>
      {/if}
    </div>
  </section>

  <section id="content" aria-labelledby="ct-h" class="card glass">
    <h2 id="ct-h">Content &amp; Privacy</h2>

    <div class="switch-row" class:disabled-row={!adult.canEnable}>
      <div class="row-copy">
        <b>Show 18+ section</b>
        <small>
          {#if !adult.canEnable}
            Unavailable while Kids mode is active
          {:else if adult.enabled}
            PIN-protected Adult space visible under Me · Spaces
          {:else}
            Hidden everywhere until enabled with age confirmation and a 4-digit PIN
          {/if}
        </small>
      </div>
      <button
        type="button"
        class="switch"
        class:on={adult.enabled}
        disabled={!adult.canEnable}
        role="switch"
        aria-checked={adult.enabled}
        aria-label="Toggle 18+ section"
        onclick={toggleAdult}
      >
        <span class="knob"></span>
      </button>
    </div>

    {#if adult.enabled && adult.canEnable}
      <div class="acts">
        <button type="button" class="btn btn-ghost sm" onclick={openChange}>Change 18+ PIN</button>
        {#if adult.unlocked}
          <button
            type="button"
            class="btn btn-ghost sm"
            onclick={() => {
              adult.lock();
              notice = '18+ section locked.';
            }}
          >
            Lock now
          </button>
        {/if}
      </div>
    {/if}

    <div class="sep"></div>

    <button type="button" class="action-row" onclick={() => (confirmAction = 'search')}>
      <span class="row-copy">
        <b>Clear search history</b>
        <small>Remove recent search queries across all sections</small>
      </span>
      <span class="chev" aria-hidden="true">›</span>
    </button>

    <div class="sep"></div>

    <button type="button" class="action-row" onclick={() => (confirmAction = 'watch')}>
      <span class="row-copy">
        <b>Clear watch history</b>
        <small>Remove Continue Watching items and saved playback positions</small>
      </span>
      <span class="chev" aria-hidden="true">›</span>
    </button>

    <div class="sep"></div>

    <button type="button" class="action-row" onclick={clearCache}>
      <span class="row-copy">
        <b>Clear image &amp; stream cache</b>
        <small>Clear cached artwork and temporary catalog data</small>
      </span>
      <span class="chev" aria-hidden="true">›</span>
    </button>
  </section>

  <button type="button" class="reset-card glass" onclick={() => (confirmAction = 'reset')}>
    <span class="reset-ic" aria-hidden="true">↺</span>
    <span class="row-copy">
      <b>Reset settings</b>
      <small>Restore theme, player, and download preferences to default. History and My List are kept.</small>
    </span>
  </button>
</div>

<Sheet bind:open={gateOpen} title="Show 18+ section">
  {#if step === 'age'}
    <div class="sheet-body">
      <p>This section contains mature content intended for adults aged 18 and older.</p>
      <label class="age-check">
        <input type="checkbox" bind:checked={ageOk} />
        <span>I confirm I am 18 years or older</span>
      </label>
      <div class="acts">
        <button type="button" class="btn btn-primary" disabled={!ageOk} onclick={() => (step = 'pin')}>I am 18 or older</button>
        <button type="button" class="btn btn-ghost" onclick={() => (gateOpen = false)}>Cancel</button>
      </div>
    </div>
  {:else if step === 'pin'}
    <PinPad title="Set a 4-digit PIN" hint="Required once per session to open the 18+ section." onSubmit={pinStep} />
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

<Sheet
  open={confirmAction !== null}
  title={confirmAction === 'search'
    ? 'Clear search history?'
    : confirmAction === 'watch'
      ? 'Clear watch history?'
      : 'Reset settings?'}
  onClose={() => (confirmAction = null)}
>
  <div class="sheet-body">
    {#if confirmAction === 'search'}
      <p>Recent searches across Home, Anime, Drama, Music, Comics, and 18+ will be removed.</p>
      <div class="acts">
        <button type="button" class="btn btn-primary" onclick={clearSearchHistoryNow}>Clear search history</button>
        <button type="button" class="btn btn-ghost" onclick={() => (confirmAction = null)}>Cancel</button>
      </div>
    {:else if confirmAction === 'watch'}
      <p>Continue Watching items and saved playback positions will be removed. Your saved My List stays intact.</p>
      <div class="acts">
        <button type="button" class="btn btn-primary" onclick={clearWatchHistoryNow}>Clear watch history</button>
        <button type="button" class="btn btn-ghost" onclick={() => (confirmAction = null)}>Cancel</button>
      </div>
    {:else if confirmAction === 'reset'}
      <p>Appearance, player gestures, floating mini-player, and Wi-Fi download settings return to their defaults.</p>
      <div class="acts">
        <button type="button" class="btn btn-primary" onclick={resetAllSettingsNow}>Reset settings</button>
        <button type="button" class="btn btn-ghost" onclick={() => (confirmAction = null)}>Cancel</button>
      </div>
    {/if}
  </div>
</Sheet>

<style>
  .settings {
    max-width: 860px;
    margin: 0 auto;
    padding: 0 16px 24px;
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
    font-size: 0.86rem;
    font-weight: 600;
  }
  .card {
    display: grid;
    gap: 12px;
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
    margin: 0;
    font-size: 1.05rem;
    font-weight: 760;
    letter-spacing: -0.01em;
  }
  .sep {
    height: 1px;
    background: var(--divider);
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

  .choice-row {
    display: grid;
    gap: 8px;
  }
  .switch-row,
  .info-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 14px;
  }
  .disabled-row {
    opacity: 0.55;
  }
  .row-copy {
    flex: 1;
    min-width: 0;
    display: grid;
    gap: 2px;
  }
  .row-copy b {
    font-size: 0.94rem;
    font-weight: 700;
    color: var(--text-primary);
  }
  .row-copy small {
    font-size: 0.79rem;
    line-height: 1.38;
    color: var(--text-secondary);
  }
  .row-chips {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }
  .switch {
    position: relative;
    width: 48px;
    height: 28px;
    flex: none;
    padding: 3px;
    border: 0;
    border-radius: 999px;
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
    cursor: pointer;
    transition: background-color 0.18s ease, box-shadow 0.18s ease;
  }
  .switch:disabled {
    cursor: not-allowed;
  }
  .switch.on {
    background: linear-gradient(135deg, var(--accent), var(--accent-end));
    box-shadow: 0 4px 14px -4px var(--accent-glow);
  }
  .switch .knob {
    display: block;
    width: 22px;
    height: 22px;
    border-radius: 50%;
    background: #fff;
    box-shadow: 0 2px 6px rgb(0 0 0 / 0.35);
    transition: transform 0.18s var(--ease-emphasized, ease);
  }
  .switch.on .knob {
    transform: translateX(20px);
  }

  .action-row {
    width: 100%;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 4px 0;
    border: 0;
    background: none;
    color: inherit;
    text-align: left;
    font: inherit;
    cursor: pointer;
  }
  .action-row:hover .row-copy b {
    color: var(--accent);
  }
  .chev {
    flex: none;
    font-size: 1.2rem;
    color: var(--text-secondary);
  }

  .reset-card {
    width: 100%;
    display: flex;
    align-items: center;
    gap: 14px;
    padding: 15px 18px;
    border: 0;
    border-radius: 22px;
    text-align: left;
    font: inherit;
    color: inherit;
    cursor: pointer;
    transition: transform calc(var(--dur-short, 140ms) * var(--dur-scale, 1)) ease;
  }
  .reset-card:hover {
    transform: translateY(-1px);
  }
  .reset-card .row-copy b {
    color: var(--error, var(--accent));
  }
  .reset-ic {
    width: 38px;
    height: 38px;
    flex: none;
    display: grid;
    place-items: center;
    border-radius: 12px;
    font-size: 1.15rem;
    font-weight: 800;
    color: var(--error, var(--accent));
    background: color-mix(in srgb, var(--error, var(--accent)) 14%, transparent);
  }

  .btn.sm {
    min-height: 34px;
    padding: 0 13px;
    font-size: 0.8rem;
  }
  .acts {
    display: flex;
    gap: 10px;
    flex-wrap: wrap;
  }
  .sheet-body {
    display: grid;
    gap: 14px;
  }
  .sheet-body p {
    margin: 0;
    color: var(--text-secondary);
    font-size: 0.9rem;
    line-height: 1.45;
  }
  .age-check {
    display: flex;
    align-items: center;
    gap: 10px;
    cursor: pointer;
    font-weight: 600;
  }
  .age-check input {
    width: 18px;
    height: 18px;
    accent-color: var(--accent);
  }
</style>
