// Reactive UI state of the full player (controls, gestures feedback, subtitles, aspect, lock).
import { get } from 'svelte/store';
import { activeProfileId } from '$lib/data/library.svelte';
import { clock, togglePiP } from './controller';
import { engine } from './engine';
import { BRIGHTNESS_MAX, BRIGHTNESS_MIN, HoldSpeed, clamp, fmtTime, seekBy } from './gesture-math';
import { settings } from '$lib/settings/store.svelte';
import { stepSpeed } from './keys';
import { DEFAULT_SUB_STYLE, clampOffset, parseSubtitles, type Cue, type SubStyle } from './subs';
import type { Sub } from '$lib/data/types';

export type Aspect = 'fit' | 'fill' | 'zoom';
export const ASPECTS: Aspect[] = ['fit', 'zoom', 'fill'];
export const ASPECT_LABEL: Record<Aspect, string> = { fit: 'Fit', fill: 'Stretch', zoom: 'Zoom' };

const styleKey = () => `cinetor.substyle.${activeProfileId()}`;
function loadStyle(): SubStyle {
  try {
    return { ...DEFAULT_SUB_STYLE, ...JSON.parse(localStorage.getItem(styleKey()) ?? '{}') };
  } catch {
    return { ...DEFAULT_SUB_STYLE };
  }
}

export interface SubChoice {
  id: string;
  label: string;
  kind: 'side' | 'ext' | 'emb';
}

export class PlayerUI {
  visible = $state(true);
  locked = $state(false);
  lockShown = $state(false);
  aspect = $state<Aspect>('fit');
  brightness = $state(1);
  settings = $state(false);
  fullscreen = $state(false);
  ripple = $state<{ side: 'left' | 'right'; text: string; n: number } | null>(null);
  holding = $state(false);
  drag = $state<{ target: number; delta: number } | null>(null);
  hud = $state<{ kind: 'volume' | 'brightness'; value: number } | null>(null);
  hint = $state<string | null>(null);
  scrubbing = $state(false);

  // Subtitles: own rendering for side-loaded (catalog + external file) cues, native for embedded tracks.
  subId = $state('off');
  cues = $state.raw<Cue[]>([]);
  subStyle = $state<SubStyle>(loadStyle());
  subOffset = $state(0);
  extSubs = $state.raw<{ id: string; label: string; cues: Cue[] }[]>([]);
  subError = $state<string | null>(null);
  private lastSub = 'off';

  stage: HTMLElement | null = null;
  private hold = new HoldSpeed();
  private hideTimer: ReturnType<typeof setTimeout> | undefined;
  private flashTimer: ReturnType<typeof setTimeout> | undefined;
  private hudTimer: ReturnType<typeof setTimeout> | undefined;
  private rippleTimer: ReturnType<typeof setTimeout> | undefined;
  private lockTimer: ReturnType<typeof setTimeout> | undefined;

  get video(): HTMLVideoElement | null {
    return engine.video;
  }

  // ---- controls visibility (auto-hide 3 s while playing) ----
  show(): void {
    if (this.locked) {
      this.lockShown = true;
      clearTimeout(this.lockTimer);
      this.lockTimer = setTimeout(() => (this.lockShown = false), 3000);
      return;
    }
    this.visible = true;
    this.armHide();
  }
  armHide(): void {
    clearTimeout(this.hideTimer);
    this.hideTimer = setTimeout(() => {
      const v = this.video;
      if (v && !v.paused && !this.settings && !this.scrubbing) this.visible = false;
      else this.armHide();
    }, 3000);
  }
  toggleControls(): void {
    if (this.locked) return this.show();
    if (this.visible) {
      this.visible = false;
      clearTimeout(this.hideTimer);
    } else this.show();
  }

  // ---- playback ----
  togglePlay(): void {
    const v = this.video;
    if (!v) return;
    if (v.paused || v.ended) v.play().catch(() => {});
    else v.pause();
    this.show();
  }
  seekTo(t: number): void {
    const v = this.video;
    if (!v) return;
    v.currentTime = clamp(t, 0, Number.isFinite(v.duration) ? v.duration : t);
  }
  seek(by: number): void {
    const v = this.video;
    if (!v) return;
    this.seekTo(seekBy(v.currentTime, by, v.duration || 0));
  }
  setRate(r: number): void {
    const v = this.video;
    if (v) v.playbackRate = r;
  }
  stepRate(dir: -1 | 1): void {
    const v = this.video;
    if (!v) return;
    v.playbackRate = stepSpeed(v.playbackRate, dir);
    this.flash(`${v.playbackRate}×`);
  }
  setVolume(x: number): void {
    const v = this.video;
    if (!v) return;
    v.volume = clamp(x, 0, 1);
    v.muted = v.volume === 0;
  }
  toggleMute(): void {
    const v = this.video;
    if (v) v.muted = !v.muted;
  }

