<script lang="ts">
  // Cinetor Beta ApertureMark (3-blade camera iris around play opening) + CINE (300) TOR (700) wordmark.
  let { size = 28, wordmark = false }: { size?: number; wordmark?: boolean } = $props();
</script>

<span class="logo">
  <svg width={size} height={size} viewBox="0 0 100 100" aria-hidden="true">
    <g stroke="var(--bg, #09090B)" stroke-width="3" stroke-linejoin="round">
      <path d="M42.50,62.99L19.72,76.14A40,40 0,0 1,42.50,10.71L42.50,37.01Z" fill="#E50914" />
      <path d="M42.50,37.01L42.50,10.71A40,40 0,0 1,87.78,63.15L65.00,50.00Z" fill="#C8102E" />
      <path d="M65.00,50.00L87.78,63.15A40,40 0,0 1,19.72,76.14L42.50,62.99Z" fill="#FF2D3B" />
    </g>
    <path d="M65.00,50.00L42.50,62.99L42.50,37.01Z" fill="#FFFFFF" />
  </svg>
  {#if wordmark}
    <span class="word" aria-label="Cinetor"><span class="cine">CINE</span><span class="tor">TOR</span></span>
  {/if}
</span>

<style>
  .logo {
    display: inline-flex;
    align-items: center;
    gap: 10px;
    line-height: 1;
    user-select: none;
  }
  svg {
    flex: none;
    display: block;
    filter: drop-shadow(0 3px 10px rgb(229 9 20 / 0.28));
  }
  :global([data-theme='matte']) svg {
    filter: none;
  }
  :global([data-theme='pearl']) svg {
    filter: drop-shadow(0 2px 6px rgb(225 29 46 / 0.18));
  }
  .word {
    display: inline-flex;
    align-items: baseline;
    font-size: 1.06rem;
    letter-spacing: 0.34em;
    margin-right: -0.34em;
    color: var(--text-primary);
    text-transform: uppercase;
    white-space: nowrap;
  }
  .cine {
    font-weight: 300;
  }
  .tor {
    font-weight: 700;
  }
</style>
