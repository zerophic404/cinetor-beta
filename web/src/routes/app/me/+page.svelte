<script lang="ts">
  import { base } from '$app/paths';
  import Logo from '$lib/components/Logo.svelte';
  import Sheet from '$lib/components/Sheet.svelte';
  import InstallButton from '$lib/pwa/InstallButton.svelte';
  import PinPad from '$lib/adult/PinPad.svelte';
  import { pinHash } from '$lib/adult/gate';
  import ProfileEditor from '$lib/profile/ProfileEditor.svelte';
  import { MAX_PROFILES, initials, type Profile } from '$lib/profile/profiles';
  import { profiles } from '$lib/profile/store.svelte';
  import Icon from '$lib/components/Icon.svelte';
  import { adult } from '$lib/adult/store.svelte';
  import { meSpaces } from '$lib/nav';
  import { myList } from '$lib/data/library.svelte';
  import type { Space } from '$lib/data/types';

  let editing = $state<Profile | null>(null);
  let editorOpen = $state(false);
  let unlockFor = $state<Profile | null>(null);
  let unlockOpen = $state(false);

  // Inline 18+ opt-in & PIN management sheets right in Profile / Me
  let gateOpen = $state(false);
  let gateStep = $state<'age' | 'pin' | 'confirm'>('age');
  let ageOk = $state(false);
  let firstPin = '';
  let changePinOpen = $state(false);
  let changePinStep = $state<'old' | 'new'>('old');
  let currentPin = '';
  let statusNotice = $state('');

  const savedCount = $derived(
    myList.ids('video').length + myList.ids('comics').length + myList.ids('music').length
  );
  const adultVisible = $derived(adult.enabled && !profiles.active.kids);
  const spaces = $derived(meSpaces(adultVisible));

  const SPACE_META: Record<string, { title: string; subtitle: string; badge: string }> = {
    TV: { title: 'Live TV & Sports', subtitle: 'Channels · EPG · Live matches', badge: 'LIVE' },
    MUSIC: { title: 'Music', subtitle: 'FLAC Lossless · Charts · Synced lyrics', badge: 'FLAC' },
    COMICS: { title: 'Comics', subtitle: 'Manga · Manhwa · Manhua', badge: 'READER' },
    ADULT: { title: '18+ Adult', subtitle: 'Adults only · PIN protected', badge: '18+' }
  };

  function metaOf(space: Space, fallbackTitle: string, fallbackSub: string) {
    return SPACE_META[space] ?? { title: fallbackTitle, subtitle: fallbackSub, badge: '' };
  }

  function pick(p: Profile) {
    if (p.id === profiles.activeId) return;
    if (p.pinHash) {
      unlockFor = p;
      unlockOpen = true;
    } else profiles.switchTo(p.id);
  }

  function edit(p: Profile | null) {
    editing = p;
    editorOpen = true;
  }

  async function tryProfilePin(pin: string): Promise<true | string> {
    const p = unlockFor;
    if (!p) return 'No profile';
    if ((await pinHash(p.id, pin)) !== p.pinHash) return 'Incorrect PIN';
    unlockOpen = false;
    profiles.switchTo(p.id);
    return true;
  }

  function toggleAdultSpace() {
    if (!adult.canEnable) return;
    if (adult.enabled) {
      adult.disable();
      statusNotice = '18+ Adult space hidden from Spaces.';
      return;
    }
    gateStep = 'age';
    ageOk = false;
    firstPin = '';
    gateOpen = true;
  }

  function onNewAdultPin(pin: string): true | string {
    firstPin = pin;
    gateStep = 'confirm';
    return true;
  }

  async function onConfirmAdultPin(pin: string): Promise<true | string> {
    if (pin !== firstPin) {
      gateStep = 'pin';
      return "PINs didn't match — enter a new PIN";
    }
    const r = await adult.enable(ageOk, pin);
    if (!r.ok) return r.error;
    gateOpen = false;
    statusNotice = '18+ Adult space enabled and unlocked for this session.';
    return true;
  }

  function openChangeAdultPin() {
    changePinStep = 'old';
    currentPin = '';
    changePinOpen = true;
  }

  function onOldAdultPin(pin: string): true | string {
    currentPin = pin;
    changePinStep = 'new';
    return true;
  }

  async function onSaveChangedPin(pin: string): Promise<true | string> {
    const r = await adult.changePin(currentPin, pin);
    if (!r.ok) {
      changePinStep = 'old';
      return r.error;
    }
    changePinOpen = false;
    statusNotice = '18+ PIN updated.';
    return true;
  }

  const links = [
    {
      href: `${base}/app/library`,
      title: 'My List & Library',
      sub: 'Watchlist · saved tracks & albums · bookmarked comics',
      icon: 'M5 4h4v16H5zM10 4h4v16h-4zM15.5 4.5l3.8 1-3.6 15-3.8-1z'
    },
    {
      href: `${base}/app/downloads`,
      title: 'Downloads',
      sub: 'Offline movies, episodes & lossless music · storage',
      icon: 'M12 4v11m0 0l-4.5-4.5M12 15l4.5-4.5M5 19.5h14'
    },
    {
      href: `${base}/app/settings`,
      title: 'App Settings',
      sub: 'Themes · Liquid Glass quality · player · subtitles · privacy',
      icon: 'M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6zM4 12h2M18 12h2M12 4v2M12 18v2M6.3 6.3l1.4 1.4M16.3 16.3l1.4 1.4M6.3 17.7l1.4-1.4M16.3 7.7l1.4-1.4'
    },
    {
      href: `${base}/app/about`,
      title: 'About & Community',
      sub: 'Version · Telegram channel · credits',
      icon: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 11v6M12 7.5v.5'
    }
  ];
