<script lang="ts">
  import { base } from '$app/paths';
  import { page } from '$app/state';
  import { SPACE_SLUG, type Space } from '$lib/data/types';
  import { searchHrefForSpace, type NavTab } from '$lib/nav';
  import Icon from './Icon.svelte';
  import Logo from './Logo.svelte';

  interface Props {
    tabs: NavTab[];
    space?: Space;
    spaceLabel?: string | null;
  }
  let { tabs, space = 'HOME', spaceLabel = null }: Props = $props();

  function trimSlash(p: string): string {
    return p.length > 1 && p.endsWith('/') ? p.slice(0, -1) : p;
  }

  const searchHref = $derived(searchHrefForSpace(space));

  const searchActive = $derived.by(() => {
    const p = trimSlash(page.url.pathname);
    const rel = p.startsWith(base) ? p.slice(base.length) : p;
    return rel === '/app/search' || /^\/app\/[^/]+\/search$/.test(rel);
  });

  const homeHref = $derived(
    space === 'MUSIC' || space === 'COMICS' || space === 'ADULT'
      ? `${base}/app/${SPACE_SLUG[space]}`
      : `${base}/app`
  );

  function isActive(t: NavTab): boolean {
    if (t.id === 'exit') return false;
    if (searchActive && t.id !== 'search') return false;
    const url = page.url;
    const [rawPath, query] = t.href.split('?');
    const path = trimSlash(rawPath);
    const curPath = trimSlash(url.pathname);
    if (query) {
      const want = new URLSearchParams(query).get('cat');
      const cur = url.searchParams.get('cat');
      const first = tabs.find((x) => x.href.includes('?cat='));
      return curPath === path && (cur === want || (!cur && first?.id === t.id));
    }
    return t.exact ? curPath === path : curPath === path || curPath.startsWith(path + '/');
  }
</script>

