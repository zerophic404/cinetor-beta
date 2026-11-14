<script lang="ts">
  import { base } from '$app/paths';
  import Logo from '$lib/components/Logo.svelte';
</script>

<svelte:head><title>Offline · Cinetor</title></svelte:head>

<div class="ambient" aria-hidden="true"></div>
<main class="off">
  <section class="card glass">
    <Logo size={72} />
    <h1>You're offline</h1>
    <p>No connection right now. Your downloaded movies and episodes still play in the app.</p>
    <div class="cta">
      <a class="btn btn-primary" href="{base}/app/downloads">Open Downloads</a>
      <button class="btn btn-ghost" onclick={() => location.reload()}>Try again</button>
    </div>
  </section>
</main>

<style>
  .off {
    min-height: 100dvh;
    display: grid;
    place-items: center;
    padding: 24px;
  }
  .card {
    max-width: 440px;
    padding: 36px 28px;
    text-align: center;
    display: grid;
    justify-items: center;
    gap: 6px;
    border-radius: var(--radius-xl);
  }
  h1 {
    margin: 14px 0 0;
    font-size: 1.6rem;
  }
  p {
    color: var(--text-secondary);
  }
  .cta {
    display: flex;
    gap: 10px;
    flex-wrap: wrap;
    justify-content: center;
  }
</style>
