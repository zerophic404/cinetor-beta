<script lang="ts">
  import Credits from '$lib/components/Credits.svelte';
  import Logo from '$lib/components/Logo.svelte';
  import { THEME_NAMES, theme } from '$lib/design/theme.svelte';
  import { themes } from '$lib/design/tokens';
  import { perf } from '$lib/perf/tier.svelte';
  import type { TierOverride } from '$lib/perf/tier';
  import { repo, setSimulatedLatency } from '$lib/data/repo';

  const tiers: { v: TierOverride; l: string }[] = [
    { v: 'AUTO', l: 'Auto' },
    { v: 'LOW', l: 'Low' },
    { v: 'MID', l: 'Medium' },
    { v: 'HIGH', l: 'High' }
  ];
  let latencyOn = $state(repo.latency.enabled);
</script>

<svelte:head><title>Me · Cinetor</title></svelte:head>

<div class="me">
  <header class="profile glass">
    <Logo size={56} />
    <div>
      <h1>Me</h1>
      <p>Profile, library and settings arrive in the next phase.</p>
    </div>
  </header>

  <section aria-labelledby="theme-h">
    <h2 id="theme-h" class="section-title">Theme</h2>
    <div class="themes" role="radiogroup" aria-label="Theme">
      {#each THEME_NAMES as name (name)}
        {@const t = themes[name]}
        <button
          class="theme-card"
          data-theme={name}
          role="radio"
          aria-checked={theme.current === name}
          onclick={() => theme.set(name)}
        >
          <span class="preview">
            <span class="pv-hero"></span>
            <span class="pv-glass glass glass-bar"><i></i><i></i><i></i></span>
          </span>
          <span class="name">{t.name}</span>
          <span class="desc">blur {t.blurDp}px · tint {Math.round(t.tintStrength * 100)}%</span>
        </button>
      {/each}
    </div>
  </section>

  <section aria-labelledby="fx-h">
    <h2 id="fx-h" class="section-title">Effects quality</h2>
    <div class="h-scroll" role="radiogroup" aria-label="Effects quality">
      {#each tiers as t (t.v)}
        <button class="chip" class:active={perf.override === t.v} role="radio" aria-checked={perf.override === t.v} onclick={() => perf.setOverride(t.v)}>{t.l}</button>
      {/each}
    </div>
    <p class="note">Active tier: <b>{perf.flags.tier}</b> · live blur {perf.flags.blurScope} · animations ×{perf.flags.durationScale}</p>
  </section>

  <section aria-labelledby="dev-h">
    <h2 id="dev-h" class="section-title">Developer</h2>
    <label class="toggle">
      <input type="checkbox" bind:checked={latencyOn} onchange={() => setSimulatedLatency(latencyOn)} />
      <span>Simulated network latency (300–1200 ms) for skeleton preview</span>
    </label>
  </section>

  <section class="about glass" aria-label="About">
    <Logo size={40} wordmark />
    <Credits />
  </section>
</div>

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
  .profile h1 {
    margin: 0;
    font-size: 1.4rem;
  }
  .profile p {
    margin: 2px 0 0;
    color: var(--text-secondary);
    font-size: 0.88rem;
  }
  .themes {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 12px;
    padding: 0 16px;
  }
  .theme-card {
    display: grid;
    gap: 4px;
    padding: 10px;
    border: 0;
    border-radius: var(--radius-lg);
    background: var(--bg);
    color: var(--text-primary);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
    cursor: pointer;
    text-align: left;
    transition: transform calc(var(--dur-medium) * var(--dur-scale)) var(--ease-emphasized);
  }
  .theme-card[aria-checked='true'] {
    box-shadow: 0 0 0 2px var(--accent), 0 10px 30px -12px var(--accent-glow);
  }
  .theme-card:hover {
    transform: translateY(-2px);
  }
  .preview {
    position: relative;
    display: block;
    aspect-ratio: 4 / 3;
    border-radius: var(--radius-md);
    overflow: hidden;
    background: var(--surface);
  }
  .pv-hero {
    position: absolute;
    inset: 0;
    background: radial-gradient(80% 70% at 20% 10%, var(--accent), transparent 70%),
      radial-gradient(60% 60% at 90% 30%, var(--accent-end), transparent 70%);
    opacity: 0.85;
  }
  .pv-glass {
    position: absolute !important;
    left: 8%;
    right: 8%;
    bottom: 8%;
    height: 26%;
    border-radius: 12px;
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
  }
  .desc,
  .note {
    font-size: 0.75rem;
    color: var(--text-secondary);
  }
  .note {
    margin: 0 16px;
  }
  .toggle {
    display: flex;
    gap: 10px;
    align-items: center;
    margin: 0 16px;
    color: var(--text-secondary);
  }
  .toggle input {
    width: 20px;
    height: 20px;
    accent-color: var(--accent);
  }
  .about {
    display: grid;
    justify-items: center;
    gap: 12px;
    margin: 28px 16px 0;
    padding: 22px;
  }
</style>
