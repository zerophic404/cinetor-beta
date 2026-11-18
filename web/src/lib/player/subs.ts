// This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer,
// scrape or hack anything without Mythbrix's explicit permission.
// Subtitle parsing + own cue rendering math (SRT→VTT, VTT parse, sync offset, full language names). Pure, unit tested.

export interface Cue {
  start: number;
  end: number;
  text: string;
}

export const OFFSET_LIMIT = 10;
export const OFFSET_STEP = 0.05;

export interface SubtitleLanguageChip {
  code: string;
  name: string;
}

export const POPULAR_SUBTITLE_LANGUAGES: readonly SubtitleLanguageChip[] = [
  { code: 'bn', name: 'Bangla' },
  { code: 'en', name: 'English' },
  { code: 'hi', name: 'Hindi' },
  { code: 'ur', name: 'Urdu' },
  { code: 'zh', name: 'Chinese' },
  { code: 'ar', name: 'Arabic' },
  { code: 'ko', name: 'Korean' },
  { code: 'ja', name: 'Japanese' },
  { code: 'es', name: 'Spanish' },
  { code: 'fr', name: 'French' },
  { code: 'id', name: 'Indonesian' }
];

const LANGUAGE_MAP: Record<string, string> = {
  bn: 'Bangla',
  ben: 'Bangla',
  bengali: 'Bangla',
  bangla: 'Bangla',
  en: 'English',
  eng: 'English',
  english: 'English',
  hi: 'Hindi',
  hin: 'Hindi',
  hindi: 'Hindi',
  ur: 'Urdu',
  urd: 'Urdu',
  urdu: 'Urdu',
  zh: 'Chinese',
  chi: 'Chinese',
  zho: 'Chinese',
  cmn: 'Chinese',
  yue: 'Cantonese',
  chinese: 'Chinese',
  mandarin: 'Chinese',
  ar: 'Arabic',
  ara: 'Arabic',
  arabic: 'Arabic',
  ko: 'Korean',
  kor: 'Korean',
  korean: 'Korean',
  ja: 'Japanese',
  jpn: 'Japanese',
  japanese: 'Japanese',
  es: 'Spanish',
  spa: 'Spanish',
  spanish: 'Spanish',
  fr: 'French',
  fre: 'French',
  fra: 'French',
  french: 'French',
  id: 'Indonesian',
  ind: 'Indonesian',
  in: 'Indonesian',
  indonesian: 'Indonesian',
  tr: 'Turkish',
  tur: 'Turkish',
  turkish: 'Turkish',
  ta: 'Tamil',
  tam: 'Tamil',
  tamil: 'Tamil',
  te: 'Telugu',
  tel: 'Telugu',
  telugu: 'Telugu',
  ml: 'Malayalam',
  mal: 'Malayalam',
  malayalam: 'Malayalam',
  pa: 'Punjabi',
  pan: 'Punjabi',
  punjabi: 'Punjabi',
  ru: 'Russian',
  rus: 'Russian',
  russian: 'Russian',
  pt: 'Portuguese',
  por: 'Portuguese',
  portuguese: 'Portuguese',
  ms: 'Malay',
  may: 'Malay',
  msa: 'Malay',
  malay: 'Malay',
  th: 'Thai',
  tha: 'Thai',
  thai: 'Thai',
  vi: 'Vietnamese',
  vie: 'Vietnamese',
  vietnamese: 'Vietnamese',
  tl: 'Filipino',
  fil: 'Filipino',
  tgl: 'Filipino',
  tagalog: 'Filipino',
  filipino: 'Filipino',
  de: 'German',
  ger: 'German',
  deu: 'German',
  german: 'German',
  it: 'Italian',
  ita: 'Italian',
  italian: 'Italian',
  fa: 'Persian',
  per: 'Persian',
  fas: 'Persian',
  persian: 'Persian',
  nl: 'Dutch',
  dut: 'Dutch',
  nld: 'Dutch',
  dutch: 'Dutch',
  pl: 'Polish',
  pol: 'Polish',
  polish: 'Polish',
  uk: 'Ukrainian',
  ukr: 'Ukrainian',
  ukrainian: 'Ukrainian',
  el: 'Greek',
  gre: 'Greek',
  ell: 'Greek',
  greek: 'Greek',
  he: 'Hebrew',
  heb: 'Hebrew',
  iw: 'Hebrew',
  hebrew: 'Hebrew',
  sv: 'Swedish',
  swe: 'Swedish',
  swedish: 'Swedish',
  no: 'Norwegian',
  nor: 'Norwegian',
  nb: 'Norwegian',
  nn: 'Norwegian',
  norwegian: 'Norwegian',
  da: 'Danish',
  dan: 'Danish',
  danish: 'Danish',
  fi: 'Finnish',
  fin: 'Finnish',
  finnish: 'Finnish',
  ro: 'Romanian',
  rum: 'Romanian',
  ron: 'Romanian',
  romanian: 'Romanian',
  hu: 'Hungarian',
  hun: 'Hungarian',
  hungarian: 'Hungarian',
  cs: 'Czech',
  cze: 'Czech',
  ces: 'Czech',
  czech: 'Czech',
  my: 'Burmese',
  bur: 'Burmese',
  mya: 'Burmese',
  burmese: 'Burmese',
  ne: 'Nepali',
  nep: 'Nepali',
  nepali: 'Nepali',
  si: 'Sinhala',
  sin: 'Sinhala',
  sinhala: 'Sinhala',
  mr: 'Marathi',
  mar: 'Marathi',
  marathi: 'Marathi',
  gu: 'Gujarati',
  guj: 'Gujarati',
  gujarati: 'Gujarati',
  kn: 'Kannada',
  kan: 'Kannada',
  kannada: 'Kannada',
  or: 'Odia',
  ori: 'Odia',
  odia: 'Odia',
  as: 'Assamese',
  asm: 'Assamese',
  assamese: 'Assamese',
  original: 'Original'
};

