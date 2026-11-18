<script lang="ts">
  import Logo from '$lib/components/Logo.svelte';
  import { ABOUT } from '$lib/about';
</script>

<svelte:head><title>About · Cinetor</title></svelte:head>

<section class="about">
  <div class="studio-card glass">
    <div class="mark" aria-hidden="true">
      <span class="halo"></span>
      <span class="logo-anim"><Logo size={96} /></span>
    </div>

    <h1>{ABOUT.name}</h1>
    <p class="ver">Version {ABOUT.version}</p>

    <div class="rule" aria-hidden="true"></div>

    <p class="dev">{ABOUT.developedBy}</p>
    <p class="rights">{ABOUT.rightsReserved}</p>
    <p class="thanks">
      {ABOUT.thanksPrefix}<a href={ABOUT.thanks.href} target={ABOUT.thanks.target} rel={ABOUT.thanks.rel}>{ABOUT.thanks.label}</a>
    </p>

    <div class="links">
      {#each ABOUT.community as l (l.href)}
        <a class="comm-link" href={l.href} target={l.target} rel={l.rel}>
          <span class="comm-ic" aria-hidden="true">
            <svg width="18" height="18" viewBox="0 0 24 24">
              <path
                fill="currentColor"
                d="M21.5 4.2 2.9 11.4c-1.3.5-1.3 1.2-.2 1.6l4.8 1.5 1.8 5.6c.2.6.4.8.8.8.4 0 .6-.2.9-.5l2.3-2.2 4.8 3.5c.9.5 1.5.2 1.7-.8l3.1-14.7c.3-1.3-.5-1.9-1.4-1.5zM8.8 14.2l9.6-6.1c.5-.3.9-.1.5.2l-8 7.3-.3 3.3z"
              />
            </svg>
          </span>
          <span class="comm-txt">
            <b>{l.label}</b>
            <small>{l.href.replace(/^https:\/\//, '')}</small>
          </span>
          <svg class="ext-ic" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M7 17L17 7M9 7h8v8" />
          </svg>
        </a>
      {/each}
    </div>
  </div>

  <p class="tmdb">
    <a href={ABOUT.tmdb.href} target={ABOUT.tmdb.target} rel={ABOUT.tmdb.rel} aria-label="TMDB">
      <svg width="68" height="14" viewBox="0 0 120 22" aria-hidden="true">
        <rect width="120" height="22" rx="11" fill="#01b4e4" />
        <text x="60" y="15.5" text-anchor="middle" font-size="13" font-weight="800" font-family="system-ui, sans-serif" fill="#0d253f">TMDB</text>
      </svg>
    </a>
    {ABOUT.tmdbNotice}
  </p>
</section>

<style>
  .about {
    max-width: 560px;
    margin: 0 auto;
    padding: 16px 16px 40px;
    display: grid;
    justify-items: center;
    gap: 16px;
  }
  .studio-card {
    width: 100%;
    padding: 28px 22px 22px;
    border-radius: 26px;
    display: grid;
    justify-items: center;
    text-align: center;
    gap: 8px;
  }
  .mark {
    position: relative;
    display: grid;
    place-items: center;
    width: 136px;
    height: 136px;
    margin-bottom: 4px;
  }
  .halo {
    position: absolute;
    inset: 0;
    border-radius: 50%;
    background: radial-gradient(closest-side, var(--accent-glow, rgb(225 29 46 / 0.42)), transparent);
  }
  .logo-anim {
    position: relative;
    display: grid;
    animation: enter 0.7s var(--ease-emphasized, cubic-bezier(0.2, 0, 0, 1)) both;
    filter: drop-shadow(0 14px 32px rgb(225 29 46 / 0.28));
  }
  :global([data-motion='reduce']) .logo-anim,
  :global([data-tier='low']) .logo-anim {
    animation: none;
    filter: none;
  }
  @keyframes enter {
    from {
      transform: scale(0.88);
      opacity: 0;
    }
  }
  h1 {
    margin: 0;
    font-size: 2rem;
    font-weight: 850;
    letter-spacing: -0.02em;
    color: var(--text-primary);
  }
  .ver {
    margin: 0;
    font-size: 0.86rem;
    color: var(--text-secondary);
    font-variant-numeric: tabular-nums;
  }
  .rule {
    width: 100%;
    height: 1px;
    margin: 10px 0 8px;
    background: var(--divider);
  }
  .dev {
    margin: 0;
    color: var(--text-primary);
    font-weight: 760;
    font-size: 1.05rem;
  }
  .rights {
    margin: 4px 0;
    padding: 6px 16px;
    border-radius: 999px;
    font-size: 0.84rem;
    font-weight: 700;
    color: var(--accent);
    background: color-mix(in srgb, var(--accent) 12%, transparent);
    box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--accent) 32%, transparent);
  }
  .thanks {
    margin: 2px 0 8px;
    font-size: 0.9rem;
    color: var(--text-secondary);
  }
  .thanks a {
    color: var(--text-primary);
    font-weight: 650;
    text-decoration-color: var(--accent);
    text-underline-offset: 3px;
  }
  .links {
    width: 100%;
    display: grid;
    gap: 10px;
    margin-top: 8px;
    text-align: left;
  }
  .comm-link {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 12px 14px;
    border-radius: 16px;
    text-decoration: none;
    color: var(--text-primary);
    background: color-mix(in srgb, var(--surface-elevated) 68%, transparent);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
    transition:
      transform calc(var(--dur-short, 140ms) * var(--dur-scale, 1)) ease,
      background-color calc(var(--dur-short, 140ms) * var(--dur-scale, 1)) ease;
  }
  .comm-link:hover,
  .comm-link:focus-visible {
    transform: translateY(-1px);
    background: var(--surface-elevated);
  }
  .comm-link:focus-visible {
    outline: 2px solid var(--focus-ring);
    outline-offset: 2px;
  }
  .comm-ic {
    width: 38px;
    height: 38px;
    flex: none;
    display: grid;
    place-items: center;
    border-radius: 11px;
    color: var(--accent);
    background: color-mix(in srgb, var(--accent) 14%, transparent);
  }
  .comm-txt {
    flex: 1;
    min-width: 0;
    display: grid;
    gap: 2px;
  }
  .comm-txt b {
    font-size: 0.94rem;
    font-weight: 700;
  }
  .comm-txt small {
    color: var(--text-secondary);
    font-size: 0.78rem;
  }
  .ext-ic {
    flex: none;
    color: var(--text-secondary);
  }
  .tmdb {
    margin: 0;
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: center;
    gap: 8px;
    font-size: 0.76rem;
    color: var(--text-tertiary);
    text-align: center;
  }
  .tmdb a {
    display: inline-flex;
  }
</style>
