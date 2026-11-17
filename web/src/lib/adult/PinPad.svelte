<script lang="ts">
  interface Props {
    title?: string;
    hint?: string;
    /** Return true (accepted) or an error message. */
    onSubmit: (pin: string) => Promise<true | string> | true | string;
  }
  let { title = 'Enter PIN', hint = '', onSubmit }: Props = $props();

  let pin = $state('');
  let error = $state('');
  let busy = $state(false);
  let shake = $state(0);
  let input = $state<HTMLInputElement>();

  $effect(() => {
    void title;
    pin = '';
    error = '';
  });

  async function submit(p: string) {
    busy = true;
    try {
      const r = await onSubmit(p);
      pin = '';
      if (r !== true) {
        error = r;
        shake++;
        input?.focus();
      }
    } finally {
      busy = false;
    }
  }

  function press(d: string) {
    if (busy || pin.length >= 4) return;
    error = '';
    pin += d;
    if (pin.length === 4) void submit(pin);
  }

  function del() {
    if (busy || !pin.length) return;
    error = '';
    pin = pin.slice(0, -1);
  }

  function clearPin() {
    if (busy || !pin.length) return;
    error = '';
    pin = '';
  }

  function typed(e: Event) {
    const el = e.currentTarget as HTMLInputElement;
    const v = el.value.replace(/\D/g, '').slice(0, 4);
    el.value = '';
    for (const ch of v) press(ch);
  }

  function inputKey(e: KeyboardEvent) {
    if (e.key === 'Backspace') {
      e.preventDefault();
      del();
    }
  }

  function onWindowKey(e: KeyboardEvent) {
    if (e.defaultPrevented || e.ctrlKey || e.metaKey || e.altKey || busy) return;
    if (e.target === input) return;
    if (e.target instanceof Element && e.target.closest('input, textarea, select, [contenteditable="true"]')) return;
    if (/^\d$/.test(e.key)) {
      e.preventDefault();
      press(e.key);
    } else if (e.key === 'Backspace') {
      e.preventDefault();
      del();
    }
  }
</script>

<svelte:window onkeydown={onWindowKey} />

