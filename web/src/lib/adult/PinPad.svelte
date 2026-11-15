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

  async function submit(p: string) {
    busy = true;
    const r = await onSubmit(p);
    busy = false;
    if (r !== true) {
      error = r;
      pin = '';
      shake++;
      input?.focus();
    }
  }

  function press(d: string) {
    if (busy || pin.length >= 4) return;
    error = '';
    pin += d;
    if (pin.length === 4) void submit(pin);
  }
  function del() {
    pin = pin.slice(0, -1);
  }
  function typed(e: Event) {
    const v = (e.currentTarget as HTMLInputElement).value.replace(/\D/g, '').slice(0, 4);
    (e.currentTarget as HTMLInputElement).value = '';
    for (const ch of v) press(ch);
  }
  function key(e: KeyboardEvent) {
    if (e.key === 'Backspace') {
      e.preventDefault();
      del();
    }
  }
</script>

<div class="pad">
  <h2>{title}</h2>
  {#if hint}<p class="hint">{hint}</p>{/if}
  {#key shake}
    <div class="dots" class:shake={shake > 0} aria-label="{pin.length} of 4 digits entered">
      {#each [0, 1, 2, 3] as i (i)}<span class:on={i < pin.length}></span>{/each}
    </div>
  {/key}
  <!-- Hidden input so hardware keyboards / TV remotes / screen readers can type the PIN. -->
  <input
    bind:this={input}
    class="sr-only"
    type="password"
    inputmode="numeric"
    autocomplete="off"
    aria-label={title}
    oninput={typed}
    onkeydown={key}
  />
  <p class="err" role="alert">{error}</p>
  <div class="keys">
    {#each ['1', '2', '3', '4', '5', '6', '7', '8', '9'] as d (d)}
      <button type="button" onclick={() => press(d)} disabled={busy}>{d}</button>
    {/each}
    <span></span>
    <button type="button" onclick={() => press('0')} disabled={busy}>0</button>
    <button type="button" onclick={del} aria-label="Delete digit">⌫</button>
  </div>
</div>

<style>
  .pad {
    display: grid;
    justify-items: center;
    gap: 10px;
    padding: 8px;
  }
  h2 {
    margin: 0;
    font-size: 1.15rem;
  }
  .hint {
    margin: 0;
    color: var(--text-secondary);
    font-size: 0.85rem;
    text-align: center;
  }
  .dots {
    display: flex;
    gap: 14px;
    margin: 8px 0 0;
  }
  .dots span {
    width: 14px;
    height: 14px;
    border-radius: 50%;
    box-shadow: inset 0 0 0 2px var(--text-secondary);
    transition: background 0.15s;
  }
  .dots span.on {
    background: var(--accent);
    box-shadow: none;
  }
  .shake {
    animation: shake 0.35s;
  }
  @keyframes shake {
    25% { transform: translateX(-8px); }
    75% { transform: translateX(8px); }
  }
  .err {
    min-height: 1.2em;
    margin: 0;
    color: #ff6b6b;
    font-size: 0.82rem;
  }
  .keys {
    display: grid;
    grid-template-columns: repeat(3, 64px);
    gap: 10px;
  }
  .keys button {
    height: 56px;
    border: 0;
    border-radius: 18px;
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
    font-size: 1.3rem;
    font-weight: 650;
    cursor: pointer;
  }
  .keys button:active {
    background: color-mix(in srgb, var(--accent) 30%, var(--glass-fill));
  }
</style>