</script>

<svelte:head><title>Me · Cinetor</title></svelte:head>

<div class="me">
  <header class="profile glass">
    <span class="av big" style:background={profiles.active.color}>
      {initials(profiles.active.name)}
      {#if profiles.active.pinHash}
        <i class="lock-dot" aria-hidden="true">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
            <rect x="5" y="11" width="14" height="10" rx="2.5" />
            <path d="M8 11V7a4 4 0 0 1 8 0v4" />
          </svg>
        </i>
      {/if}
    </span>
    <div class="who">
      <div class="title-row">
        <h1>{profiles.active.name}</h1>
        {#if profiles.active.kids}<span class="kid">Kids</span>{/if}
      </div>
      <p>
        {savedCount} in Library · {profiles.list.length}/{MAX_PROFILES} profiles on this device
      </p>
    </div>
    <button type="button" class="btn btn-ghost edit-main" onclick={() => edit(profiles.active)}>Edit</button>
  </header>

  {#if statusNotice}
    <p class="notice glass" role="status">{statusNotice}</p>
  {/if}

  <section aria-labelledby="prof-h">
    <div class="sec-head">
      <span class="bar" aria-hidden="true"></span>
      <h2 id="prof-h">Who's watching?</h2>
    </div>
    <ul class="picker">
      {#each profiles.list as p (p.id)}
        <li>
          <button
            type="button"
            class="pp"
            class:current={p.id === profiles.activeId}
            onclick={() => pick(p)}
            aria-current={p.id === profiles.activeId ? 'true' : undefined}
            aria-label="Switch to {p.name}{p.kids ? ' (Kids)' : ''}{p.pinHash ? ', PIN protected' : ''}"
          >
            <span class="av" style:background={p.color}>
              {initials(p.name)}
              {#if p.pinHash}
                <i class="lock-dot" aria-hidden="true">
                  <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
                    <rect x="5" y="11" width="14" height="10" rx="2.5" />
                    <path d="M8 11V7a4 4 0 0 1 8 0v4" />
                  </svg>
                </i>
              {/if}
            </span>
            <span class="nm">{p.name}</span>
            {#if p.kids}<span class="kid">Kids</span>{/if}
          </button>
          {#if p.id !== profiles.activeId}
            <button type="button" class="mini" onclick={() => edit(p)} aria-label="Edit {p.name}">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z" />
              </svg>
            </button>
          {/if}
        </li>
      {/each}
      {#if profiles.canCreate}
        <li>
          <button type="button" class="pp add" onclick={() => edit(null)} aria-label="Add profile">
            <span class="av">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" aria-hidden="true">
                <path d="M12 5v14M5 12h14" />
              </svg>
            </span>
            <span class="nm">Add</span>
          </button>
        </li>
      {/if}
    </ul>
  </section>

  <section aria-labelledby="spaces-h">
    <div class="sec-head">
      <span class="bar" aria-hidden="true"></span>
      <h2 id="spaces-h">Spaces</h2>
    </div>
    <p class="hint">Dedicated worlds with their own navigation and search — isolated from Home and global history.</p>
    <ul class="spaces">
      {#each spaces as e (e.space)}
        {@const m = metaOf(e.space, e.label, e.blurb)}
        <li>
          <a class="space glass" class:adult-card={e.space === 'ADULT'} href={e.href} data-space={e.space}>
            <div class="space-top">
              <span class="ic"><Icon name={e.icon} size={22} /></span>
              <div class="badges">
                {#if m.badge}
                  <span class="space-pill" class:danger={e.space === 'ADULT'}>{m.badge}</span>
                {/if}
                {#if e.space === 'ADULT'}
                  <span class="pin-ic" aria-label="PIN protected">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                      <rect x="5" y="11" width="14" height="10" rx="2.5" />
                      <path d="M8 11V7a4 4 0 0 1 8 0v4" />
                    </svg>
                  </span>
                {/if}
              </div>
            </div>
            <div class="txt">
              <b>{m.title}</b>
              <small>{m.subtitle}</small>
            </div>
          </a>
        </li>
      {/each}
    </ul>
  </section>

  <section aria-labelledby="prefs-h">
    <div class="sec-head">
      <span class="bar" aria-hidden="true"></span>
      <h2 id="prefs-h">Profile preferences</h2>
    </div>
    <div class="prefs glass">
      {#if !adult.canEnable}
        <div class="pref-row">
          <span class="pref-ic">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M12 3l8 3v6c0 4.5-3.4 8.3-8 9-4.6-.7-8-4.5-8-9V6z" />
              <path d="m9 12 2 2 4-4" />
            </svg>
          </span>
          <div class="pref-txt">
            <b>Kids profile protection</b>
            <small>18+ Adult space is unavailable while a Kids profile is active.</small>
          </div>
          <button type="button" class="btn btn-ghost sm" onclick={() => edit(profiles.active)}>Manage</button>
        </div>
      {:else}
        <div class="pref-row">
          <span class="pref-ic" class:active={adult.enabled}>
            <Icon name="adult" size={20} />
          </span>
          <div class="pref-txt">
            <b>18+ Adult Space</b>
            <small>
              {#if adult.enabled}
                {adult.unlocked ? 'Unlocked for this session · Relocks after 5 min idle' : 'Enabled · Protected by 4-digit session PIN'}
              {:else}
                Show PIN-protected 18+ space in Me (18+ age confirmation required)
              {/if}
            </small>
          </div>
          <button
            type="button"
            class="switch"
            class:on={adult.enabled}
            role="switch"
            aria-checked={adult.enabled}
            aria-label="Enable 18+ Adult space"
            onclick={toggleAdultSpace}
          >
            <span class="knob"></span>
          </button>
        </div>
        {#if adult.enabled}
          <div class="pref-actions">
            <a class="chip" href="{base}/app/adult">Open 18+ Space</a>
            <button type="button" class="chip" onclick={openChangeAdultPin}>Change PIN</button>
            {#if adult.unlocked}
              <button type="button" class="chip" onclick={() => { adult.lock(); statusNotice = '18+ Adult space locked.'; }}>Lock now</button>
            {/if}
          </div>
        {/if}
      {/if}
    </div>
  </section>

  <nav class="links" aria-label="Me">
    {#each links as l (l.href)}
      <a class="lnk glass" href={l.href}>
        <span class="lnk-ic">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d={l.icon} /></svg>
        </span>
        <span class="lnk-txt"><b>{l.title}</b><small>{l.sub}</small></span>
        <svg class="chev" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="m9 6 6 6-6 6" />
        </svg>
      </a>
    {/each}
  </nav>

  <div class="install"><InstallButton label="Install Cinetor app" /></div>
  <p class="brand"><Logo size={28} wordmark /></p>
</div>

<Sheet bind:open={editorOpen} title={editing ? `Edit ${editing.name}` : 'New profile'}>
  <ProfileEditor profile={editing} onDone={() => (editorOpen = false)} />
</Sheet>

<Sheet bind:open={unlockOpen} title={unlockFor ? `Switch to ${unlockFor.name}` : 'Profile PIN'}>
  <PinPad title="Profile PIN" hint="Enter the 4-digit PIN for this profile." onSubmit={tryProfilePin} />
</Sheet>

<Sheet bind:open={gateOpen} title="Enable 18+ Adult Space">
  {#if gateStep === 'age'}
    <div class="age-sheet">
      <p>This space contains adult content restricted to viewers aged 18 and older. It stays isolated from Home, global search, and Continue Watching.</p>
      <label class="age-toggle">
        <input type="checkbox" bind:checked={ageOk} />
        <span>I confirm I am 18 years or older</span>
      </label>
      <div class="age-actions">
        <button type="button" class="btn btn-primary" disabled={!ageOk} onclick={() => (gateStep = 'pin')}>Continue</button>
        <button type="button" class="btn btn-ghost" onclick={() => (gateOpen = false)}>Cancel</button>
      </div>
    </div>
  {:else if gateStep === 'pin'}
    <PinPad title="Set a 4-digit PIN" hint="Required once per session; auto-locks after 5 minutes idle." onSubmit={onNewAdultPin} />
  {:else}
    <PinPad title="Confirm PIN" hint="Re-enter the same 4 digits to enable 18+." onSubmit={onConfirmAdultPin} />
  {/if}
</Sheet>

<Sheet bind:open={changePinOpen} title="Change 18+ PIN">
  {#if changePinStep === 'old'}
    <PinPad title="Current PIN" hint="Verify your existing 4-digit PIN." onSubmit={onOldAdultPin} />
  {:else}
    <PinPad title="New PIN" hint="Choose your new 4-digit PIN." onSubmit={onSaveChangedPin} />
  {/if}
</Sheet>

<style>
  .me {
    max-width: 920px;
    margin: 0 auto;
    padding-bottom: 12px;
  }
  .profile {
    display: flex;
    gap: 16px;
    align-items: center;
    margin: 0 16px;
    padding: 18px 20px;
    border-radius: 24px;
  }
  .who {
    flex: 1;
    min-width: 0;
  }
  .title-row {
    display: flex;
    align-items: center;
    gap: 8px;
    min-width: 0;
  }
  .who h1 {
    margin: 0;
    font-size: 1.38rem;
    font-weight: 800;
    letter-spacing: -0.015em;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .who p {
    margin: 3px 0 0;
    color: var(--text-secondary);
    font-size: 0.84rem;
  }
  .edit-main {
    min-height: 38px;
    padding: 0 16px;
    font-size: 0.86rem;
  }
  .notice {
    margin: 12px 16px 0;
    padding: 10px 16px;
    border-radius: 16px;
    font-size: 0.85rem;
    font-weight: 600;
    color: var(--text-primary);
  }
  .sec-head {
    display: flex;
    align-items: center;
    gap: 8px;
    margin: 22px 16px 10px;
  }
  .sec-head .bar {
    width: 3px;
    height: 16px;
    border-radius: 99px;
    background: var(--accent);
    box-shadow: 0 0 10px var(--accent-glow);
  }
  .sec-head h2 {
    margin: 0;
    font-size: 1.05rem;
    font-weight: 780;
    letter-spacing: -0.01em;
  }
  .av {
    position: relative;
    width: 62px;
    height: 62px;
    display: grid;
    place-items: center;
    border-radius: 50%;
    font-weight: 800;
    font-size: 1.25rem;
    color: #fff;
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
    flex: none;
  }
  .av.big {
    width: 60px;
    height: 60px;
  }
  .lock-dot {
    position: absolute;
    right: -2px;
    bottom: -2px;
    width: 22px;
    height: 22px;
    display: grid;
    place-items: center;
    border-radius: 50%;
    background: var(--surface);
    color: var(--text-primary);
    box-shadow: 0 0 0 2px var(--bg);
  }
  .picker {
    list-style: none;
    margin: 0;
    padding: 0 16px;
    display: flex;
    flex-wrap: wrap;
    gap: 12px;
  }
  .picker li {
    position: relative;
  }
  .pp {
    display: grid;
    justify-items: center;
    gap: 6px;
    width: 84px;
    border: 0;
    background: none;
    cursor: pointer;
    padding: 6px;
    border-radius: 18px;
    transition: transform 0.14s ease;
  }
  .pp:hover {
    transform: translateY(-1px);
  }
  .pp.current .av {
    box-shadow:
      0 0 0 3px var(--bg),
      0 0 0 5px var(--accent);
  }
  .pp.add .av {
    color: var(--text-secondary);
  }
  .nm {
    font-size: 0.82rem;
    font-weight: 650;
    max-width: 80px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .kid {
    font-size: 0.65rem;
    font-weight: 800;
    letter-spacing: 0.03em;
    padding: 2px 7px;
    border-radius: 999px;
    background: color-mix(in srgb, var(--text-primary) 14%, var(--glass-fill));
    color: var(--text-primary);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
  }
  .mini {
    position: absolute;
    top: 2px;
    right: 2px;
    width: 26px;
    height: 26px;
    display: grid;
    place-items: center;
    border: 0;
    border-radius: 50%;
    background: var(--surface-elevated, var(--glass-fill));
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
    color: var(--text-secondary);
    cursor: pointer;
  }
  .mini:hover {
    color: var(--text-primary);
  }
  .hint {
    margin: -4px 16px 12px;
    color: var(--text-secondary);
    font-size: 0.83rem;
    line-height: 1.4;
  }
  .spaces {
    list-style: none;
    margin: 0;
    padding: 0 16px;
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 12px;
  }
  @media (max-width: 380px) {
    .spaces {
      grid-template-columns: 1fr;
    }
  }
  .space {
    display: flex;
    flex-direction: column;
    justify-content: space-between;
    gap: 14px;
    padding: 16px;
    min-height: 108px;
    text-decoration: none;
    border-radius: 22px;
    transition:
      transform calc(var(--dur-short, 140ms) * var(--dur-scale, 1)) var(--ease-emphasized, ease),
      box-shadow calc(var(--dur-medium, 200ms) * var(--dur-scale, 1)) var(--ease-standard, ease);
  }
  .space:hover,
  .space:focus-visible {
    transform: translateY(-2px);
    box-shadow:
      0 14px 32px -14px var(--accent-glow),
      var(--glass-shadow);
  }
  .space:focus-visible {
    outline: 2px solid var(--accent);
    outline-offset: 2px;
  }
  .space.adult-card {
    background:
      linear-gradient(155deg, color-mix(in srgb, var(--accent) 12%, transparent), transparent 60%),
      var(--glass-fill);
  }
  .space-top {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
  }
  .space .ic {
    display: grid;
    place-items: center;
    width: 42px;
    height: 42px;
    flex: none;
    border-radius: 14px;
    color: var(--on-accent);
    background: linear-gradient(135deg, var(--accent), var(--accent-end));
    box-shadow: 0 8px 20px -8px var(--accent-glow);
  }
  .badges {
    display: flex;
    align-items: center;
    gap: 6px;
  }
  .space-pill {
    padding: 3px 8px;
    border-radius: 999px;
    font-size: 0.66rem;
    font-weight: 800;
    letter-spacing: 0.06em;
    color: var(--text-secondary);
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
  }
  .space-pill.danger {
    color: var(--on-accent);
    background: linear-gradient(135deg, var(--accent), var(--accent-end));
    box-shadow: none;
  }
  .pin-ic {
    width: 26px;
    height: 26px;
    display: grid;
    place-items: center;
    border-radius: 50%;
    color: var(--text-secondary);
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
  }
  .space .txt {
    display: grid;
    gap: 3px;
    min-width: 0;
  }
  .space .txt b {
    font-size: 0.98rem;
    font-weight: 760;
    letter-spacing: -0.01em;
  }
  .space small {
    color: var(--text-secondary);
    font-size: 0.78rem;
    line-height: 1.35;
  }
  .prefs {
    margin: 0 16px;
    padding: 14px 16px;
    border-radius: 22px;
    display: grid;
    gap: 12px;
  }
  .pref-row {
    display: flex;
    align-items: center;
    gap: 12px;
  }
  .pref-ic {
    width: 40px;
    height: 40px;
    flex: none;
    display: grid;
    place-items: center;
    border-radius: 13px;
    color: var(--text-secondary);
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
  }
  .pref-ic.active {
    color: var(--accent);
    background: color-mix(in srgb, var(--accent) 16%, var(--glass-fill));
    box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--accent) 38%, var(--glass-stroke));
  }
  .pref-txt {
    flex: 1;
    min-width: 0;
    display: grid;
    gap: 2px;
  }
  .pref-txt b {
    font-size: 0.94rem;
    font-weight: 730;
  }
  .pref-txt small {
    color: var(--text-secondary);
    font-size: 0.78rem;
    line-height: 1.35;
  }
  .btn.sm {
    min-height: 34px;
    padding: 0 14px;
    font-size: 0.8rem;
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
  .pref-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    padding-top: 4px;
    border-top: 1px solid var(--divider);
  }
  .pref-actions .chip {
    min-height: 32px;
    padding: 0 13px;
    font-size: 0.8rem;
  }
  .links {
    display: grid;
    gap: 10px;
    margin: 20px 16px 0;
  }
  .lnk {
    display: flex;
    align-items: center;
    gap: 14px;
    padding: 14px 16px;
    border-radius: 20px;
    text-decoration: none;
    transition: transform calc(var(--dur-short, 140ms) * var(--dur-scale, 1)) ease;
  }
  .lnk:hover,
  .lnk:focus-visible {
    transform: translateY(-1px);
  }
  .lnk-ic {
    width: 38px;
    height: 38px;
    flex: none;
    display: grid;
    place-items: center;
    border-radius: 12px;
    color: var(--text-primary);
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
  }
  .lnk-txt {
    flex: 1;
    min-width: 0;
    display: grid;
    gap: 2px;
  }
  .lnk small {
    color: var(--text-secondary);
    font-size: 0.79rem;
  }
  .chev {
    flex: none;
    color: var(--text-secondary);
  }
  .install {
    margin: 18px 16px 0;
  }
  .brand {
    display: flex;
    justify-content: center;
    margin-top: 26px;
    opacity: 0.85;
  }
  .age-sheet {
    display: grid;
    gap: 14px;
  }
  .age-sheet p {
    margin: 0;
    color: var(--text-secondary);
    font-size: 0.9rem;
    line-height: 1.45;
  }
  .age-toggle {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 12px 14px;
    border-radius: 14px;
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
    font-weight: 650;
    cursor: pointer;
  }
  .age-toggle input {
    width: 20px;
    height: 20px;
    accent-color: var(--accent);
  }
  .age-actions {
    display: flex;
    gap: 10px;
    flex-wrap: wrap;
  }
</style>
