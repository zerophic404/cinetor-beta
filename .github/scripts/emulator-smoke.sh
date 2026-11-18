#!/usr/bin/env bash
# Emulator smoke: install APK, launch, walk main tabs, screenshot, fail on crash.
# Usage: emulator-smoke.sh <apk_dir> <shots_dir>
set -uo pipefail
APK_DIR="$1"; OUT="$2"; PKG=com.mythbrix.cinetor
mkdir -p "$OUT"
APK=$(ls "$APK_DIR"/*universal*.apk 2>/dev/null | head -1)
[ -z "$APK" ] && APK=$(ls "$APK_DIR"/*.apk | head -1)
echo "apk: $APK"
adb wait-for-device
adb shell settings put global window_animation_scale 0 || true
adb install -r -g "$APK" || { echo "install failed"; exit 1; }
adb logcat -c || true
adb logcat -G 16M >/dev/null 2>&1 || true   # big buffer: a crash stack must survive the whole run
adb logcat -v threadtime > "$OUT/logcat-live.txt" 2>&1 &
LOGCAT_PID=$!
trap 'kill ${LOGCAT_PID:-} 2>/dev/null || true' EXIT

shot() {
  sleep "${2:-3}"
  if [ "$(adb get-state 2>/dev/null || true)" != "device" ]; then
    echo "::error::Emulator offline at shot $1"
    cp "$OUT/logcat-live.txt" "$OUT/logcat.txt" 2>/dev/null || true
    exit 1
  fi
  timeout 20 adb exec-out screencap -p > "$OUT/$1.png"
  echo "shot $1"
}
alive() { [ "$(adb get-state 2>/dev/null || true)" = "device" ] && adb shell pidof "$PKG" >/dev/null 2>&1; }
ensure_fg() {
  if ! adb shell dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | grep -q "$PKG"; then
    adb shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1 || true
    sleep 1
  fi
}

adb shell am start -W -n "$PKG/.MainActivity"
shot 01-splash 1
shot 02-home 12   # gateway catalog (network)
SIZE=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1); W=${SIZE%x*}; H=${SIZE#*x}
echo "screen ${W}x${H}"
# scroll home rows (+ frame-time sample: gfxinfo janky-frame share while flinging Home)
adb shell dumpsys gfxinfo "$PKG" reset >/dev/null 2>&1 || true
for _ in 1 2 3; do adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/4)) 300; sleep 0.5; adb shell input swipe $((W/2)) $((H/4)) $((W/2)) $((H*3/4)) 300; sleep 0.5; done
adb shell dumpsys gfxinfo "$PKG" > "$OUT/gfxinfo-home.txt" 2>/dev/null || true
grep -E "Total frames rendered|Janky frames|50th percentile|90th percentile|99th percentile" "$OUT/gfxinfo-home.txt" | head -5 || true
adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/4)) 400; shot 03-home-scrolled 3
adb shell input swipe $((W/2)) $((H/4)) $((W/2)) $((H*3/4)) 400; sleep 1
# bottom bar: 5 tabs Home Anime Drama TV Me (v2.1.0)
# in-app glass bar sits above the system nav bar (~48dp) → ~89% of screen height
BAR_Y=$((H * 89 / 100))
i=0
for name in home anime drama tv me; do
  X=$(( W * (2*i + 1) / 10 ))
  adb shell input tap $X $BAR_Y
  shot "1$i-tab-$name" 4
  i=$((i+1))
done
# Tap the first UI node whose content-desc equals $1 (Compose semantics via uiautomator).
tap_desc() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 1
  local b
  b=$(adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep -m1 "content-desc=\"$1\"" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | tr '\n' ' ')
  [ -z "$b" ] && { echo "no node '$1'"; return 1; }
  set -- $b
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
# back to home + persistent top-right header Search icon check (v2.1.0)
adb shell input tap $((W/10)) $BAR_Y; sleep 2
if tap_desc "Search"; then
  shot 15-top-search 3
  adb shell input keyevent KEYCODE_BACK; sleep 2
  ensure_fg
fi
# hero tap → details (skeleton, then loaded)
adb shell input tap $((W/10)) $BAR_Y; sleep 2
adb shell input tap $((W/2)) $((H/3)); shot 20-details-loading 1
shot 21-details 4
# Play → in-place inline 16:9 player at top of DetailsScreen (never navigates to a separate screen).
PLAYED_INLINE=0
if tap_desc "Play"; then
  PLAYED_INLINE=1
  shot 30-details-inline-player 3
  shot 31-details-inline-playing 10   # real gateway stream inline in DetailsScreen
  adb logcat -d | grep -E "CinetorPlayback|playback error|tap→firstFrame" | tail -8 || true
fi
# Scroll DetailsScreen to verify Seasons/Episodes, Cast, and For You below the inline player
adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/4)) 400; shot 22-details-scrolled 2
adb shell input swipe $((W/2)) $((H/4)) $((W/2)) $((H*3/4)) 400; sleep 1
if tap_desc "Download"; then
  shot 23-download-sheet 6   # movie options or series batch download sheet
  adb shell input keyevent KEYCODE_BACK; sleep 1
fi
# Back from DetailsScreen → playback minimizes into the free-drag MiniPlayer on Home
adb shell input keyevent KEYCODE_BACK
ensure_fg
if [ "$PLAYED_INLINE" = "1" ]; then
  shot 34-mini-player 3
fi
shot 24-back-home 2
# category tab swap (Series) → hero swaps
tap_desc "Tab Series" && shot 25-home-series 4
# scroll to rows and open a poster card
adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/3)) 400; shot 26-home-rows 2
adb shell input tap $((W/6)) $((H*2/3)); shot 27-card-details 4
adb shell input keyevent KEYCODE_BACK; sleep 1
ensure_fg
# Me → Downloads → first download (if present) → details sheet
tap_prefix() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 1
  local b
  b=$(adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep -m1 "content-desc=\"$1" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | tr '\n' ' ')
  [ -z "$b" ] && { echo "no node prefix '$1'"; return 1; }
  set -- $b
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
adb shell input tap $(( W * 9 / 10 )) $BAR_Y; shot 29-me 3
if tap_desc "Downloads"; then
  shot 30-downloads 4
  if tap_prefix "Download "; then
    shot 31-download-details 3
    adb shell input swipe $((W/2)) $((H*4/5)) $((W/2)) $((H/4)) 300; shot 32-download-details-more 2
    adb shell input keyevent KEYCODE_BACK; sleep 1
  fi
  tap_desc "Tab Completed" && shot 33-downloads-completed 2
  adb shell input keyevent KEYCODE_BACK; sleep 1
  ensure_fg
fi

# mini player should have survived navigation; Home → system PiP; back to app; close mini
shot 40-end-with-mini 1
adb shell input keyevent KEYCODE_HOME; shot 42-pip 3
adb shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1; shot 43-back-from-pip 3
tap_desc "Close mini player" && shot 41-mini-closed 2

# Isolated spaces (Music, Comics, 18+) live in Me → Spaces; TV is the 4th main bottom-bar tab.
open_space() {
  tap_text "Me" || adb shell input tap $(( W * 9 / 10 )) $BAR_Y
  sleep 2
  adb shell input swipe $((W/2)) $((H/3)) $((W/2)) $((H*4/5)) 250; sleep 1
  tap_desc "Space $1" && return 0
  adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/3)) 300; sleep 1
  tap_desc "Space $1"
}

# --- Phase 4A: TV main tab (Live TV grid, EPG, PlayzTV Sports, live player zap) + Music space ---
tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 1
  local b
  b=$(adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep "text=\"$1\"" | tail -1 | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | tr '\n' ' ')
  [ -z "$b" ] && { echo "no text '$1'"; return 1; }
  set -- $b
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
if tap_text "TV" || { adb shell input tap $(( W * 7 / 10 )) $BAR_Y; true; }; then
  shot 50-tv-live 6
  adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/4)) 400; shot 51-tv-grid 2
  adb shell input swipe $((W/2)) $((H/4)) $((W/2)) $((H*3/4)) 400; sleep 1
  tap_desc "Tab Guide" && shot 52-tv-epg 5
  tap_desc "Tab Sports" && { shot 53-tv-sports 4; tap_desc "Tab Upcoming" && shot 54-tv-sports-upcoming 2; }
  tap_desc "Tab Live" && sleep 2
  if tap_desc "Watch live"; then
    shot 55-live-player 7
    adb shell input keyevent KEYCODE_CHANNEL_UP; shot 56-live-zapped 5
    adb shell input tap $((W*85/100)) $((H/8)); sleep 0.6
    tap_desc "Channel list" && { shot 57-live-channel-list 2; adb shell input keyevent KEYCODE_BACK; sleep 1; }
    adb shell input keyevent KEYCODE_BACK; sleep 2                 # → mini
    tap_desc "Close mini player"; sleep 1
  fi
  ensure_fg
fi
if open_space "Music"; then
  shot 60-music-home 6
  adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/3)) 400; sleep 2
  if tap_prefix "Album "; then
    shot 61-music-album 4
    tap_desc "Play album" && shot 62-music-mini-bar 5
    if tap_desc "Open now playing"; then
      shot 63-music-now-playing 4
      tap_desc "Queue" && { shot 64-music-queue 3; adb shell input keyevent KEYCODE_BACK; sleep 1; }
      adb shell input keyevent KEYCODE_BACK; sleep 2                # collapse to mini bar
    fi
    # navigate away: music keeps playing, mini bar stays above the nav
    tap_text "Video" && shot 65-music-mini-on-home 4
    adb logcat -d | grep -cE "CinetorPlayer.*error" || true
  fi
  ensure_fg
fi

# Phase 4b: comics reader + adult gate (separate file to keep merges with phase 4a small)
[ -f "$(dirname "$0")/smoke-phase4b.sh" ] && source "$(dirname "$0")/smoke-phase4b.sh"
[ -f "$(dirname "$0")/smoke-phase5.sh" ] && source "$(dirname "$0")/smoke-phase5.sh"
timeout 15 adb logcat -d > "$OUT/logcat.txt" || cp "$OUT/logcat-live.txt" "$OUT/logcat.txt" 2>/dev/null || true
if grep -A35 -E "FATAL EXCEPTION|E AndroidRuntime:" "$OUT/logcat.txt" | grep -q "$PKG" \
   || grep -qE "F DEBUG.*$PKG|Fatal signal.*mythbrix\.cinetor" "$OUT/logcat.txt"; then
  echo "::error::App crashed ($PKG FATAL EXCEPTION / AndroidRuntime / native crash)"
  grep -A35 -E "FATAL EXCEPTION|E AndroidRuntime:|F DEBUG|Fatal signal" "$OUT/logcat.txt" | head -80
  exit 1
elif grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then
  echo "::warning::System process FATAL EXCEPTION in logcat (not $PKG):"
  grep -A6 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -24
fi
if ! alive; then
  echo "::error::App process not alive"
  grep -A35 -E "FATAL EXCEPTION|E AndroidRuntime:|F DEBUG|ActivityManager.*$PKG" "$OUT/logcat.txt" | tail -60 || true
  exit 1
fi
echo "smoke OK"
