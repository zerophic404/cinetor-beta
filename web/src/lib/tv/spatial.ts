// This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer,
// scrape or hack anything without Mythbrix's explicit permission.
// TV / remote spatial navigation: pure nearest-neighbour selection + DOM engine
// (arrow keys / D-pad, Enter, Back/Escape/Backspace, focus memory per route).
import { writable, get } from 'svelte/store';

export type Dir = 'up' | 'down' | 'left' | 'right';
export interface Rect {
  left: number;
  top: number;
  right: number;
  bottom: number;
}
export interface Candidate<T> {
  rect: Rect;
  value: T;
}

/** Weight of the orthogonal (misalignment) distance vs. the distance along the direction. */
export const ORTHO_WEIGHT = 4;

const cx = (r: Rect) => (r.left + r.right) / 2;
const cy = (r: Rect) => (r.top + r.bottom) / 2;

/** Score of `c` as the next focus from `f` in `dir` (lower = better), or null when not in that direction. */
export function score(f: Rect, c: Rect, dir: Dir): number | null {
  let primary: number;
  let overlap: number;
  let ortho: number;
  let offset: number;
  if (dir === 'left' || dir === 'right') {
    const fwd = dir === 'right';
    if (fwd ? !(cx(c) > cx(f) + 0.5 && c.right > f.right) : !(cx(c) < cx(f) - 0.5 && c.left < f.left)) return null;
    primary = Math.max(0, fwd ? c.left - f.right : f.left - c.right);
    overlap = Math.min(f.bottom, c.bottom) - Math.max(f.top, c.top);
    ortho = overlap > 0 ? 0 : Math.max(c.top - f.bottom, f.top - c.bottom);
    offset = Math.abs(cy(c) - cy(f));
  } else {
    const fwd = dir === 'down';
    if (fwd ? !(cy(c) > cy(f) + 0.5 && c.bottom > f.bottom) : !(cy(c) < cy(f) - 0.5 && c.top < f.top)) return null;
    primary = Math.max(0, fwd ? c.top - f.bottom : f.top - c.bottom);
    overlap = Math.min(f.right, c.right) - Math.max(f.left, c.left);
    ortho = overlap > 0 ? 0 : Math.max(c.left - f.right, f.left - c.right);
    offset = Math.abs(cx(c) - cx(f));
  }
  return primary + ORTHO_WEIGHT * ortho + 0.1 * offset;
}

/** Best candidate in `dir` from `from` (nearest-neighbour with alignment preference). */
export function nearest<T>(from: Rect, cands: Candidate<T>[], dir: Dir): T | null {
  let best: T | null = null;
  let bestScore = Infinity;
  for (const c of cands) {
    if (c.rect.left === from.left && c.rect.top === from.top && c.rect.right === from.right && c.rect.bottom === from.bottom) continue;
    const s = score(from, c.rect, dir);
    if (s !== null && s < bestScore) {
      bestScore = s;
      best = c.value;
    }
  }
  return best;
}

export const TV_UA = /Tizen|Web0S|webOS|SMART-TV|SmartTV|Android TV|AndroidTV|GoogleTV|BRAVIA|CrKey|HbbTV|Roku|PhilipsTV|NetCast|Viera|Opera TV/i;
/** Amazon Fire TV models (AFTMM, AFTKA …) — case-sensitive to avoid false hits. */
const FIRE_TV = /\bAFT[A-Z]/;
export const isTvUA = (ua: string): boolean => TV_UA.test(ua) || FIRE_TV.test(ua);

/** Remote key → action (incl. webOS 461 / Tizen 10009 back keyCodes). */
export type NavKey = { type: 'move'; dir: Dir } | { type: 'enter' } | { type: 'back' } | null;
export function navKey(e: { key: string; keyCode?: number }): NavKey {
  switch (e.key) {
    case 'ArrowUp':
    case 'Up':
      return { type: 'move', dir: 'up' };
    case 'ArrowDown':
    case 'Down':
      return { type: 'move', dir: 'down' };
    case 'ArrowLeft':
    case 'Left':
      return { type: 'move', dir: 'left' };
    case 'ArrowRight':
    case 'Right':
      return { type: 'move', dir: 'right' };
    case 'Enter':
      return { type: 'enter' };
    case 'Escape':
    case 'Backspace':
    case 'GoBack':
    case 'BrowserBack':
      return { type: 'back' };
  }
  if (e.keyCode === 461 || e.keyCode === 10009) return { type: 'back' };
  return null;
}

