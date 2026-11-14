<script lang="ts">
  import { DEVELOPER, TELEGRAM_CHANNEL_URL, TELEGRAM_GROUP_URL, THANKS_NAME, THANKS_URL } from '$lib/constants';
</script>

<div class="credits">
  <p class="dev">Developed By {DEVELOPER}</p>
  <p>Thanks to <a href={THANKS_URL} target="_blank" rel="noopener noreferrer">{THANKS_NAME}</a></p>
  <p class="links">
    <a href={TELEGRAM_CHANNEL_URL} target="_blank" rel="noopener noreferrer">Telegram Channel</a>
    <span aria-hidden="true">·</span>
    <a href={TELEGRAM_GROUP_URL} target="_blank" rel="noopener noreferrer">Telegram Group</a>
  </p>
</div>

<style>
  .credits {
    text-align: center;
    color: var(--text-secondary);
    font-size: 0.9rem;
  }
  p {
    margin: 4px 0;
  }
  .dev {
    color: var(--text-primary);
    font-weight: 700;
  }
  a {
    color: var(--text-primary);
    text-decoration-color: var(--accent);
    text-underline-offset: 3px;
  }
  a:hover {
    color: var(--accent);
  }
  .links {
    display: flex;
    justify-content: center;
    gap: 10px;
  }
</style>
