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
  <p class="tmdb">
    <a href="https://www.themoviedb.org/" target="_blank" rel="noopener noreferrer" aria-label="TMDB">
      <svg width="64" height="12" viewBox="0 0 120 22" aria-hidden="true"><rect width="120" height="22" rx="11" fill="#01b4e4" /><text x="60" y="15.5" text-anchor="middle" font-size="13" font-weight="800" font-family="system-ui, sans-serif" fill="#0d253f">TMDB</text></svg>
    </a>
    This product uses the TMDB API but is not endorsed or certified by TMDB.
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
  .tmdb {
    margin-top: 12px;
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
