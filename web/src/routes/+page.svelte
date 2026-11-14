<script lang="ts">
  import { onMount } from 'svelte';
  import { base } from '$app/paths';
  import Credits from '$lib/components/Credits.svelte';
  import InstallButton from '$lib/pwa/InstallButton.svelte';

  const RELEASES = 'https://github.com/Ashrix404/cinetor-app/releases/latest';

  const features = [
    { i: '🎬', t: 'Movies, Series, Anime, Drama', d: 'One searchable home with TMDB metadata, hero carousels, genres and Top 10s.' },
    { i: '⚡', t: 'Instant play', d: 'The player pre-buffers while you read the details — playback starts the moment you tap Play.' },
    { i: '📺', t: 'Live TV & Sports', d: 'Channels with a full programme guide and a live match centre, in their own space.' },
    { i: '🎧', t: 'Music & Comics', d: 'Albums, moods, manga, manhwa and manhua — isolated spaces with their own search.' },
    { i: '⬇️', t: 'Offline downloads', d: 'Multi-connection downloads with resume, quality choice and a full live progress view.' },
    { i: '🖼️', t: 'Floating player', d: 'Mini player across screens, Picture-in-Picture, gestures, subtitle styling and sync.' },
    { i: '🕹️', t: 'TV & remote ready', d: 'Spatial navigation for D-pads and remotes on Android TV, Fire TV, Tizen and webOS.' },
    { i: '🎨', t: 'Red & black glass', d: 'Three themes — Noir, Obsidian, Ember — with a live tint that follows the artwork.' }
  ];

  const shots = [
    { src: `${base}/screens/phone-music.jpg`, label: 'Music' },
    { src: `${base}/screens/phone-livetv.jpg`, label: 'Live TV & Sports' },
    { src: `${base}/screens/phone-comics.jpg`, label: 'Comics' },
    { src: `${base}/screens/phone-downloads.jpg`, label: 'Offline downloads' }
  ];

  const platforms = [
    { k: 'android', t: 'Android phone', s: 'APK · universal & per-ABI', icon: 'M7 9h10v8a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1zM7 8a5 5 0 0 1 10 0zM9.5 5.5l-1-1.5m6 1.5l1-1.5' },
    { k: 'tv', t: 'Android TV / Fire TV', s: 'Same APK · TV launcher', icon: 'M3 6h18v11H3zM8 20h8' },
    { k: 'win', t: 'Windows', s: '.exe / .msi installer', icon: 'M3 5.5l7.5-1v7H3zM12 4.3L21 3v8.5h-9zM3 12.5h7.5v7L3 18.5zM12 12.5h9V21l-9-1.3z' },
    { k: 'linux', t: 'Linux', s: '.deb / .AppImage', icon: 'M12 3c-2 0-3 2-3 4.5 0 1.5-2.5 4-3 6.5-.4 2 1 3.5 2 4 1 .5 2 1.5 4 1.5s3-1 4-1.5 2.4-2 2-4c-.5-2.5-3-5-3-6.5C15 5 14 3 12 3z' }
  ];

  let track = $state<HTMLDivElement>();
  let idx = $state(0);
  function go(i: number) {
    if (!track) return;
    const n = shots.length;
    idx = (i + n) % n;
    const el = track.children[idx] as HTMLElement | undefined;
    if (el) track.scrollTo({ left: el.offsetLeft - (track.clientWidth - el.clientWidth) / 2, behavior: 'smooth' });
  }
  function onScroll() {
    if (!track) return;
    const mid = track.scrollLeft + track.clientWidth / 2;
    let best = 0;
    let bd = Infinity;
    [...track.children].forEach((c, i) => {
      const el = c as HTMLElement;
      const d = Math.abs(el.offsetLeft + el.clientWidth / 2 - mid);
      if (d < bd) {
        bd = d;
        best = i;
      }
    });
    idx = best;
  }
  onMount(() => {
    // Auto-advance every 4 s while visible and not hovered.
    let hover = false;
    const t = track;
    const enter = () => (hover = true);
    const leave = () => (hover = false);
    t?.addEventListener('pointerenter', enter);
    t?.addEventListener('pointerleave', leave);
    const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
    const id = reduce ? undefined : setInterval(() => !hover && !document.hidden && go(idx + 1), 4000);
    return () => {
      clearInterval(id);
      t?.removeEventListener('pointerenter', enter);
      t?.removeEventListener('pointerleave', leave);
    };
  });
</script>

<svelte:head>
  <title>Cinetor — Stream everything, beautifully</title>
  <meta name="description" content="Cinetor: movies, series, anime, drama, live TV, music and comics in one red & black glass app. Android, Android TV, Windows, Linux and the web." />
</svelte:head>

