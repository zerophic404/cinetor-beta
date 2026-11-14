// Subtitle parsing + own cue rendering math (SRT→VTT, VTT parse, sync offset). Pure, unit tested.

export interface Cue {
  start: number;
  end: number;
  text: string;
}

export const OFFSET_LIMIT = 10;
export const OFFSET_STEP = 0.05;

/** "01:02:03,450" | "02:03.450" | "3.5" → seconds. NaN when unparsable. */
export function parseTimestamp(ts: string): number {
  const m = ts.trim().match(/^(?:(\d+):)?(\d{1,2}):(\d{1,2})(?:[.,](\d{1,3}))?$/);
  if (!m) return NaN;
  const [, h, mi, s, frac] = m;
  return (h ? +h * 3600 : 0) + +mi * 60 + +s + (frac ? +frac.padEnd(3, '0') / 1000 : 0);
}

const vttTs = (sec: number): string => {
  const ms = Math.max(0, Math.round(sec * 1000));
  const h = Math.floor(ms / 3600000);
  const m = Math.floor((ms % 3600000) / 60000);
  const s = Math.floor((ms % 60000) / 1000);
  const p = (n: number, w = 2) => String(n).padStart(w, '0');
  return `${p(h)}:${p(m)}:${p(s)}.${p(ms % 1000, 3)}`;
};

const ARROW = /^\s*(\S+)\s+-->\s+(\S+)(.*)$/;

/** SRT → WebVTT (comma → dot, numeric counters dropped, BOM/CRLF normalized). */
export function srtToVtt(srt: string): string {
  const blocks = srt.replace(/^\uFEFF/, '').replace(/\r\n?/g, '\n').trim().split(/\n{2,}/);
  const out: string[] = ['WEBVTT', ''];
  for (const b of blocks) {
    const lines = b.split('\n');
    const i = lines.findIndex((l) => ARROW.test(l));
    if (i < 0) continue;
    const [, a, z] = lines[i].match(ARROW)!;
    const st = parseTimestamp(a);
    const en = parseTimestamp(z);
    if (Number.isNaN(st) || Number.isNaN(en)) continue;
    out.push(`${vttTs(st)} --> ${vttTs(en)}`, ...lines.slice(i + 1), '');
  }
  return out.join('\n');
}

export const looksLikeVtt = (text: string): boolean => /^\uFEFF?WEBVTT/.test(text);

/** Minimal WebVTT parser (cue timings + text; settings, NOTE/STYLE/REGION blocks skipped). */
export function parseVtt(vtt: string): Cue[] {
  const blocks = vtt.replace(/^\uFEFF/, '').replace(/\r\n?/g, '\n').split(/\n{2,}/);
  const cues: Cue[] = [];
  for (const b of blocks) {
    const lines = b.split('\n').filter((l, idx) => idx > 0 || l.trim() !== '');
    const i = lines.findIndex((l) => l.includes('-->'));
    if (i < 0 || /^(NOTE|STYLE|REGION)\b/.test(lines[0] ?? '')) continue;
    const m = lines[i].match(ARROW);
    if (!m) continue;
    const start = parseTimestamp(m[1]);
    const end = parseTimestamp(m[2]);
    if (Number.isNaN(start) || Number.isNaN(end) || end < start) continue;
    const text = lines.slice(i + 1).join('\n').trim();
    if (text) cues.push({ start, end, text });
  }
  return cues.sort((x, y) => x.start - y.start);
}

/** Parse either format. */
export function parseSubtitles(text: string, hint?: 'SRT' | 'VTT' | string): Cue[] {
  const isVtt = hint === 'VTT' || (hint !== 'SRT' && looksLikeVtt(text));
  return parseVtt(isVtt ? text : srtToVtt(text));
}

/** Clamp + snap a sync offset to ±10 s in 50 ms steps. Positive = subtitles appear later. */
export function clampOffset(sec: number): number {
  const c = Math.min(OFFSET_LIMIT, Math.max(-OFFSET_LIMIT, sec));
  return Math.round(c / OFFSET_STEP) * OFFSET_STEP + 0; // +0 normalizes -0
}

/** Cue times shifted by offset (both directions; cues ending before 0 dropped). */
export function shiftCues(cues: Cue[], offset: number): Cue[] {
  return cues.map((c) => ({ ...c, start: c.start + offset, end: c.end + offset })).filter((c) => c.end > 0);
}

/** Cues visible at media time `t` with the given offset. */
export function activeCues(cues: Cue[], t: number, offset = 0): Cue[] {
  const q = t - offset;
  const out: Cue[] = [];
  for (const c of cues) {
    if (c.start > q) break;
    if (q < c.end) out.push(c);
  }
  return out;
}

/** Strip tags except <i>/<b>/<u>, escape the rest — safe for {@html}. */
export function cueHtml(text: string): string {
  const esc = text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  return esc
    .replace(/&lt;(\/?)([ibu])&gt;/g, '<$1$2>')
    .replace(/&lt;\/?[^&]*?&gt;/g, '')
    .replace(/\n/g, '<br>');
}

export interface SubStyle {
  /** Percent, 50–200. */
  size: number;
  color: string;
  /** Background opacity 0–1 (black). */
  bg: number;
}
export const DEFAULT_SUB_STYLE: SubStyle = { size: 100, color: '#ffffff', bg: 0.5 };
