<script lang="ts">
  import type { Snippet } from 'svelte';
  import { page } from '$app/state';
  import { base } from '$app/paths';
  import { adult } from './store.svelte';
  import PinPad from './PinPad.svelte';

  let { children }: { children: Snippet } = $props();

  const onBrowse = $derived(page.url.pathname.replace(/\/$/, '').endsWith('/app/adult'));
  const activeCat = $derived(page.url.searchParams.get('cat') ?? 'ad-featured');
  const ADULT_SORTS = [
    { id: 'ad-featured', label: 'Movies' },
    { id: 'ad-series', label: 'Series' },
    { id: 'ad-popular', label: 'Hottest' },
    { id: 'ad-top-rated', label: 'Top Rated' }
  ];

  let setupStep = $state<'idle' | 'age' | 'pin' | 'confirm'>('idle');
  let ageOk = $state(false);
  let draftPin = '';

  function startSetup() {
    ageOk = false;
    draftPin = '';
    setupStep = 'age';
  }

  function saveFirstPin(pin: string): true | string {
    draftPin = pin;
    setupStep = 'confirm';
    return true;
  }

  async function confirmSetupPin(pin: string): Promise<true | string> {
    if (pin !== draftPin) {
      setupStep = 'pin';
      return "PINs didn't match — enter a new PIN";
    }
    const res = await adult.enable(ageOk, pin);
    if (!res.ok) return res.error;
    setupStep = 'idle';
    return true;
  }

  async function submitUnlock(pin: string): Promise<true | string> {
    const waitMs = adult.lockedOutMs;
    if (waitMs > 0) return `Too many attempts — try again in ${Math.ceil(waitMs / 1000)}s`;
    if (await adult.unlock(pin)) return true;
    const afterMs = adult.lockedOutMs;
    if (afterMs > 0) return `Too many attempts — wait ${Math.ceil(afterMs / 1000)}s`;
    return 'Incorrect PIN';
  }
</script>

