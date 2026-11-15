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

  let editing = $state<Profile | null>(null);
  let editorOpen = $state(false);
  let unlockFor = $state<Profile | null>(null);
  let unlockOpen = $state(false);

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
  async function tryPin(pin: string): Promise<true | string> {
    const p = unlockFor;
    if (!p) return 'No profile';
    if ((await pinHash(p.id, pin)) !== p.pinHash) return 'Wrong PIN';
    unlockOpen = false;
    profiles.switchTo(p.id);
    return true;
  }

  const links = [
    { href: `${base}/app/library`, title: 'Library', sub: 'Watchlist · history · saved albums & comics', icon: 'M5 4h4v16H5zM10 4h4v16h-4zM15.5 4.5l3.8 1 -3.6 15 -3.8-1z' },
    { href: `${base}/app/downloads`, title: 'Downloads', sub: 'Offline movies & episodes · storage', icon: 'M12 4v11m0 0l-4.5-4.5M12 15l4.5-4.5M5 19.5h14' },
    { href: `${base}/app/settings`, title: 'Settings', sub: 'Appearance · playback · subtitles · data · content', icon: 'M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6zM4 12h2M18 12h2M12 4v2M12 18v2M6.3 6.3l1.4 1.4M16.3 16.3l1.4 1.4M6.3 17.7l1.4-1.4M16.3 7.7l1.4-1.4' },
    { href: `${base}/app/about`, title: 'About', sub: 'Version · community · credits', icon: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 11v6M12 7.5v.5' }
  ];
</script>

<svelte:head><title>Me · Cinetor</title></svelte:head>

<div class="me">
  <header class="profile glass">
    <span class="av big" style:background={profiles.active.color}>{initials(profiles.active.name)}</span>
    <div class="who">
      <h1>{profiles.active.name}</h1>
      <p>{profiles.active.kids ? 'Kids profile' : 'Profile'} · {profiles.list.length}/{MAX_PROFILES} on this device</p>
    </div>
    <button class="btn btn-ghost" onclick={() => edit(profiles.active)}>Edit</button>
  </header>

  <section aria-labelledby="prof-h">
    <h2 id="prof-h" class="section-title">Who's watching?</h2>
    <ul class="picker">
      {#each profiles.list as p (p.id)}
        <li>
          <button class="pp" class:current={p.id === profiles.activeId} onclick={() => pick(p)} aria-current={p.id === profiles.activeId ? 'true' : undefined} aria-label="Switch to {p.name}{p.kids ? ' (Kids)' : ''}{p.pinHash ? ', PIN protected' : ''}">
            <span class="av" style:background={p.color}>{initials(p.name)}{#if p.pinHash}<i aria-hidden="true">🔒</i>{/if}</span>
            <span class="nm">{p.name}</span>
            {#if p.kids}<span class="kid">Kids</span>{/if}
          </button>
          {#if p.id !== profiles.activeId}<button class="mini" onclick={() => edit(p)} aria-label="Edit {p.name}">✎</button>{/if}
        </li>
      {/each}
      {#if profiles.canCreate}
        <li>
          <button class="pp add" onclick={() => edit(null)} aria-label="Add profile">
            <span class="av">+</span>
            <span class="nm">Add</span>
          </button>
        </li>
      {/if}
    </ul>
  </section>

  <nav class="links" aria-label="Me">
    {#each links as l (l.href)}
      <a class="lnk glass" href={l.href}>
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d={l.icon} /></svg>
        <span><b>{l.title}</b><small>{l.sub}</small></span>
        <span aria-hidden="true">›</span>
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
  <PinPad title="Profile PIN" onSubmit={tryPin} />
</Sheet>

<style>
  .me {
    max-width: 920px;
  }
  .profile {
    display: flex;
    gap: 16px;
    align-items: center;
    margin: 0 16px;
    padding: 18px;
  }
  .who {
    flex: 1;
    min-width: 0;
  }
  .who h1 {
    margin: 0;
    font-size: 1.4rem;
  }
  .who p {
    margin: 2px 0 0;
    color: var(--text-secondary);
    font-size: 0.85rem;
  }
  .av {
    position: relative;
    width: 64px;
    height: 64px;
    display: grid;
    place-items: center;
    border-radius: 50%;
    font-weight: 800;
    font-size: 1.3rem;
    color: #fff;
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
  }
  .av.big {
    width: 60px;
    height: 60px;
  }
  .av i {
    position: absolute;
    right: -2px;
    bottom: -2px;
    font-size: 0.8rem;
    font-style: normal;
  }
  .picker {
    list-style: none;
    margin: 0;
    padding: 0 16px;
    display: flex;
    flex-wrap: wrap;
    gap: 14px;
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
  }
  .pp.current .av {
    box-shadow: 0 0 0 3px var(--bg, #000), 0 0 0 5px var(--accent);
  }
  .pp.add .av {
    font-size: 1.8rem;
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
    font-size: 0.62rem;
    font-weight: 800;
    padding: 1px 6px;
    border-radius: 6px;
    background: #17c964;
    color: #000;
  }
  .mini {
    position: absolute;
    top: 0;
    right: 0;
    width: 26px;
    height: 26px;
    border: 0;
    border-radius: 50%;
    background: var(--glass-fill);
    cursor: pointer;
    font-size: 0.75rem;
  }
  .links {
    display: grid;
    gap: 10px;
    margin: 18px 16px 0;
  }
  .lnk {
    display: flex;
    align-items: center;
    gap: 14px;
    padding: 14px 18px;
    text-decoration: none;
  }
  .lnk span:nth-child(2) {
    flex: 1;
    display: grid;
  }
  .lnk small {
    color: var(--text-secondary);
    font-size: 0.8rem;
  }
  .install {
    margin: 18px 16px 0;
  }
  .brand {
    display: flex;
    justify-content: center;
    margin-top: 26px;
    opacity: 0.8;
  }
</style>
