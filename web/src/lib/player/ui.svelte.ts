// Reactive UI state of the player (controls, inline mount sync, gestures feedback, subtitles, aspect, lock).
import { get } from 'svelte/store';
import { activeProfileId } from '$lib/data/library.svelte';
import { gatewayBase, shouldSignGatewayUrl, signedGatewayFetch } from '$lib/data/gateway';
import { clock, togglePiP } from './controller';
import { engine } from './engine';
import { BRIGHTNESS_MAX, BRIGHTNESS_MIN, HoldSpeed, clamp, fmtTime, seekBy } from './gesture-math';
import { settings } from '$lib/settings/store.svelte';
import { stepSpeed } from './keys';
import {
  DEFAULT_SUB_STYLE,
  clampOffset,
  formatTrackLanguage,
  fullLanguageName,
  parseSubtitles,
  type Cue,
  type SubStyle
} from './subs';
import type { SettingsPane } from './types';
import type { Sub } from '$lib/data/types';

export type Aspect = 'fit' | 'fill' | 'zoom';
export const ASPECTS: Aspect[] = ['fit', 'zoom', 'fill'];
export const ASPECT_LABEL: Record<Aspect, string> = { fit: 'Fit', fill: 'Stretch', zoom: 'Zoom' };

const sharedPlayerUi = $state({ inlineMounted: false });

const styleKey = () => `cinetor.substyle.v2.${activeProfileId()}`;
function loadStyle(): SubStyle {
  try {
    return { ...DEFAULT_SUB_STYLE, ...JSON.parse(localStorage.getItem(styleKey()) ?? '{}') };
  } catch {
    return { ...DEFAULT_SUB_STYLE };
  }
}

async function fetchSubtitleText(url: string): Promise<string> {
  const base = gatewayBase();
  const res = shouldSignGatewayUrl(url, base) ? await signedGatewayFetch(url, undefined, base) : await fetch(url);
  if (!res.ok) throw new Error('subtitle_fetch_failed');
  return res.text();
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
  settingsPane = $state<SettingsPane>('main');
  fullscreen = $state(false);
  ripple = $state<{ side: 'left' | 'right'; text: string; n: number } | null>(null);
  holding = $state(false);
  drag = $state<{ target: number; delta: number } | null>(null);
  hud = $state<{ kind: 'volume' | 'brightness'; value: number } | null>(null);
  hint = $state<string | null>(null);
  scrubbing = $state(false);

  // Subtitles: own rendering for side-loaded (catalog + online + external file) cues, native for embedded tracks.
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

  /** Shared reactive flag across `/app/title` `<Player inline={true}>` and layout `<MiniPlayer>`. */
  get inlineMounted(): boolean {
    return sharedPlayerUi.inlineMounted;
  }
  set inlineMounted(value: boolean) {
    sharedPlayerUi.inlineMounted = value;
  }

  get video(): HTMLVideoElement | null {
    return engine.video;
  }

  openSettings(pane: SettingsPane = 'main'): void {
    this.settingsPane = pane;
    this.settings = true;
    this.show();
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
      if (!engine.isPaused && !this.settings && !this.scrubbing) this.visible = false;
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
    engine.togglePlay();
    this.show();
  }
  seekTo(t: number): void {
    const dur = engine.duration;
    engine.seekTo(clamp(t, 0, dur > 0 ? dur : Math.max(0, t)));
  }
  seek(by: number): void {
    this.seekTo(seekBy(engine.currentTime, by, engine.duration));
  }
  setRate(r: number): void {
    engine.setPlaybackRate(r);
  }
  stepRate(dir: -1 | 1): void {
    const next = stepSpeed(engine.playbackRate, dir);
    engine.setPlaybackRate(next);
    this.flash(`${next}×`);
  }
  setVolume(x: number): void {
    engine.setVolume(clamp(x, 0, 1));
  }
  toggleMute(): void {
    engine.setMuted(!engine.muted);
  }

  // ---- gesture feedback ----
  holdStart(): void {
    const s = settings.s as typeof settings.s & { holdForSpeed?: boolean };
    if (engine.isPaused || s.holdForSpeed === false || s.holdSpeed <= 1) return;
    engine.setPlaybackRate(this.hold.start(engine.playbackRate, s.holdSpeed));
    this.holding = true;
  }
  holdEnd(): void {
    const prev = this.hold.end();
    if (prev !== null && engine.playbackRate === this.hold.rate) engine.setPlaybackRate(prev);
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
      ...sides.map((s, i) => ({
        id: `side:${i}`,
        label: formatTrackLanguage(s.label, s.lang, 'Original'),
        kind: 'side' as const
      })),
      ...this.extSubs.map((e) => ({
        id: e.id,
        label: fullLanguageName(e.label),
        kind: 'ext' as const
      })),
      ...t.texts.map((x) => ({
        id: `emb:${x.id}`,
        label: `${formatTrackLanguage(x.label, x.language, 'Original')} (stream)`,
        kind: 'emb' as const
      }))
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
      const text = await fetchSubtitleText(s.url);
      if (this.subId === id) this.cues = parseSubtitles(text, s.format);
    } catch {
      if (this.subId === id) {
        this.subError = `Couldn't load ${formatTrackLanguage(s.label, s.lang, 'subtitles')}`;
        this.cues = [];
      }
    }
  }

  /** Loads and activates an online WebVTT/SRT subtitle returned by in-app online search. */
  async loadRemoteSub(sub: Sub, sides: Sub[]): Promise<void> {
    this.subError = null;
    const cleanLabel = formatTrackLanguage(sub.label, sub.lang, 'Original');
    try {
      const text = await fetchSubtitleText(sub.url);
      const cues = parseSubtitles(text, sub.format);
      if (!cues.length) throw new Error('empty');
      const id = `ext:${sub.url}`;
      const filtered = this.extSubs.filter((e) => e.id !== id);
      this.extSubs = [...filtered, { id, label: cleanLabel, cues }];
      await this.selectSub(id, sides);
      this.flash(`${cleanLabel} subtitles on`);
    } catch {
      this.subError = `Couldn't load ${cleanLabel} subtitles`;
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
      const rawName = file.name.replace(/\.(srt|vtt)$/i, '');
      const label = formatTrackLanguage(rawName, rawName, rawName);
      this.extSubs = [...this.extSubs, { id, label, cues }];
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
    this.settingsPane = 'main';
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

export const ui = new PlayerUI();