<header class="topbar glass glass-bar">
  <div class="top-left">
    <a class="brand-link" href={homeHref} aria-label="Cinetor home">
      <Logo size={26} wordmark />
    </a>
    {#if spaceLabel}
      <span class="space-pill glass-pill">{spaceLabel}</span>
    {/if}
  </div>
  <a
    class="search-btn glass-pill"
    class:active={searchActive}
    href={searchHref}
    aria-label="Search"
    aria-current={searchActive ? 'page' : undefined}
    title="Search"
  >
    <Icon name="search" size={20} />
  </a>
</header>

<nav class="nav glass glass-bar" aria-label="Main">
  <ul>
    {#each tabs as t (t.id)}
      {@const active = isActive(t)}
      <li>
        <a href={t.href} aria-current={active ? 'page' : undefined} class:active>
          <span class="ic"><Icon name={t.icon} size={22} /></span>
          <span class="lbl">{t.label}</span>
        </a>
      </li>
    {/each}
  </ul>
</nav>

<style>
  /* Persistent Cinetor Beta top header bar across every section (mobile + desktop). */
  .topbar {
    position: fixed;
    z-index: 52;
    top: var(--safe-t);
    left: 0;
    right: 0;
    height: var(--header-h, 56px);
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 0 calc(14px + var(--safe-r)) 0 calc(16px + var(--safe-l));
    border-radius: 0;
    box-shadow:
      var(--glass-specular),
      0 8px 26px -14px rgb(0 0 0 / 0.65);
    border-bottom: 1px solid var(--glass-stroke);
  }
  .topbar::before {
    display: none;
  }
  :global([data-theme='matte']) .topbar {
    box-shadow: none;
    border-bottom: 1px solid var(--divider);
  }
  :global([data-theme='pearl']) .topbar {
    box-shadow:
      var(--glass-specular),
      0 6px 20px -12px rgb(28 22 18 / 0.14);
    border-bottom: 1px solid rgb(20 20 22 / 0.1);
  }

  .top-left {
    display: flex;
    align-items: center;
    gap: 10px;
    min-width: 0;
  }
  .brand-link {
    display: inline-flex;
    align-items: center;
    padding: 6px 4px;
    margin-left: -4px;
    border-radius: 10px;
    text-decoration: none;
    color: var(--text-primary);
    transition: transform calc(var(--dur-short) * var(--dur-scale)) var(--ease-emphasized);
  }
  .brand-link:active {
    transform: scale(0.97);
  }
  .space-pill {
    display: inline-flex;
    align-items: center;
    height: 24px;
    padding: 0 10px;
    font-size: 0.72rem;
    font-weight: 700;
    letter-spacing: 0.03em;
    color: var(--text-primary);
    white-space: nowrap;
  }

  .search-btn {
    flex: none;
    width: 40px;
    height: 40px;
    display: grid;
    place-items: center;
    border-radius: 50%;
    color: var(--text-primary);
    text-decoration: none;
    transition:
      transform calc(var(--dur-short) * var(--dur-scale)) var(--ease-emphasized),
      background-color calc(var(--dur-medium) * var(--dur-scale)) var(--ease-standard),
      color calc(var(--dur-medium) * var(--dur-scale)) var(--ease-standard);
  }
  .search-btn:hover:not(.active) {
    background: color-mix(in srgb, var(--text-primary) 12%, var(--glass-fill));
  }
  .search-btn:active {
    transform: scale(0.93);
  }
  .search-btn.active {
    color: var(--on-accent);
    background: linear-gradient(135deg, var(--accent), var(--accent-end));
    box-shadow:
      inset 0 1px 0 rgb(255 255 255 / 0.28),
      0 6px 18px -6px var(--accent-glow);
  }
  :global([data-theme='matte']) .search-btn.active {
    background: var(--accent);
    box-shadow: none;
  }

  /* Bottom / side main navigation */
  .nav {
    position: fixed;
    z-index: 50;
    left: calc(12px + var(--safe-l));
    right: calc(12px + var(--safe-r));
    bottom: calc(10px + var(--safe-b));
    height: var(--nav-h);
    display: flex;
    align-items: center;
    padding: 0 6px;
    border-radius: 26px;
  }
  ul {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex: 1;
    min-width: 0;
    justify-content: space-around;
    align-items: center;
  }
  li {
    flex: 1;
    min-width: 0;
    display: flex;
    justify-content: center;
  }
  a:not(.brand-link):not(.search-btn) {
    position: relative;
    display: grid;
    justify-items: center;
    gap: 3px;
    width: 100%;
    max-width: 74px;
    padding: 5px 4px;
    border-radius: 16px;
    color: var(--text-secondary);
    text-decoration: none;
    font-size: 0.7rem;
    font-weight: 600;
    letter-spacing: 0.01em;
    transition:
      color calc(var(--dur-medium) * var(--dur-scale)) var(--ease-standard),
      transform calc(var(--dur-short) * var(--dur-scale)) var(--ease-emphasized);
  }
  a:not(.brand-link):not(.search-btn):active {
    transform: scale(0.95);
  }
  a .ic {
    display: grid;
    place-items: center;
    width: 50px;
    height: 28px;
    border-radius: 999px;
    transition:
      background-color calc(var(--dur-medium) * var(--dur-scale)) var(--ease-emphasized),
      color calc(var(--dur-medium) * var(--dur-scale)) var(--ease-standard);
  }
  a:not(.brand-link):not(.search-btn):hover:not(.active) {
    color: var(--text-primary);
  }
  a:not(.brand-link):not(.search-btn):hover:not(.active) .ic {
    background: color-mix(in srgb, var(--text-primary) 8%, transparent);
  }
  a .lbl {
    max-width: 100%;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  a.active:not(.search-btn) {
    color: var(--text-primary);
    font-weight: 700;
  }
  a.active .ic {
    color: var(--on-accent);
    background: linear-gradient(
      135deg,
      color-mix(in srgb, var(--accent) 92%, transparent),
      color-mix(in srgb, var(--accent-end) 84%, transparent)
    );
    box-shadow:
      inset 0 1px 0 rgb(255 255 255 / 0.28),
      0 6px 18px -6px var(--accent-glow);
  }

  /* Matte: crisp solid active indicator with zero specular glare. */
  :global([data-theme='matte']) a.active .ic {
    background: var(--accent);
    box-shadow: none;
  }

  /* Material: rounder tonal dock and wider pill indicator. */
  :global([data-theme='material']) .nav {
    border-radius: 28px;
  }
  :global([data-theme='material']) a .ic {
    width: 54px;
    height: 30px;
  }

  /* Pearl: crisp daylight shadow under active accent pill. */
  :global([data-theme='pearl']) a.active .ic {
    background: linear-gradient(135deg, var(--accent), var(--accent-end));
    box-shadow:
      inset 0 1px 0 rgb(255 255 255 / 0.32),
      0 6px 16px -6px color-mix(in srgb, var(--accent) 44%, transparent);
  }

  /* Cinetor Beta floating Aura capsule bottom bar (<900px). */
  @media (max-width: 899.98px) {
    :global([data-theme='aura']) .nav {
      left: 50%;
      right: auto;
      transform: translateX(-50%);
      width: min(416px, calc(100vw - 28px - var(--safe-l) - var(--safe-r)));
      bottom: calc(14px + var(--safe-b));
      height: 60px;
      padding: 0 10px;
      border-radius: 999px;
      background:
        var(--glass-sheen),
        radial-gradient(110% 140% at 50% 0%, color-mix(in srgb, var(--tint) 24%, transparent), transparent 68%),
        color-mix(in srgb, var(--surface) 76%, transparent);
      box-shadow:
        inset 0 1px 0 rgb(255 255 255 / 0.28),
        0 20px 44px -12px rgb(0 0 0 / 0.82),
        0 6px 22px -8px color-mix(in srgb, var(--tint) 36%, transparent);
    }
    :global([data-theme='aura'][data-blur='none']) .nav {
      background:
        radial-gradient(110% 140% at 50% 0%, color-mix(in srgb, var(--tint) 20%, transparent), transparent 68%),
        color-mix(in srgb, var(--surface) 92%, transparent);
    }
    :global([data-theme='aura']) a:not(.brand-link):not(.search-btn) {
      padding: 4px 2px;
      gap: 2px;
    }
    :global([data-theme='aura']) a .ic {
      width: 46px;
      height: 26px;
    }
    :global([data-theme='aura']) a.active:not(.search-btn)::before {
      content: '';
      position: absolute;
      top: 0;
      left: 50%;
      width: 22px;
      height: 2px;
      transform: translateX(-50%);
      border-radius: 99px;
      background: var(--focus-ring);
      box-shadow: 0 4px 12px 1px color-mix(in srgb, var(--focus-ring) 55%, transparent);
    }
  }

  /* ≥900px: floating Liquid Glass side rail below the persistent top header */
  @media (min-width: 900px) {
    .topbar {
      padding: 0 calc(20px + var(--safe-r)) 0 calc(20px + var(--safe-l));
    }
    .nav {
      left: calc(12px + var(--safe-l));
      right: auto;
      top: calc(var(--header-h, 56px) + 12px + var(--safe-t));
      bottom: calc(12px + var(--safe-b));
      width: var(--rail-w);
      height: auto;
      flex-direction: column;
      justify-content: flex-start;
      padding: 14px 6px;
      border-radius: 28px;
    }
    :global([data-theme='aura']) .nav {
      border-radius: 36px;
    }
    ul {
      flex-direction: column;
      justify-content: flex-start;
      gap: 8px;
      flex: none;
      width: 100%;
    }
    li {
      flex: none;
      width: 100%;
    }
    a:not(.brand-link):not(.search-btn) {
      max-width: 76px;
      padding: 7px 6px;
      border-radius: 18px;
    }
    :global([data-theme='aura']) a.active:not(.search-btn)::after {
      content: '';
      position: absolute;
      left: 0;
      top: 50%;
      width: 2.5px;
      height: 20px;
      transform: translateY(-50%);
      border-radius: 99px;
      background: var(--focus-ring);
      box-shadow: 2px 0 10px color-mix(in srgb, var(--focus-ring) 50%, transparent);
    }
  }
</style>