// ---------------- DOM engine (browser only) ----------------

export const tvEnabled = writable(false);
const TV_KEY = 'cinetor.tv';
const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]):not([type=hidden]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"]), [data-focusable]';

export function setTv(on: boolean, persist = true): void {
  tvEnabled.set(on);
  if (typeof document !== 'undefined') {
    if (on) document.documentElement.dataset.tv = '1';
    else delete document.documentElement.dataset.tv;
  }
  if (persist) {
    try {
      localStorage.setItem(TV_KEY, on ? '1' : '0');
    } catch {
      /* ignore */
    }
  }
}

const visible = (el: HTMLElement): boolean => {
  if (el.closest('[inert], [aria-hidden="true"], .video-park, .skip, [data-tv-skip]')) return false;
  const r = el.getBoundingClientRect();
  if (r.width < 2 || r.height < 2) return false;
  const cs = getComputedStyle(el);
  return cs.visibility !== 'hidden' && cs.display !== 'none' && Number(cs.opacity) > 0.05;
};

/** Top-most open modal dialog scopes navigation. */
function scope(): ParentNode {
  const dialogs = [...document.querySelectorAll<HTMLDialogElement>('dialog[open]')];
  return dialogs[dialogs.length - 1] ?? document;
}

export function focusables(root: ParentNode = scope()): HTMLElement[] {
  return [...root.querySelectorAll<HTMLElement>(FOCUSABLE)].filter(visible);
}

const rectOf = (el: Element): Rect => {
  const r = el.getBoundingClientRect();
  return { left: r.left, top: r.top, right: r.right, bottom: r.bottom };
};

function focusEl(el: HTMLElement): void {
  el.focus({ preventScroll: true });
  el.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' });
}

/** Focus memory per route (path + query). */
const memory = new Map<string, { key: string; index: number }>();
const routeKey = () => location.pathname + location.search;
const elKey = (el: HTMLElement) => el.id || el.getAttribute('href') || el.getAttribute('aria-label') || el.textContent?.trim().slice(0, 40) || '';

let lastFocused: HTMLElement | null = null;

export function remember(el: HTMLElement): void {
  lastFocused = el;
  const list = focusables(document);
  memory.set(routeKey(), { key: elKey(el), index: list.indexOf(el) });
}

/** Restore the last focused element of this route (or the first item in main content). */
export function restoreFocus(attempt = 0): void {
  if (!get(tvEnabled)) return;
  const main = document.getElementById('main');
  const cur = document.activeElement;
  // User already moved focus into the new page → leave it.
  if (attempt > 0 && cur && cur !== document.body && main?.contains(cur)) return;
  const list = focusables(document);
  const m = memory.get(routeKey());
  const exact = m ? list.find((el) => elKey(el) === m.key) : undefined;
  // Content loads async (skeletons first): retry a few times for the remembered element.
  if (m && !exact && attempt < 6) {
    setTimeout(() => restoreFocus(attempt + 1), 250);
    return;
  }
  if (!list.length) return;
  const target = exact ?? (m ? list[Math.min(m.index, list.length - 1)] : undefined) ?? list.find((el) => main?.contains(el)) ?? list[0];
  if (target) focusEl(target);
}

function move(dir: Dir): boolean {
  const list = focusables();
  if (!list.length) return false;
  const cur = document.activeElement as HTMLElement | null;
  if (!cur || cur === document.body || !list.includes(cur)) {
    // Focus lost (element re-rendered): resume from the last focused element if still on screen.
    if (lastFocused?.isConnected && list.includes(lastFocused)) {
      const next = nearest(rectOf(lastFocused), list.filter((el) => el !== lastFocused).map((el) => ({ rect: rectOf(el), value: el })), dir);
      focusEl(next ?? lastFocused);
      return true;
    }
    const main = document.getElementById('main');
    focusEl(list.find((el) => main?.contains(el)) ?? list[0]);
    return true;
  }
  const next = nearest(rectOf(cur), list.filter((el) => el !== cur).map((el) => ({ rect: rectOf(el), value: el })), dir);
  if (next) {
    focusEl(next);
    return true;
  }
  return false;
}

const isTextField = (el: Element | null): el is HTMLInputElement | HTMLTextAreaElement =>
  !!el && (el.tagName === 'TEXTAREA' || (el.tagName === 'INPUT' && !/^(checkbox|radio|range|button|submit|reset|color|file)$/i.test((el as HTMLInputElement).type)));

export interface SpatialOpts {
  /** Back when nothing else handles it (default: history.back()). */
  onBack?: () => void;
}

/** Install the global key handler. Returns uninstall. */
export function installSpatialNav(opts: SpatialOpts = {}): () => void {
  try {
    const q = new URLSearchParams(location.search).get('tv');
    const saved = localStorage.getItem(TV_KEY);
    if (q === '1' || q === '0') setTv(q === '1');
    else if (saved === '1' || (saved === null && isTvUA(navigator.userAgent))) setTv(true, false);
  } catch {
    /* ignore */
  }

  const onKey = (e: KeyboardEvent) => {
    if (e.defaultPrevented || e.ctrlKey || e.metaKey || e.altKey) return;
    const k = navKey(e);
    if (!k) return;
    const active = document.activeElement;
    // The player owns arrows/Enter/Back while focus is on its stage (not inside its controls).
    const player = document.querySelector('[data-tv-player]');
    if (player && (active === player || active === document.body || !active) && !document.querySelector('dialog[open]')) return;
    if (k.type === 'back') {
      if (isTextField(active) && e.key === 'Backspace') return;
      if (document.querySelector('dialog[open]')) return; // native Escape closes the sheet
      if (e.key === 'Escape' && !get(tvEnabled)) return;
      if (player && player.contains(active)) {
        e.preventDefault();
        (player as HTMLElement).focus({ preventScroll: true });
        return;
      }
      if (!get(tvEnabled)) return;
      e.preventDefault();
      (opts.onBack ?? (() => history.back()))();
      return;
    }
    if (k.type === 'enter') {
      if (!get(tvEnabled)) return;
      // Native Enter already activates links/buttons; give custom [data-focusable] tiles a click.
      if (active instanceof HTMLElement && active.matches('[data-focusable]:not(a):not(button)')) {
        e.preventDefault();
        active.click();
      }
      return;
    }
    // Arrows: caret movement inside text fields, values inside selects/sliders.
    if (isTextField(active) && (k.dir === 'left' || k.dir === 'right')) return;
    if (active instanceof HTMLSelectElement) return;
    if (active instanceof HTMLInputElement && active.type === 'range' && (k.dir === 'left' || k.dir === 'right')) return;
    if (active?.getAttribute('role') === 'slider' && (k.dir === 'left' || k.dir === 'right')) return;
    if (!get(tvEnabled)) setTv(true, false); // first arrow press switches to TV/remote mode
    if (move(k.dir)) e.preventDefault();
  };
  const onFocus = (e: FocusEvent) => {
    if (get(tvEnabled) && e.target instanceof HTMLElement && e.target !== document.body) remember(e.target);
  };
  // Pointer/touch use → leave TV mode unless it came from a TV UA or ?tv=1.
  const onPointer = (e: PointerEvent) => {
    if (e.pointerType === 'mouse' && e.movementX === 0 && e.movementY === 0) return;
    try {
      if (localStorage.getItem(TV_KEY) === '1' || isTvUA(navigator.userAgent)) return;
    } catch {
      /* ignore */
    }
    if (get(tvEnabled) && e.pointerType === 'touch') setTv(false, false);
  };
  window.addEventListener('keydown', onKey);
  document.addEventListener('focusin', onFocus);
  window.addEventListener('pointerdown', onPointer, { passive: true });
  return () => {
    window.removeEventListener('keydown', onKey);
    document.removeEventListener('focusin', onFocus);
    window.removeEventListener('pointerdown', onPointer);
  };
}