<div class="ambient" aria-hidden="true"></div>
<div class="glow g1" aria-hidden="true"></div>
<div class="glow g2" aria-hidden="true"></div>

<header class="top">
  <a class="brand" href="{base}/" aria-label="Cinetor home">
    <svg width="30" height="30" viewBox="0 0 512 512" aria-hidden="true"><rect width="512" height="512" rx="112" fill="#0b0b0b" /><path d="M 364.5 158.3 A 146 146 0 1 0 364.5 353.7" fill="none" stroke="#E50914" stroke-width="62" stroke-linecap="round" /><path d="M220 196 L220 316 L322 256 Z" fill="#fff" /></svg>
    <span>CINETOR</span>
  </a>
  <nav aria-label="Sections">
    <a href="#features">Features</a>
    <a href="#screens">Screens</a>
    <a href="#download">Download</a>
  </nav>
  <a class="btn btn-primary sm" href="{base}/app">Open Web App</a>
</header>

<main class="landing">
  <section class="hero glass">
    <div class="mark" aria-hidden="true">
      <svg viewBox="0 0 512 512" width="148" height="148">
        <defs>
          <linearGradient id="lr" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FF2D3B" /><stop offset="1" stop-color="#B00610" /></linearGradient>
          <linearGradient id="lt" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#1a1a1a" /><stop offset="1" stop-color="#050505" /></linearGradient>
          <filter id="lb" x="-40%" y="-40%" width="180%" height="180%"><feGaussianBlur stdDeviation="18" /></filter>
        </defs>
        <rect width="512" height="512" rx="112" fill="url(#lt)" />
        <rect x="1.5" y="1.5" width="509" height="509" rx="110" fill="none" stroke="#fff" stroke-opacity=".12" stroke-width="3" />
        <path class="lglow" d="M 364.5 158.3 A 146 146 0 1 0 364.5 353.7" fill="none" stroke="#E50914" stroke-width="82" filter="url(#lb)" />
        <path class="lring" d="M 364.5 158.3 A 146 146 0 1 0 364.5 353.7" fill="none" stroke="url(#lr)" stroke-width="62" stroke-linecap="round" />
        <path class="lplay" d="M220 196 L220 316 L322 256 Z" fill="#fff" stroke="#fff" stroke-width="12" stroke-linejoin="round" />
      </svg>
    </div>
    <h1>CINETOR</h1>
    <p class="tag">Movies · Series · Anime · Drama · Live TV · Music · Comics</p>
    <p class="lead">One fast, beautiful app for everything you watch, listen to and read — on your phone, TV, computer and the web.</p>
    <div class="cta">
      <a class="btn btn-primary" href="{base}/app">▶ Open Web App</a>
      <a class="btn btn-ghost" href={RELEASES} target="_blank" rel="noopener noreferrer">⬇ Download</a>
      <InstallButton label="Install" />
    </div>
  </section>

  <section id="features" aria-labelledby="f-h">
    <h2 id="f-h" class="h2">Everything in one place</h2>
    <div class="grid">
      {#each features as f (f.t)}
        <article class="glass feat">
          <span class="fi" aria-hidden="true">{f.i}</span>
          <h3>{f.t}</h3>
          <p>{f.d}</p>
        </article>
      {/each}
    </div>
  </section>

  <section id="screens" aria-labelledby="s-h">
    <h2 id="s-h" class="h2">A look inside</h2>
    <div class="carousel">
      <button class="nav prev" onclick={() => go(idx - 1)} aria-label="Previous screenshot">‹</button>
      <div class="track" bind:this={track} onscroll={onScroll} role="group" aria-roledescription="carousel" aria-label="Screenshots">
        {#each shots as s, i (s.src)}
          <figure class="phone" class:on={i === idx} aria-label="{i + 1} of {shots.length}: {s.label}">
            <img src={s.src} alt="Cinetor {s.label} screen" width="390" height="844" loading="lazy" decoding="async" />
            <figcaption>{s.label}</figcaption>
          </figure>
        {/each}
      </div>
      <button class="nav next" onclick={() => go(idx + 1)} aria-label="Next screenshot">›</button>
    </div>
    <div class="dots">
      {#each shots as s, i (s.src)}
        <button aria-label="Show {s.label}" aria-pressed={i === idx} class:on={i === idx} onclick={() => go(i)}></button>
      {/each}
    </div>
    <img class="wide" src="{base}/screens/wide-details.jpg" alt="Cinetor on desktop" width="1920" height="1080" loading="lazy" decoding="async" />
  </section>

  <section id="download" aria-labelledby="d-h">
    <h2 id="d-h" class="h2">Get Cinetor</h2>
    <p class="sub">Free. Every build is published to GitHub Releases — download from any device, no login.</p>
    <div class="plats">
      {#each platforms as p (p.k)}
        <a class="plat glass" href={RELEASES} target="_blank" rel="noopener noreferrer">
          <svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round" aria-hidden="true"><path d={p.icon} /></svg>
          <span class="pt"><b>{p.t}</b><small>{p.s}</small></span>
          <span class="arr" aria-hidden="true">↓</span>
        </a>
      {/each}
      <a class="plat glass web" href="{base}/app">
        <svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><circle cx="12" cy="12" r="9" /><path d="M3 12h18M12 3c3 3.5 3 14.5 0 18M12 3c-3 3.5-3 14.5 0 18" /></svg>
        <span class="pt"><b>Web app</b><small>Any browser · installable PWA</small></span>
        <span class="arr" aria-hidden="true">→</span>
      </a>
    </div>
    <p class="hint">Tip: on a TV browser open the web app with <code>?tv=1</code> for remote-friendly navigation.</p>
  </section>
</main>

<footer class="foot">
  <Credits />
</footer>

<style>
  :global(html) {
    scroll-behavior: smooth;
  }
  .glow {
    position: fixed;
    z-index: -1;
    width: 60vmax;
    height: 60vmax;
    border-radius: 50%;
    filter: blur(80px);
    opacity: 0.35;
    pointer-events: none;
    animation: drift 18s ease-in-out infinite alternate;
  }
  .g1 {
    left: -20vmax;
    top: -25vmax;
    background: radial-gradient(circle, #e50914, transparent 65%);
  }
  .g2 {
    right: -25vmax;
    top: 30vh;
    background: radial-gradient(circle, #7a0610, transparent 65%);
    animation-duration: 24s;
  }
  @keyframes drift {
    to {
      transform: translate(6vmax, 4vmax) scale(1.08);
    }
  }
  :global([data-tier='low']) .glow {
    display: none;
  }
  .top {
    position: sticky;
    top: 0;
    z-index: 10;
    display: flex;
    align-items: center;
    gap: 16px;
    padding: calc(10px + var(--safe-t)) calc(16px + var(--safe-r)) 10px calc(16px + var(--safe-l));
    background: linear-gradient(180deg, rgb(0 0 0 / 0.75), transparent);
    -webkit-backdrop-filter: blur(10px);
    backdrop-filter: blur(10px);
  }
  .brand {
    display: flex;
    align-items: center;
    gap: 10px;
    text-decoration: none;
    font-weight: 850;
    letter-spacing: 0.22em;
  }
  .top nav {
    display: flex;
    gap: 18px;
    margin-left: auto;
  }
  .top nav a {
    text-decoration: none;
    color: var(--text-secondary);
    font-weight: 600;
    font-size: 0.9rem;
  }
  .top nav a:hover {
    color: var(--text-primary);
  }
  .sm {
    min-height: 38px;
    padding: 0 16px;
    font-size: 0.85rem;
  }
  @media (max-width: 640px) {
    .top nav {
      display: none;
    }
    .top .sm {
      margin-left: auto;
    }
  }
  .landing {
    max-width: 1120px;
    margin: 0 auto;
    padding: 12px calc(16px + var(--safe-r)) 24px calc(16px + var(--safe-l));
  }
  .hero {
    display: grid;
    justify-items: center;
    text-align: center;
    padding: clamp(40px, 8vw, 88px) 20px;
    border-radius: var(--radius-xl);
    overflow: hidden;
  }
  .mark {
    animation: float 6s ease-in-out 1.2s infinite;
  }
  .mark svg {
    filter: drop-shadow(0 18px 40px rgb(229 9 20 / 0.45));
  }
  .lring {
    stroke-dasharray: 703.8 703.8;
    animation: draw 900ms cubic-bezier(0.65, 0, 0.35, 1) both;
  }
  .lglow {
    animation: pulse 3.2s ease-in-out 900ms infinite;
    opacity: 0.35;
  }
  .lplay {
    transform-box: fill-box;
    transform-origin: 40% 50%;
    animation: pop 450ms cubic-bezier(0.34, 1.56, 0.64, 1) 650ms both;
  }
  @keyframes draw {
    from {
      stroke-dashoffset: 703.8;
    }
    to {
      stroke-dashoffset: 0;
    }
  }
  @keyframes pulse {
    50% {
      opacity: 0.85;
    }
  }
  @keyframes pop {
    from {
      transform: scale(0);
      opacity: 0;
    }
  }
  @keyframes float {
    50% {
      transform: translateY(-8px);
    }
  }
  h1 {
    margin: 22px 0 6px;
    font-size: clamp(2.2rem, 8vw, 4.2rem);
    letter-spacing: 0.26em;
    padding-left: 0.26em;
    font-weight: 900;
    background: linear-gradient(180deg, #fff, #ffb3b8);
    -webkit-background-clip: text;
    background-clip: text;
    color: transparent;
  }
  .tag {
    margin: 0;
    color: color-mix(in srgb, var(--accent) 55%, white);
    font-weight: 700;
    font-size: 0.9rem;
  }
  .lead {
    max-width: 620px;
    margin: 14px auto 26px;
    color: var(--text-secondary);
    font-size: 1.05rem;
  }
  .cta {
    display: flex;
    gap: 12px;
    flex-wrap: wrap;
    justify-content: center;
  }
  .h2 {
    margin: 56px 0 18px;
    font-size: clamp(1.4rem, 4vw, 2rem);
    text-align: center;
  }
  .grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
    gap: 14px;
  }
  .feat {
    padding: 20px;
    transition: transform 0.25s var(--ease-emphasized);
  }
  .feat:hover {
    transform: translateY(-3px);
  }
  .fi {
    font-size: 1.6rem;
  }
  .feat h3 {
    margin: 8px 0 6px;
    font-size: 1.02rem;
  }
  .feat p {
    margin: 0;
    color: var(--text-secondary);
    font-size: 0.9rem;
  }
  .carousel {
    position: relative;
  }
  .track {
    position: relative;
    display: flex;
    gap: 22px;
    overflow-x: auto;
    scroll-snap-type: x mandatory;
    scrollbar-width: none;
    padding: 14px max(16px, calc(50% - 130px)) 22px;
  }
  .track::-webkit-scrollbar {
    display: none;
  }
  .phone {
    flex: none;
    width: 260px;
    margin: 0;
    scroll-snap-align: center;
    transition: transform 0.35s var(--ease-emphasized), opacity 0.35s;
    transform: scale(0.9);
    opacity: 0.6;
  }
  .phone.on {
    transform: none;
    opacity: 1;
  }
  .phone img {
    width: 100%;
    height: auto;
    border-radius: 28px;
    box-shadow: 0 0 0 6px #111, 0 0 0 7px rgb(255 255 255 / 0.14), 0 30px 60px -20px rgb(229 9 20 / 0.45);
  }
  .phone figcaption {
    text-align: center;
    margin-top: 14px;
    font-size: 0.85rem;
    color: var(--text-secondary);
  }
  .nav {
    position: absolute;
    top: 45%;
    z-index: 2;
    width: 44px;
    height: 44px;
    border: 0;
    border-radius: 50%;
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
    font-size: 1.6rem;
    cursor: pointer;
    -webkit-backdrop-filter: blur(8px);
    backdrop-filter: blur(8px);
  }
  .prev {
    left: 4px;
  }
  .next {
    right: 4px;
  }
  .dots {
    display: flex;
    justify-content: center;
    gap: 8px;
  }
  .dots button {
    width: 8px;
    height: 8px;
    padding: 0;
    border: 0;
    border-radius: 99px;
    background: rgb(255 255 255 / 0.25);
    cursor: pointer;
    transition: width 0.3s;
  }
  .dots button.on {
    width: 24px;
    background: var(--accent);
  }
  .wide {
    width: 100%;
    height: auto;
    margin-top: 36px;
    border-radius: 18px;
    box-shadow: 0 0 0 1px rgb(255 255 255 / 0.12), 0 40px 80px -30px rgb(229 9 20 / 0.4);
  }
  .sub {
    text-align: center;
    color: var(--text-secondary);
    margin: -6px 0 20px;
  }
  .plats {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
    gap: 12px;
  }
  .plat {
    display: flex;
    align-items: center;
    gap: 14px;
    padding: 18px;
    text-decoration: none;
    transition: transform 0.2s var(--ease-emphasized);
  }
  .plat:hover {
    transform: translateY(-2px);
  }
  .plat svg {
    flex: none;
    color: color-mix(in srgb, var(--accent) 70%, white);
  }
  .pt {
    flex: 1;
    display: grid;
  }
  .plat small {
    color: var(--text-secondary);
  }
  .arr {
    font-size: 1.2rem;
    color: var(--accent);
  }
  .plat.web {
    background: linear-gradient(135deg, color-mix(in srgb, var(--accent) 22%, transparent), transparent), var(--glass-fill);
  }
  .hint {
    text-align: center;
    margin-top: 16px;
    color: var(--text-tertiary);
    font-size: 0.85rem;
  }
  code {
    padding: 1px 6px;
    border-radius: 6px;
    background: var(--glass-fill);
  }
  .foot {
    margin-top: 56px;
    padding: 28px 16px calc(28px + var(--safe-b));
    border-top: 1px solid rgb(255 255 255 / 0.08);
  }
  @media (prefers-reduced-motion: reduce) {
    .glow,
    .mark,
    .lglow {
      animation: none;
    }
  }
</style>
