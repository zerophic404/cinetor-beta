<script lang="ts">
  import { base } from '$app/paths';
  import Logo from '$lib/components/Logo.svelte';
  import Sheet from '$lib/components/Sheet.svelte';
  import InstallButton from '$lib/pwa/InstallButton.svelte';
  import Icon from '$lib/components/Icon.svelte';
  import { AVATAR_COLORS, initials } from '$lib/profile/profiles';
  import { profiles } from '$lib/profile/store.svelte';
  import { adult } from '$lib/adult/store.svelte';
  import { meSpaces } from '$lib/nav';
  import { myList } from '$lib/data/library.svelte';
  import type { Space } from '$lib/data/types';

  let editOpen = $state(false);
  let draftName = $state('');
  let draftColor = $state(AVATAR_COLORS[0]);

  const savedCount = $derived(
    myList.ids('video').length + myList.ids('comics').length + myList.ids('music').length
  );
  const adultVisible = $derived(adult.enabled && !profiles.active.kids);
  const spaces = $derived(
    meSpaces(adultVisible).filter((s) => s.space !== 'TV' && (s.space !== 'ADULT' || adultVisible))
  );
  const displayName = $derived(profiles.active.name.trim() || 'Your Cinetor');

  const SPACE_META: Record<string, { title: string; subtitle: string; badge: string }> = {
    MUSIC: { title: 'Music', subtitle: 'FLAC Lossless · Charts · Synced lyrics', badge: 'FLAC' },
    COMICS: { title: 'Comics', subtitle: 'Manga · Manhwa · Manhua', badge: 'READER' },
    ADULT: { title: '18+ Adult', subtitle: 'Adults only · PIN protected', badge: '18+' }
  };

  function metaOf(space: Space, fallbackTitle: string, fallbackSub: string) {
    return SPACE_META[space] ?? { title: fallbackTitle, subtitle: fallbackSub, badge: '' };
  }

  function openEditName() {
    draftName = profiles.active.name;
    draftColor = profiles.active.color || AVATAR_COLORS[0];
    editOpen = true;
  }

  function saveEditName(e: SubmitEvent) {
    e.preventDefault();
    const trimmed = draftName.trim();
    if (!trimmed) return;
    profiles.update(profiles.activeId, { name: trimmed, color: draftColor });
    editOpen = false;
  }

  const links = [
    {
      href: `${base}/app/library`,
      title: 'My List & Library',
      sub: 'Watchlist · watch history · saved tracks & comics',
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
      sub: 'Themes · player · downloads · content & privacy',
      icon: 'M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6zM4 12h2M18 12h2M12 4v2M12 18v2M6.3 6.3l1.4 1.4M16.3 16.3l1.4 1.4M6.3 17.7l1.4-1.4M16.3 7.7l1.4-1.4'
    },
    {
      href: `${base}/app/about`,
      title: 'About & Community',
      sub: 'Version · Mythbrix · Telegram community',
      icon: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 11v6M12 7.5v.5'
    }
  ];
</script>

<svelte:head><title>Me · Cinetor</title></svelte:head>

<div class="me">
  <header class="profile glass">
    <span class="av big" style:background={profiles.active.color}>
      {initials(displayName)}
    </span>
    <div class="who">
      <h1>{displayName}</h1>
      <p>{savedCount} saved in Library</p>
    </div>
    <button type="button" class="btn btn-ghost edit-main" onclick={openEditName}>Edit</button>
  </header>

  {#if spaces.length}
    <section aria-labelledby="spaces-h">
      <div class="sec-head">
        <div class="head-left">
          <span class="bar" aria-hidden="true"></span>
          <h2 id="spaces-h">Spaces</h2>
        </div>
        {#if adultVisible && adult.unlocked}
          <button
            type="button"
            class="chip"
            onclick={() => adult.lock()}
          >
            Lock 18+
          </button>
        {/if}
      </div>
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
  {/if}

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

<Sheet bind:open={editOpen} title="Edit display name">
  <form class="edit-form" onsubmit={saveEditName}>
    <label class="field">
      <span>Display name</span>
      <input
        type="text"
        maxlength="24"
        bind:value={draftName}
        placeholder="Your name"
        required
      />
    </label>
    <div class="field">
      <span>Avatar color</span>
      <div class="color-row" role="radiogroup" aria-label="Avatar color">
        {#each AVATAR_COLORS as col (col)}
          <button
            type="button"
            class="col-dot"
            style:background={col}
            role="radio"
            aria-checked={draftColor === col}
            aria-label="Color {col}"
            onclick={() => (draftColor = col)}
          ></button>
        {/each}
      </div>
    </div>
    <div class="form-acts">
      <button type="submit" class="btn btn-primary" disabled={!draftName.trim()}>Save</button>
      <button type="button" class="btn btn-ghost" onclick={() => (editOpen = false)}>Cancel</button>
    </div>
  </form>
</Sheet>

<style>
  .me {
    max-width: 920px;
    margin: 0 auto;
    padding-bottom: 16px;
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
  .av {
    width: 60px;
    height: 60px;
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
  .sec-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    margin: 22px 16px 12px;
  }
  .head-left {
    display: flex;
    align-items: center;
    gap: 8px;
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
    color: var(--text-primary);
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
    color: var(--text-primary);
  }
  .space small {
    color: var(--text-secondary);
    font-size: 0.78rem;
    line-height: 1.35;
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
    color: var(--text-primary);
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
  .lnk-txt b {
    color: var(--text-primary);
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
    margin: 20px 16px 0;
    display: flex;
    justify-content: center;
  }
  .brand {
    margin: 20px 0 4px;
    display: flex;
    justify-content: center;
    opacity: 0.78;
  }

  .edit-form {
    display: grid;
    gap: 16px;
  }
  .field {
    display: grid;
    gap: 6px;
    font-size: 0.85rem;
    color: var(--text-secondary);
  }
  .field input {
    min-height: 44px;
    border-radius: 12px;
    border: 0;
    padding: 0 14px;
    font: inherit;
    font-size: 0.95rem;
    background: var(--glass-fill);
    color: var(--text-primary);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
  }
  :global([data-theme='pearl']) .field input {
    background: var(--surface-elevated);
    box-shadow: inset 0 0 0 1px rgb(20 20 22 / 0.16);
  }
  .field input:focus-visible {
    outline: 2px solid var(--focus-ring);
    outline-offset: 2px;
  }
  .color-row {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
  }
  .col-dot {
    width: 34px;
    height: 34px;
    border-radius: 50%;
    border: 0;
    cursor: pointer;
    box-shadow: inset 0 0 0 1px rgb(255 255 255 / 0.24);
  }
  .col-dot[aria-checked='true'] {
    box-shadow:
      0 0 0 3px var(--bg, #000),
      0 0 0 5px var(--accent);
  }
  .form-acts {
    display: flex;
    gap: 10px;
  }
</style>
