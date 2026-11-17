// This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer,
// scrape or hack anything without Mythbrix's explicit permission.
// Pure LRC synced-lyrics parser + O(log n) active-line lookup (zero framework/DOM imports).

export interface LyricLine {
  timeMs: number;
  text: string;
}

export interface MusicLyrics {
  plainLyrics: string | null;
  syncedLyrics: string | null;
  lines: LyricLine[];
}

const OFFSET_RE = /^\[offset:\s*([+-]?\d+)\s*\]$/i;
const STAMP_RE = /\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]/g;
const WORD_TAG_RE = /<\d{1,3}:\d{2}(?:[.:]\d{1,3})?>/g;

function fracToMs(frac: string | undefined): number {
  if (!frac) return 0;
  const n = Number(frac);
  if (!Number.isFinite(n)) return 0;
  if (frac.length === 1) return n * 100;
  if (frac.length === 2) return n * 10;
  return n;
}

/** Parse standard & multi-timestamp LRC text into sorted `{ timeMs, text }` lines. */
export function parseLrc(lrc: string | null | undefined): LyricLine[] {
  if (!lrc || !lrc.trim()) return [];
  const out: LyricLine[] = [];
  let offsetMs = 0;

  for (const rawLine of lrc.split(/\r?\n/)) {
    const trimmed = rawLine.trim();
    if (!trimmed) continue;

    const offMatch = OFFSET_RE.exec(trimmed);
    if (offMatch) {
      const parsed = Number(offMatch[1]);
      if (Number.isFinite(parsed)) offsetMs = parsed;
      continue;
    }

    const stamps: number[] = [];
    let lastIndex = 0;
    STAMP_RE.lastIndex = 0;
    let m: RegExpExecArray | null;
    while ((m = STAMP_RE.exec(trimmed)) !== null) {
      const min = Number(m[1]);
      const sec = Number(m[2]);
      const ms = fracToMs(m[3]);
      if (Number.isFinite(min) && Number.isFinite(sec)) {
        stamps.push(min * 60_000 + sec * 1_000 + ms);
      }
      lastIndex = STAMP_RE.lastIndex;
    }
    if (!stamps.length) continue;

    const text = trimmed
      .slice(lastIndex)
      .replace(WORD_TAG_RE, '')
      .replace(/\s{2,}/g, ' ')
      .trim();
    if (!text) continue;

    for (const rawMs of stamps) {
      out.push({ timeMs: Math.max(0, rawMs + offsetMs), text });
    }
  }

  return out.sort((a, b) => a.timeMs - b.timeMs);
}

/** Binary search for the index of the latest lyric line with `timeMs <= positionMs` (-1 before first line). */
export function activeLyricIndex(lines: readonly LyricLine[], positionMs: number): number {
  if (!lines.length || !Number.isFinite(positionMs) || positionMs < lines[0].timeMs) return -1;
  let lo = 0;
  let hi = lines.length - 1;
  let ans = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >>> 1;
    if (lines[mid].timeMs <= positionMs) {
      ans = mid;
      lo = mid + 1;
    } else {
      hi = mid - 1;
    }
  }
  return ans;
}

/** Clean non-empty lines for unsynced plain-text lyrics fallback. */
export function plainLyricParagraphs(plain: string | null | undefined): string[] {
  if (!plain) return [];
  return plain
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean);
}
