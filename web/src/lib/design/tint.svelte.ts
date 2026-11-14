import { blendTint } from './tint';
import { theme } from './theme.svelte';

/**
 * Current dynamic tint (CSS var --tint on <html>, animated 600 ms via @property in app.css).
 * Call `tint.follow(item.dominantColor)` when the hero/focused card changes.
 */
class TintStore {
  source = $state<string | null>(null);

  value = $derived.by(() => {
    const t = theme.tokens;
    return this.source ? blendTint(this.source, t.colors.accent, t.tintStrength) : t.colors.accent;
  });

  private pending: ReturnType<typeof setTimeout> | null = null;

  follow(color: string | null | undefined): void {
    if (this.pending) clearTimeout(this.pending);
    this.pending = null;
    this.source = color ?? null;
  }

  /** Debounced follow for hovered / focused cards (rapid pointer sweeps don't thrash the tint). */
  followSoon(color: string | null | undefined, ms = 150): void {
    if (this.pending) clearTimeout(this.pending);
    this.pending = setTimeout(() => {
      this.pending = null;
      this.source = color ?? null;
    }, ms);
  }
}

export const tint = new TintStore();

/** Mount once (app layout): mirrors tint.value into CSS. */
export function bindTintToDocument(): () => void {
  return $effect.root(() => {
    $effect(() => {
      document.documentElement.style.setProperty('--tint', tint.value);
    });
  });
}