<div class="pad">
  <div class="emblem" aria-hidden="true">
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round">
      <rect x="5" y="11" width="14" height="10" rx="2.5" />
      <path d="M8 11V7a4 4 0 0 1 8 0v4" />
    </svg>
  </div>
  <h2>{title}</h2>
  {#if hint}<p class="hint">{hint}</p>{/if}

  {#key shake}
    <div class="dots" class:shake={shake > 0} role="status" aria-label="{pin.length} of 4 digits entered">
      {#each [0, 1, 2, 3] as i (i)}
        <span class:on={i < pin.length} class:next={i === pin.length && !busy}></span>
      {/each}
    </div>
  {/key}

  <!-- Screen-reader & mobile numeric keyboard input -->
  <input
    bind:this={input}
    data-pin-input="1"
    class="sr-only"
    type="password"
    inputmode="numeric"
    autocomplete="off"
    aria-label={title}
    oninput={typed}
    onkeydown={inputKey}
  />

  <p class="err" role="alert">{error}</p>

  <div class="keys" role="group" aria-label="PIN keypad">
    {#each ['1', '2', '3', '4', '5', '6', '7', '8', '9'] as d (d)}
      <button type="button" class="key-btn" onclick={() => press(d)} disabled={busy}>{d}</button>
    {/each}
    <button type="button" class="key-btn util" onclick={clearPin} disabled={busy || !pin.length} aria-label="Clear PIN">C</button>
    <button type="button" class="key-btn" onclick={() => press('0')} disabled={busy}>0</button>
    <button type="button" class="key-btn util" onclick={del} disabled={busy || !pin.length} aria-label="Delete digit">
      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <path d="M21 6H9l-6 6 6 6h12a1.5 1.5 0 0 0 1.5-1.5v-9A1.5 1.5 0 0 0 21 6z" />
        <path d="m16.5 9.5-5 5M11.5 9.5l5 5" />
      </svg>
    </button>
  </div>
</div>

<style>
  .pad {
    display: grid;
    justify-items: center;
    gap: 10px;
    padding: 8px 4px;
  }
  .emblem {
    width: 44px;
    height: 44px;
    display: grid;
    place-items: center;
    border-radius: 14px;
    color: var(--accent);
    background: linear-gradient(145deg, color-mix(in srgb, var(--accent) 22%, transparent), color-mix(in srgb, var(--accent) 6%, var(--glass-fill)));
    box-shadow:
      inset 0 1px 0 rgb(255 255 255 / 0.18),
      inset 0 0 0 1px color-mix(in srgb, var(--accent) 36%, var(--glass-stroke));
  }
  h2 {
    margin: 2px 0 0;
    font-size: 1.2rem;
    font-weight: 760;
    letter-spacing: -0.01em;
  }
  .hint {
    margin: 0;
    max-width: 30ch;
    color: var(--text-secondary);
    font-size: 0.84rem;
    line-height: 1.4;
    text-align: center;
  }
  .dots {
    display: flex;
    gap: 14px;
    margin: 8px 0 2px;
    padding: 10px 18px;
    border-radius: 999px;
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
  }
  .dots span {
    width: 13px;
    height: 13px;
    border-radius: 50%;
    background: transparent;
    box-shadow: inset 0 0 0 2px var(--text-secondary);
    transition:
      transform 0.16s var(--ease-emphasized, ease-out),
      background-color 0.16s ease,
      box-shadow 0.16s ease;
  }
  .dots span.next {
    box-shadow: inset 0 0 0 2px var(--text-primary);
  }
  .dots span.on {
    background: var(--accent);
    box-shadow: 0 0 12px var(--accent-glow);
    transform: scale(1.12);
  }
  .shake {
    animation: pin-shake 0.34s ease;
  }
  @keyframes pin-shake {
    20% {
      transform: translateX(-8px);
    }
    45% {
      transform: translateX(7px);
    }
    70% {
      transform: translateX(-5px);
    }
    90% {
      transform: translateX(3px);
    }
  }
  .err {
    min-height: 1.25em;
    margin: 0;
    color: var(--accent);
    font-size: 0.82rem;
    font-weight: 650;
    text-align: center;
  }
  .keys {
    display: grid;
    grid-template-columns: repeat(3, 68px);
    gap: 10px;
    margin-top: 2px;
  }
  .key-btn {
    height: 56px;
    display: grid;
    place-items: center;
    border: 0;
    border-radius: 18px;
    background: linear-gradient(160deg, rgb(255 255 255 / 0.08), transparent 65%), var(--glass-fill);
    box-shadow:
      inset 0 1px 0 rgb(255 255 255 / 0.14),
      inset 0 0 0 1px var(--glass-stroke),
      0 6px 16px -8px rgb(0 0 0 / 0.45);
    color: var(--text-primary);
    font-size: 1.32rem;
    font-weight: 700;
    font-variant-numeric: tabular-nums;
    cursor: pointer;
    user-select: none;
    transition:
      transform 0.12s ease,
      background-color 0.15s ease,
      box-shadow 0.15s ease;
  }
  .key-btn.util {
    font-size: 0.95rem;
    font-weight: 700;
    color: var(--text-secondary);
  }
  .key-btn:hover:not(:disabled) {
    background: linear-gradient(160deg, rgb(255 255 255 / 0.12), transparent 65%), color-mix(in srgb, var(--accent) 12%, var(--glass-fill));
  }
  .key-btn:active:not(:disabled) {
    transform: scale(0.95);
    background: color-mix(in srgb, var(--accent) 28%, var(--glass-fill));
    box-shadow:
      inset 0 0 0 1px color-mix(in srgb, var(--accent) 55%, var(--glass-stroke)),
      0 6px 18px -6px var(--accent-glow);
  }
  .key-btn:focus-visible {
    outline: 2px solid var(--accent);
    outline-offset: 2px;
  }
  .key-btn:disabled {
    opacity: 0.36;
    cursor: default;
  }
</style>