  // ---- gesture feedback ----
  holdStart(): void {
    const v = this.video;
    if (!v || v.paused) return;
    v.playbackRate = this.hold.start(v.playbackRate, settings.s.holdSpeed);
    this.holding = true;
  }
  holdEnd(): void {
    const prev = this.hold.end();
    const v = this.video;
    if (v && prev !== null) v.playbackRate = v.playbackRate === this.hold.rate ? prev : v.playbackRate;
    this.holding = false;
  }
  doubleTap(side: 'left' | 'right', total: number, step: number): void {
    this.seek(Math.sign(total) * step);
    this.ripple = { side, text: `${total > 0 ? '+' : '−'}${Math.abs(total)}s`, n: (this.ripple?.n ?? 0) + 1 };
    clearTimeout(this.rippleTimer);
    this.rippleTimer = setTimeout(() => (this.ripple = null), 750);
  }
  showHud(kind: 'volume' | 'brightness', value: number): void {
    this.hud = { kind, value };
    clearTimeout(this.hudTimer);
    this.hudTimer = setTimeout(() => (this.hud = null), 900);
  }
  setBrightness(b: number): void {
    this.brightness = clamp(b, BRIGHTNESS_MIN, BRIGHTNESS_MAX);
    this.showHud('brightness', (this.brightness - BRIGHTNESS_MIN) / (BRIGHTNESS_MAX - BRIGHTNESS_MIN));
  }
  flash(text: string, ms = 1200): void {
    this.hint = text;
    clearTimeout(this.flashTimer);
    this.flashTimer = setTimeout(() => (this.hint = null), ms);
  }
  dragLabel(): string {
    const d = this.drag;
    if (!d) return '';
    return `${fmtTime(d.target)}  (${d.delta < 0 ? '−' : '+'}${fmtTime(Math.abs(d.delta))})`;
  }

  // ---- lock / aspect / fullscreen / pip ----
  lock(): void {
    this.locked = true;
    this.visible = false;
    this.settings = false;
    this.show();
  }
  unlock(): void {
    this.locked = false;
    this.lockShown = false;
    this.show();
  }
  cycleAspect(): void {
    this.aspect = ASPECTS[(ASPECTS.indexOf(this.aspect) + 1) % ASPECTS.length];
    this.flash(ASPECT_LABEL[this.aspect]);
  }
  async toggleFullscreen(): Promise<void> {
    const el = this.stage;
    if (!el) return;
    type Orient = ScreenOrientation & { lock?: (o: string) => Promise<void> };
    const orient = (screen.orientation ?? null) as Orient | null;
    try {
      if (document.fullscreenElement) {
        await document.exitFullscreen();
        orient?.unlock?.();
        return;
      }
      if (el.requestFullscreen && document.fullscreenEnabled) {
        await el.requestFullscreen({ navigationUI: 'hide' });
        await orient?.lock?.('landscape').catch(() => {});
      } else {
        // iPhone Safari: only the video element can go fullscreen (native controls).
        const v = this.video as (HTMLVideoElement & { webkitEnterFullscreen?: () => void }) | null;
        v?.webkitEnterFullscreen?.();
      }
    } catch {
      /* denied */
    }
  }
  pip(): void {
    void togglePiP();
  }

  // ---- subtitles ----
  choices(sides: Sub[]): SubChoice[] {
    const t = get(engine.tracks);
    return [
      ...sides.map((s, i) => ({ id: `side:${i}`, label: s.label || s.lang, kind: 'side' as const })),
      ...this.extSubs.map((e) => ({ id: e.id, label: e.label, kind: 'ext' as const })),
      ...t.texts.map((x) => ({ id: `emb:${x.id}`, label: `${x.label} (stream)`, kind: 'emb' as const }))
    ];
  }

  async selectSub(id: string, sides: Sub[]): Promise<void> {
    this.subError = null;
    if (id !== 'off') this.lastSub = id;
    this.subId = id;
    if (!id.startsWith('emb:')) engine.selectText(null);
    if (id === 'off') {
      this.cues = [];
      return;
    }
    if (id.startsWith('emb:')) {
      this.cues = [];
      engine.selectText(Number(id.slice(4)));
      return;
    }
    if (id.startsWith('ext:')) {
      this.cues = this.extSubs.find((e) => e.id === id)?.cues ?? [];
      return;
    }
    const s = sides[Number(id.slice(5))];
    if (!s) return;
    try {
      const res = await fetch(s.url);
      if (!res.ok) throw new Error(String(res.status));
      const text = await res.text();
      if (this.subId === id) this.cues = parseSubtitles(text, s.format);
    } catch {
      if (this.subId === id) {
        this.subError = `Couldn't load ${s.label}`;
        this.cues = [];
      }
    }
  }

  /** Captions key (C): toggle last choice / first available. */
  toggleCaptions(sides: Sub[]): void {
    if (this.subId !== 'off') return void this.selectSub('off', sides);
    const all = this.choices(sides);
    const pick = all.find((c) => c.id === this.lastSub) ?? all[0];
    if (pick) void this.selectSub(pick.id, sides);
    else this.flash('No subtitles');
  }

  async loadFile(file: File, sides: Sub[]): Promise<void> {
    try {
      const text = await file.text();
      const cues = parseSubtitles(text, /\.srt$/i.test(file.name) ? 'SRT' : /\.vtt$/i.test(file.name) ? 'VTT' : undefined);
      if (!cues.length) throw new Error('empty');
      const id = `ext:${Date.now()}`;
      this.extSubs = [...this.extSubs, { id, label: file.name.replace(/\.(srt|vtt)$/i, ''), cues }];
      await this.selectSub(id, sides);
      this.flash(`${cues.length} cues loaded`);
    } catch {
      this.subError = 'Not a valid SRT/VTT file';
    }
  }

  setOffset(sec: number): void {
    this.subOffset = clampOffset(sec);
  }
  setStyle(patch: Partial<SubStyle>): void {
    this.subStyle = { ...this.subStyle, ...patch };
    try {
      localStorage.setItem(styleKey(), JSON.stringify(this.subStyle));
    } catch {
      /* ignore */
    }
  }

  reset(): void {
    this.subId = 'off';
    this.cues = [];
    this.subOffset = 0;
    this.extSubs = [];
    this.locked = false;
    this.settings = false;
    this.drag = null;
    if (this.holding) this.holdEnd();
  }

  dispose(): void {
    for (const t of [this.hideTimer, this.flashTimer, this.hudTimer, this.rippleTimer, this.lockTimer]) clearTimeout(t);
    if (this.holding) this.holdEnd();
  }

  get clock() {
    return get(clock);
  }
}
