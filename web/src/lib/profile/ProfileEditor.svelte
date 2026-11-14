<script lang="ts">
  import { AVATAR_COLORS, initials, type Profile } from './profiles';
  import { profiles } from './store.svelte';
  import { isPin, pinHash } from '$lib/adult/gate';

  interface Props {
    /** null → create a new profile. */
    profile: Profile | null;
    onDone: () => void;
  }
  let { profile, onDone }: Props = $props();

  // svelte-ignore state_referenced_locally
  let name = $state(profile?.name ?? '');
  // svelte-ignore state_referenced_locally
  let color = $state(profile?.color ?? AVATAR_COLORS[profiles.list.length % AVATAR_COLORS.length]);
  // svelte-ignore state_referenced_locally
  let kids = $state(profile?.kids ?? false);
  let pin = $state('');
  // svelte-ignore state_referenced_locally
  let removePin = $state(false);
  let error = $state('');

  async function save(e: SubmitEvent) {
    e.preventDefault();
    error = '';
    if (!name.trim()) return void (error = 'Name required');
    if (pin && !isPin(pin)) return void (error = 'PIN must be 4 digits');
    try {
      if (profile) {
        const patch: Partial<Profile> = { name, color, kids };
        if (pin) patch.pinHash = await pinHash(profile.id, pin);
        else if (removePin) patch.pinHash = undefined;
        profiles.update(profile.id, patch);
      } else {
        const p = profiles.create({ name, color, kids });
        if (pin) profiles.update(p.id, { pinHash: await pinHash(p.id, pin) });
      }
      onDone();
    } catch (err) {
      error = err instanceof Error ? err.message : String(err);
    }
  }

  async function del() {
    if (!profile) return;
    if (!confirm(`Delete profile "${profile.name}" and all of its history, lists and settings on this device?`)) return;
    try {
      await profiles.remove(profile.id);
      onDone();
    } catch (err) {
      error = err instanceof Error ? err.message : String(err);
    }
  }
</script>

<form class="ed" onsubmit={save}>
  <div class="preview">
    <span class="av" style:background={color}>{initials(name || '?')}</span>
  </div>
  <label class="field">
    <span>Name</span>
    <input bind:value={name} maxlength="24" placeholder="Profile name" autocomplete="off" />
  </label>
  <fieldset class="colors">
    <legend>Avatar colour</legend>
    {#each AVATAR_COLORS as c (c)}
      <button type="button" class="sw" style:background={c} aria-pressed={color === c} aria-label="Colour {c}" onclick={() => (color = c)}></button>
    {/each}
  </fieldset>
  <label class="check">
    <input type="checkbox" bind:checked={kids} />
    <span><b>Kids profile</b><small>Hides the 18+ section and its setting</small></span>
  </label>
  <label class="field">
    <span>{profile?.pinHash ? 'New PIN (optional)' : 'Profile PIN (optional)'}</span>
    <input type="password" inputmode="numeric" maxlength="4" pattern="[0-9]*" bind:value={pin} placeholder="4 digits" autocomplete="new-password" />
  </label>
  {#if profile?.pinHash}
    <label class="check"><input type="checkbox" bind:checked={removePin} /><span>Remove PIN</span></label>
  {/if}
  {#if error}<p class="err" role="alert">{error}</p>{/if}
  <div class="acts">
    <button class="btn btn-primary" type="submit">{profile ? 'Save' : 'Create profile'}</button>
    {#if profile && profiles.list.length > 1}<button class="btn btn-ghost danger" type="button" onclick={del}>Delete</button>{/if}
  </div>
</form>

<style>
  .ed {
    display: grid;
    gap: 14px;
  }
  .preview {
    display: grid;
    place-items: center;
  }
  .av {
    width: 72px;
    height: 72px;
    display: grid;
    place-items: center;
    border-radius: 50%;
    font-weight: 800;
    font-size: 1.5rem;
    color: #fff;
  }
  .field {
    display: grid;
    gap: 6px;
    font-size: 0.85rem;
    color: var(--text-secondary);
  }
  .field input {
    min-height: 44px;
    padding: 0 14px;
    border: 0;
    border-radius: 14px;
    background: var(--glass-fill);
    box-shadow: inset 0 0 0 1px var(--glass-stroke);
    color: var(--text-primary);
    font-size: 1rem;
  }
  .colors {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    border: 0;
    padding: 0;
    margin: 0;
  }
  .colors legend {
    font-size: 0.85rem;
    color: var(--text-secondary);
    margin-bottom: 6px;
  }
  .sw {
    width: 34px;
    height: 34px;
    border-radius: 50%;
    border: 0;
    cursor: pointer;
  }
  .sw[aria-pressed='true'] {
    box-shadow: 0 0 0 3px var(--bg, #000), 0 0 0 5px #fff;
  }
  .check {
    display: flex;
    gap: 10px;
    align-items: center;
  }
  .check input {
    width: 20px;
    height: 20px;
    accent-color: var(--accent);
  }
  .check span {
    display: grid;
  }
  .check small {
    color: var(--text-secondary);
    font-size: 0.75rem;
  }
  .err {
    color: #ff6b6b;
    margin: 0;
  }
  .acts {
    display: flex;
    gap: 10px;
  }
  .danger {
    color: #ff6b6b;
  }
</style>
