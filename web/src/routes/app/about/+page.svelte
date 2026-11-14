<script lang="ts">
  import Logo from '$lib/components/Logo.svelte';
  import { ABOUT } from '$lib/about';
</script>

<svelte:head><title>About · Cinetor</title></svelte:head>

<section class="about">
  <div class="mark" aria-hidden="true">
    <span class="halo"></span>
    <span class="logo-anim"><Logo size={112} /></span>
  </div>
  <h1>{ABOUT.name}</h1>
  <p class="ver">Version {ABOUT.version}</p>

  <div class="links">
    {#each ABOUT.community as l (l.href)}
      <a class="btn btn-ghost" href={l.href} target={l.target} rel={l.rel}>
        <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M21.5 4.2 2.9 11.4c-1.3.5-1.3 1.2-.2 1.6l4.8 1.5 1.8 5.6c.2.6.4.8.8.8.4 0 .6-.2.9-.5l2.3-2.2 4.8 3.5c.9.5 1.5.2 1.7-.8l3.1-14.7c.3-1.3-.5-1.9-1.4-1.5zM8.8 14.2l9.6-6.1c.5-.3.9-.1.5.2l-8 7.3-.3 3.3z" /></svg>
        {l.label}
      </a>
    {/each}
  </div>

  <div class="credits glass">
    <p class="dev">{ABOUT.developedBy}</p>
    <p>{ABOUT.thanksPrefix}<a href={ABOUT.thanks.href} target={ABOUT.thanks.target} rel={ABOUT.thanks.rel}>{ABOUT.thanks.label}</a></p>
  </div>

  <p class="tmdb">
    <a href={ABOUT.tmdb.href} target={ABOUT.tmdb.target} rel={ABOUT.tmdb.rel} aria-label="TMDB">
      <svg width="72" height="14" viewBox="0 0 120 22" aria-hidden="true"><rect width="120" height="22" rx="11" fill="#01b4e4" /><text x="60" y="15.5" text-anchor="middle" font-size="13" font-weight="800" font-family="system-ui, sans-serif" fill="#0d253f">TMDB</text></svg>
    </a>
    {ABOUT.tmdbNotice}
  </p>
</section>

<style>
  .about {
    max-width: 560px;
    margin: 0 auto;
    padding: 24px 16px 40px;
    display: grid;
    justify-items: center;
    text-align: center;
    gap: 10px;
  }
  .mark {
    position: relative;
    display: grid;
    place-items: center;
    width: 180px;
    height: 180px;
  }
  .halo {
    position: absolute;
    inset: 0;
    border-radius: 50%;
    background: radial-gradient(closest-side, var(--accent-glow, rgb(229 9 20 / 0.5)), transparent);
    animation: breathe 3.2s ease-in-out infinite;
  }
  .logo-anim {
    position: relative;
    display: grid;
    animation: enter 0.9s var(--ease-emphasized, cubic-bezier(0.2, 0, 0, 1)) both, float 5s ease-in-out 0.9s infinite;
    filter: drop-shadow(0 18px 40px rgb(229 9 20 / 0.35));
  }
  @keyframes enter {
    from { transform: scale(0.6) rotate(-12deg); opacity: 0; }
  }
  @keyframes float {
    50% { transform: translateY(-6px); }
  }
  @keyframes breathe {
    50% { transform: scale(1.15); opacity: 0.6; }
  }
  h1 {
    margin: 4px 0 0;
    font-size: 2.2rem;
    letter-spacing: 0.02em;
    background: linear-gradient(135deg, #fff, var(--accent));
    -webkit-background-clip: text;
    background-clip: text;
    color: transparent;
  }
  .ver {
    margin: 0;
    color: var(--text-secondary);
    font-variant-numeric: tabular-nums;
  }
  .links {
    display: flex;
    flex-wrap: wrap;
    justify-content: center;
    gap: 10px;
    margin: 12px 0;
  }
  .credits {
    width: 100%;
    padding: 18px;
    border-radius: 22px;
  }
  .credits p {
    margin: 4px 0;
    color: var(--text-secondary);
  }
  .credits .dev {
    color: var(--text-primary);
    font-weight: 750;
    font-size: 1.05rem;
  }
  .credits a {
    color: var(--text-primary);
    text-decoration-color: var(--accent);
    text-underline-offset: 3px;
  }
  .tmdb {
    margin-top: 16px;
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: center;
    gap: 8px;
    font-size: 0.75rem;
    color: var(--text-tertiary);
  }
  .tmdb a {
    display: inline-flex;
  }
</style>
