<script lang="ts">
  let { size = 36, wordmark = false }: { size?: number; wordmark?: boolean } = $props();
  const pid = $props.id();
  const uid = 'lg' + pid.replace(/[^a-zA-Z0-9_-]/g, '');
</script>

<span class="logo">
  <svg width={size} height={size} viewBox="0 0 512 512" aria-hidden="true">
    <defs>
      <linearGradient id="{uid}r" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0" stop-color="#FF2D3B" /><stop offset="1" stop-color="#B00610" />
      </linearGradient>
      <linearGradient id="{uid}t" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0" stop-color="#1a1a1a" /><stop offset="1" stop-color="#050505" />
      </linearGradient>
    </defs>
    <rect width="512" height="512" rx="112" fill="url(#{uid}t)" />
    <rect x="1.5" y="1.5" width="509" height="509" rx="110" fill="none" stroke="#ffffff" stroke-opacity=".12" stroke-width="3" />
    <path d="M 364.5 158.3 A 146 146 0 1 0 364.5 353.7" fill="none" stroke="url(#{uid}r)" stroke-width="62" stroke-linecap="round" />
    <path d="M220 196 L220 316 L322 256 Z" fill="#fff" stroke="#fff" stroke-width="12" stroke-linejoin="round" />
  </svg>
  {#if wordmark}<span class="word">CINETOR</span>{/if}
</span>

<style>
  .logo {
    display: inline-flex;
    align-items: center;
    gap: 10px;
  }
  svg {
    filter: drop-shadow(0 4px 14px rgb(229 9 20 / 0.35));
  }
  .word {
    font-weight: 800;
    letter-spacing: 0.22em;
    font-size: 1.05rem;
  }
</style>
