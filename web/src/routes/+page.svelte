<script lang="ts">
  import { base } from '$app/paths';
  import Credits from '$lib/components/Credits.svelte';
  import Logo from '$lib/components/Logo.svelte';

  const features = [
    { t: 'Movies, Series, Anime, Drama', d: 'One searchable home for everything you watch.' },
    { t: 'Live TV & Sports', d: 'Channels with a full guide and live match centre.' },
    { t: 'Music & Comics', d: 'Albums, moods, manga, manhwa and manhua — in their own spaces.' },
    { t: 'Instant play', d: 'Pre-buffered playback starts the moment you tap Play.' }
  ];
</script>

<svelte:head>
  <title>Cinetor — Stream everything, beautifully</title>
</svelte:head>

<div class="ambient" aria-hidden="true"></div>

<main class="landing">
  <section class="hero glass">
    <div class="mark"><Logo size={112} /></div>
    <h1>CINETOR</h1>
    <p class="tag">Movies · Series · Anime · Drama · Live TV · Music · Comics</p>
    <div class="cta">
      <a class="btn btn-primary" href="{base}/app">Open Web App</a>
      <a class="btn btn-ghost" href="https://github.com/Ashrix404/cinetor/releases/latest" rel="noopener">Downloads</a>
    </div>
  </section>

  <section class="features" aria-label="Features">
    {#each features as f (f.t)}
      <article class="glass feat">
        <h2>{f.t}</h2>
        <p>{f.d}</p>
      </article>
    {/each}
  </section>
</main>

<footer class="foot">
  <Credits />
</footer>

<style>
  .landing {
    max-width: 1080px;
    margin: 0 auto;
    padding: calc(24px + var(--safe-t)) calc(16px + var(--safe-r)) 24px calc(16px + var(--safe-l));
  }
  .hero {
    display: grid;
    justify-items: center;
    text-align: center;
    padding: clamp(36px, 8vw, 80px) 20px;
    border-radius: var(--radius-xl);
  }
  .mark {
    animation: float 6s ease-in-out infinite;
  }
  @keyframes float {
    50% { transform: translateY(-6px); }
  }
  h1 {
    margin: 18px 0 6px;
    font-size: clamp(2rem, 7vw, 3.6rem);
    letter-spacing: 0.24em;
    font-weight: 850;
    padding-left: 0.24em;
  }
  .tag {
    margin: 0 0 26px;
    color: var(--text-secondary);
  }
  .cta {
    display: flex;
    gap: 12px;
    flex-wrap: wrap;
    justify-content: center;
  }
  .features {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
    gap: 14px;
    margin-top: 18px;
  }
  .feat {
    padding: 20px;
  }
  .feat h2 {
    margin: 0 0 6px;
    font-size: 1.05rem;
  }
  .feat p {
    margin: 0;
    color: var(--text-secondary);
  }
  .foot {
    padding: 28px 16px calc(28px + var(--safe-b));
  }
</style>