{#if !adult.canEnable}
  <section class="lock glass glass-sheet" aria-labelledby="adult-kids-h">
    <div class="badge blocked" aria-hidden="true">
      <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round">
        <path d="M12 3l8 3v6c0 4.5-3.4 8.3-8 9-4.6-.7-8-4.5-8-9V6z" />
        <path d="m9.5 9.5 5 5M14.5 9.5l-5 5" />
      </svg>
    </div>
    <h1 id="adult-kids-h">Unavailable on Kids profiles</h1>
    <p>18+ content is permanently blocked while a Kids profile is active.</p>
    <div class="actions">
      <a class="btn btn-primary" href="{base}/app/me">Switch profile</a>
    </div>
  </section>
{:else if !adult.enabled}
  <section class="lock glass glass-sheet" aria-labelledby="adult-off-h">
    {#if setupStep === 'idle'}
      <span class="pill-18">18+</span>
      <h1 id="adult-off-h">18+ space is off</h1>
      <p>Restricted to adults 18 and older. Requires age confirmation and a 4-digit PIN, and stays isolated from Home, global search, and Continue Watching.</p>
      <div class="actions">
        <button type="button" class="btn btn-primary" onclick={startSetup}>Enable 18+ space</button>
        <a class="btn btn-ghost" href="{base}/app/me">Back to Me</a>
      </div>
    {:else if setupStep === 'age'}
      <span class="pill-18">18+</span>
      <h1 id="adult-off-h">Confirm your age</h1>
      <p>This space contains adult material intended strictly for viewers aged 18 or older.</p>
      <label class="age-check">
        <input type="checkbox" bind:checked={ageOk} />
        <span>I confirm that I am 18 years of age or older</span>
      </label>
      <div class="actions">
        <button type="button" class="btn btn-primary" disabled={!ageOk} onclick={() => (setupStep = 'pin')}>Continue to PIN</button>
        <button type="button" class="btn btn-ghost" onclick={() => (setupStep = 'idle')}>Cancel</button>
      </div>
    {:else if setupStep === 'pin'}
      <PinPad title="Create 4-digit PIN" hint="Required once per session to unlock 18+." onSubmit={saveFirstPin} />
      <button type="button" class="text-btn" onclick={() => (setupStep = 'idle')}>Cancel</button>
    {:else}
      <PinPad title="Confirm 4-digit PIN" hint="Re-enter the same 4 digits to finish setup." onSubmit={confirmSetupPin} />
      <button type="button" class="text-btn" onclick={() => (setupStep = 'pin')}>Back</button>
    {/if}
  </section>
{:else if !adult.unlocked}
  <section class="lock glass glass-sheet" aria-label="Unlock 18+ space">
    <PinPad
      title="Unlock 18+ Space"
      hint="Unlocked for this session. Relocks after 5 minutes of inactivity or in the background."
      onSubmit={submitUnlock}
    />
    <a class="text-btn" href="{base}/app/me">Back to Me</a>
  </section>
{:else}
  <div class="session-bar glass">
    <div class="session-left">
      <span class="pill-18 sm">18+</span>
      {#if onBrowse}
        <nav class="sort-pills" aria-label="18+ categories">
          {#each ADULT_SORTS as s (s.id)}
            <a class="chip xs" class:active={activeCat === s.id} href="{base}/app/adult?cat={s.id}">{s.label}</a>
          {/each}
        </nav>
      {:else}
        <span class="session-note">Session unlocked · Auto-locks after 5 min idle</span>
      {/if}
    </div>
    <button type="button" class="lock-btn" onclick={() => adult.lock()}>
      <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <rect x="5" y="11" width="14" height="10" rx="2.5" />
        <path d="M8 11V7a4 4 0 0 1 8 0v4" />
      </svg>
      Lock now
    </button>
  </div>
  {@render children()}
{/if}

<style>
  .lock {
    max-width: 430px;
    margin: 36px auto;
    padding: 28px 24px;
    display: grid;
    justify-items: center;
    gap: 12px;
    text-align: center;
    border-radius: 28px;
  }
  .lock h1 {
    margin: 2px 0 0;
    font-size: 1.35rem;
    font-weight: 780;
    letter-spacing: -0.015em;
  }
  .lock p {
    margin: 0;
    max-width: 34ch;
    color: var(--text-secondary);
    font-size: 0.9rem;
    line-height: 1.45;
  }
  .badge {
    width: 50px;
    height: 50px;
    display: grid;
    place-items: center;
    border-radius: 16px;
    color: var(--accent);
    background: color-mix(in srgb, var(--accent) 16%, var(--glass-fill));
    box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--accent) 36%, var(--glass-stroke));
  }
  .pill-18 {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    padding: 3px 10px;
    border-radius: 999px;
    font-size: 0.75rem;
    font-weight: 800;
    letter-spacing: 0.05em;
    color: var(--on-accent);
    background: linear-gradient(135deg, var(--accent), var(--accent-end));
    box-shadow: 0 6px 16px -6px var(--accent-glow);
  }
  .pill-18.sm {
    padding: 2px 8px;
    font-size: 0.68rem;
  }
  .age-check {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 12px 14px;
    margin-top: 4px;
    border-radius: 16px;
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
    text-align: left;
    font-size: 0.88rem;
    font-weight: 600;
    cursor: pointer;
  }
  .age-check input {
    width: 20px;
    height: 20px;
    flex: none;
    accent-color: var(--accent);
  }
  .actions {
    display: flex;
    flex-wrap: wrap;
    justify-content: center;
    gap: 10px;
    margin-top: 6px;
  }
  .text-btn {
    border: 0;
    background: none;
    padding: 6px 12px;
    border-radius: 999px;
    color: var(--text-secondary);
    font-size: 0.85rem;
    font-weight: 650;
    text-decoration: none;
    cursor: pointer;
  }
  .text-btn:hover {
    color: var(--text-primary);
  }
  .session-bar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    flex-wrap: wrap;
    gap: 10px;
    margin: 0 16px 10px;
    padding: 8px 12px;
    border-radius: 16px;
  }
  .session-left {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 8px;
    min-width: 0;
  }
  .session-note {
    color: var(--text-secondary);
    font-size: 0.8rem;
    font-weight: 600;
  }
  .sort-pills {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }
  .chip.xs {
    min-height: 28px;
    padding: 0 11px;
    font-size: 0.76rem;
  }
  .lock-btn {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    min-height: 32px;
    padding: 0 12px;
    border: 0;
    border-radius: 999px;
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
    color: var(--text-primary);
    font-size: 0.78rem;
    font-weight: 700;
    cursor: pointer;
  }
  .lock-btn:hover {
    background: color-mix(in srgb, var(--accent) 18%, var(--glass-fill));
  }
</style>