const GENERIC_TRACK_RE = /^(?:subtitle|audio|track)\s*\d+$/i;
const PAREN_CODE_RE = /\(([A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,4})?)\)$/;
const NON_AUDIO_DUB_TOKEN_RE =
  /^(?:\d{3,4}p(?:\s*(?:hd|fhd|qhd|uhd))?|4k(?:\s*uhd)?|2k(?:\s*qhd)?|auto|hd|sd|fhd|qhd|uhd|high[ -]?speed|bdix|mp4|mkv|m4v|avi|webm|mov|ts|hls|dash|h\.?264|h\.?265|x264|x265|hevc|av1|aac|flac|mp3|youtube|direct|proxy|gateway(?:\s*proxy)?|server\s*\d*|source\s*\d*|[\d.]+\s*[kmgt]?b)$/i;

function titleCaseFirst(s: string): string {
  return s ? s.charAt(0).toUpperCase() + s.slice(1) : s;
}

/** Pure mapper converting any ISO code or variant into a full human language name (never 2/3-letter codes). */
export function fullLanguageName(raw: string | undefined | null): string {
  const cleaned = (raw ?? '').trim();
  if (!cleaned) return 'Original';
  const lower = cleaned.toLowerCase();
  if (lower === 'und' || lower === 'unknown' || lower === 'mul' || lower === 'zxx' || lower === 'default') {
    return 'Original';
  }
  if (LANGUAGE_MAP[lower]) return LANGUAGE_MAP[lower];
  const primary = lower.split(/[-_\s]/)[0] ?? '';
  if (LANGUAGE_MAP[primary]) return LANGUAGE_MAP[primary];
  if (/^[a-z]{2,3}$/.test(primary)) {
    try {
      const disp = new Intl.DisplayNames(['en'], { type: 'language' }).of(primary)?.trim() ?? '';
      if (disp.length > 3 && disp.toLowerCase() !== primary) {
        return disp.toLowerCase() === 'bengali' ? 'Bangla' : titleCaseFirst(disp);
      }
    } catch {
      /* ignore */
    }
    return 'Original';
  }
  return titleCaseFirst(cleaned);
}

/** Formats any audio/subtitle track label so short codes (`bn`, `en`, `Subtitle 1`) become full human language words. */
export function formatTrackLanguage(
  label?: string | null,
  language?: string | null,
  fallback = 'Original'
): string {
  const rawLabel = (label ?? '').trim();
  const rawLang = (language ?? '').trim();
  if (!rawLabel || GENERIC_TRACK_RE.test(rawLabel) || rawLabel.toLowerCase() === 'und') {
    return rawLang ? fullLanguageName(rawLang) : fallback;
  }
  if (/^[A-Za-z]{2,3}$/.test(rawLabel)) {
    return fullLanguageName(rawLang || rawLabel);
  }
  const paren = PAREN_CODE_RE.exec(rawLabel);
  if (paren) {
    const full = fullLanguageName(paren[1]);
    const prefix = rawLabel.slice(0, paren.index).trim();
    return prefix.toLowerCase() === full.toLowerCase() ? full : `${prefix} (${full})`;
  }
  const mapped = LANGUAGE_MAP[rawLabel.toLowerCase()];
  return mapped ?? rawLabel;
}

/** Extracts a clean human dub/audio language (`'Hindi'`, `'Bangla'`, `'English'`, `'Original'`) from a stream source. */
export function audioDubOfSource(
  source?: { label?: string | null; audioLang?: string | null } | null
): string | null {
  const explicit = source?.audioLang?.trim();
  if (explicit) {
    return /^dual\s*audio$/i.test(explicit) ? 'Dual Audio' : fullLanguageName(explicit);
  }
  const label = source?.label?.trim();
  if (!label) return null;
  for (const raw of label.split(/[·|/]/)) {
    const cleaned = raw.replace(/^[\s[\]()]+|[\s[\]()]+$/g, '');
    if (!cleaned || NON_AUDIO_DUB_TOKEN_RE.test(cleaned)) continue;
    if (/^(?:dual|multi)[ -]?audio$/i.test(cleaned)) return 'Dual Audio';
    return fullLanguageName(cleaned);
  }
  return null;
}

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
  /** Background opacity 0–1 (default 0 = completely transparent). */
  bg: number;
}
export const DEFAULT_SUB_STYLE: SubStyle = { size: 100, color: '#ffffff', bg: 0 };
