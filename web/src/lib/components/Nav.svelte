<script lang="ts">
  import { page } from '$app/state';
  import type { NavTab } from '$lib/nav';
  import Icon from './Icon.svelte';
  import Logo from './Logo.svelte';

  interface Props {
    tabs: NavTab[];
    spaceLabel?: string | null;
  }
  let { tabs, spaceLabel = null }: Props = $props();

  function isActive(t: NavTab): boolean {
    const url = page.url;
    const [path, query] = t.href.split('?');
    if (query) {
      const want = new URLSearchParams(query).get('cat');
      const cur = url.searchParams.get('cat');
      // First category is active when no ?cat= is present.
      const first = tabs.find((x) => x.href.includes('?cat='));
      return url.pathname === path && (cur === want || (!cur && first?.id === t.id));
    }
    if (t.id === 'exit') return false;
    return t.exact ? url.pathname === path || url.pathname === path + '/' : url.pathname === path || url.pathname.startsWith(path + '/');
  }
</script>

<nav class="nav glass glass-bar" aria-label="Main">
  <!-- Brand only (spaces moved to Me → Spaces). Hidden on phones to give tabs the room. -->
  <div class="brand" aria-hidden={spaceLabel ? undefined : 'true'}>
    <Logo size={34} />
    {#if spaceLabel}<span class="space-name">{spaceLabel}</span>{/if}
  </div>
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
    transition:
      border-radius calc(var(--dur-medium) * var(--dur-scale)) var(--ease-emphasized),
      box-shadow calc(var(--dur-medium) * var(--dur-scale)) var(--ease-standard);
  }
  .brand {
    display: none;
    place-items: center;
    flex: none;
    border: 0;
    background: none;
    padding: 4px;
    border-radius: 14px;
  }
  .brand .space-name {
    display: none;
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
  a {
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
  a:active {
    transform: scale(0.95);
  }
  a .ic {
    display: grid;
    place-items: center;
    width: 50px;
    height: 28px;
    border-radius: 999px;
    transition:
      background calc(var(--dur-medium) * var(--dur-scale)) var(--ease-emphasized),
      color calc(var(--dur-medium) * var(--dur-scale)) var(--ease-standard),
      box-shadow calc(var(--dur-medium) * var(--dur-scale)) var(--ease-standard);
  }
  a:hover:not(.active) {
    color: var(--text-primary);
  }
  a:hover:not(.active) .ic {
    background: color-mix(in srgb, var(--text-primary) 8%, transparent);
  }
  a .lbl {
    max-width: 100%;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  a.active {
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
    :global([data-theme='aura']) a {
      padding: 4px 2px;
      gap: 2px;
    }
    :global([data-theme='aura']) a .ic {
      width: 46px;
      height: 26px;
    }
    /* Beta Spotlight bar & downward light cone on the active capsule tab. */
    :global([data-theme='aura']) a.active::before {
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

  .space-name {
    font-size: 0.65rem;
    font-weight: 700;
    color: var(--text-secondary);
  }

  /* ≥900px: floating Liquid Glass side rail */
  @media (min-width: 900px) {
    .nav {
      left: calc(12px + var(--safe-l));
      right: auto;
      top: calc(12px + var(--safe-t));
      bottom: calc(12px + var(--safe-b));
      width: var(--rail-w);
      height: auto;
      flex-direction: column;
      padding: 16px 6px;
      border-radius: 28px;
    }
    :global([data-theme='aura']) .nav {
      border-radius: 36px;
    }
    .brand {
      display: grid;
      place-items: center;
      justify-items: center;
      gap: 4px;
      width: 100%;
      padding-bottom: 14px;
      margin-bottom: 12px;
      border-bottom: 1px solid var(--divider);
    }
    .brand .space-name {
      display: block;
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
    a {
      max-width: 76px;
      padding: 7px 6px;
      border-radius: 18px;
    }
    :global([data-theme='aura']) a.active::after {
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
