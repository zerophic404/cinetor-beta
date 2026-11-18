#!/usr/bin/env bash
# Android TV emulator smoke (Task 6.2): D-pad only (adb keyevents), screenshots of TV home,
# side nav, rows, details, back-restore, player (+ seek OSD, controls). Fails on crash.
# Usage: tv-smoke.sh <apk_dir> <shots_dir>
set -uo pipefail
APK_DIR="$1"; OUT="$2"; PKG=com.mythbrix.cinetor
mkdir -p "$OUT"
APK=$(ls "$APK_DIR"/*universal*.apk 2>/dev/null | head -1)
[ -z "$APK" ] && APK=$(ls "$APK_DIR"/*.apk | head -1)
echo "apk: $APK"
adb wait-for-device
adb shell settings put global window_animation_scale 0 || true
adb shell getprop ro.build.characteristics || true
adb shell pm list features | grep -E 'leanback|touchscreen' || true
adb install -r -g "$APK" || { echo "install failed"; exit 1; }
adb logcat -c || true
adb logcat -G 16M >/dev/null 2>&1 || true   # preserve full run logcat for crash assertions
adb logcat -v threadtime > "$OUT/logcat-live.txt" 2>&1 &
LOGCAT_PID=$!
trap 'kill ${LOGCAT_PID:-} 2>/dev/null || true' EXIT

shot() {
  sleep "${2:-3}"
  if [ "$(adb get-state 2>/dev/null || true)" != "device" ]; then
    echo "::error::TV emulator offline at shot $1"
    cp "$OUT/logcat-live.txt" "$OUT/logcat.txt" 2>/dev/null || true
    exit 1
  fi
  timeout 20 adb exec-out screencap -p > "$OUT/$1.png"
  echo "shot $1"
}
key() { for k in "$@"; do adb shell input keyevent "$k"; sleep 0.7; done; }
alive() { [ "$(adb get-state 2>/dev/null || true)" = "device" ] && adb shell pidof "$PKG" >/dev/null 2>&1; }
ensure_fg() {
  if ! adb shell dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | grep -q "$PKG"; then
    adb shell monkey -p "$PKG" -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1 \
      || adb shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1 || true
    sleep 1
  fi
}
check() {
  local lc
  lc=$(timeout 10 adb logcat -d 2>/dev/null || true)
  if ! alive || echo "$lc" | grep -A35 -E 'FATAL EXCEPTION|E AndroidRuntime:' | grep -q "$PKG" \
     || echo "$lc" | grep -qE "F DEBUG.*$PKG|Fatal signal.*mythbrix\.cinetor"; then
    echo "::error::TV app crashed or died at $1"
    echo "$lc" > "$OUT/logcat.txt" || true
    echo "$lc" | grep -A35 -E 'FATAL EXCEPTION|E AndroidRuntime:|F DEBUG|Fatal signal' | head -80 || true
    exit 1
  fi
}
# Dismiss a system ANR dialog if the slow emulator shows one ("Wait").
anr() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 0
  if adb shell cat /sdcard/ui.xml | grep -q 'isn.t responding'; then
    echo "ANR dialog → Wait"; adb shell input keyevent KEYCODE_DPAD_DOWN; adb shell input keyevent KEYCODE_ENTER
  fi
}

# Launch through the leanback launcher entry (same activity).
adb shell monkey -p "$PKG" -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1 \
  || adb shell am start -W -n "$PKG/.MainActivity"
shot 01-tv-splash 2
shot 02-tv-home 12
anr; check home
# Hero Play is focused first. Left → side nav expands (glass, labels).
key KEYCODE_DPAD_LEFT; shot 03-tv-sidenav 1
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN; shot 04-tv-sidenav-anime 1
key KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_RIGHT; sleep 1
# Down into the rows: focus scale + glow + accent border.
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN; shot 05-tv-row-focus 1
key KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_DOWN; shot 06-tv-row2-focus 1
check rows
# OK → details; Back → home with the same card focused (FocusMemory).
key KEYCODE_DPAD_CENTER; shot 10-tv-details 5
anr; check details
key KEYCODE_DPAD_DOWN; shot 11-tv-details-focus 1
key KEYCODE_BACK; ensure_fg; shot 12-tv-home-restored 3
check back
# Up to the hero, OK on Play → TV player.
key KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_UP; sleep 1
shot 13-tv-hero-focus 1
key KEYCODE_DPAD_CENTER; shot 20-tv-player 8
anr; check player
shot 21-tv-player-hidden 5
adb shell input keyevent KEYCODE_DPAD_RIGHT; shot 22-tv-player-seek 0.2
# hold Right (repeats) → accelerating scrub
adb shell input keyevent --longpress KEYCODE_DPAD_RIGHT 2>/dev/null || true
shot 23-tv-player-scrub 0.2
sleep 2
# Up → controls (settings row focused); Back hides controls (player stays). One input call = no JVM gap.
adb shell input keyevent KEYCODE_DPAD_UP; shot 24-tv-player-controls 0.3
# adb input + 1080p screencap take seconds on this emulator → controls may have auto-hidden (6 s) by now.
# One input call: UP (re-shows if hidden; no-op focus move if shown) then BACK (hides; player must stay).
adb shell input keyevent KEYCODE_DPAD_UP KEYCODE_BACK; ensure_fg; shot 25-tv-player-controls-hidden 1
check back-hides-controls
adb shell input keyevent KEYCODE_DPAD_DOWN; shot 26-tv-player-rail 2
adb shell input keyevent KEYCODE_BACK; sleep 1; ensure_fg
adb shell input keyevent KEYCODE_MENU; shot 27-tv-player-settings 2
adb shell input keyevent KEYCODE_BACK; sleep 2; ensure_fg
# controls hidden → Back exits the player; home focus restored (hero Play)
adb shell input keyevent KEYCODE_BACK; ensure_fg; shot 28-tv-after-player 3
check after-player
# Menu → Settings; Search reachable from the side nav.
key KEYCODE_MENU; shot 30-tv-settings 3
check settings
key KEYCODE_BACK; sleep 1; ensure_fg
key KEYCODE_DPAD_LEFT; sleep 0.5
adb shell input keyevent KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_UP
key KEYCODE_DPAD_CENTER; shot 31-tv-search 3
check search
# v2.1.0 TvSideNav order (Profiles removed):
# Primary: Search (0) · Home (1) · Anime (2) · Drama (3) · TV (4)
# Spaces:  Music (5) · Comics (6) [· 18+ when enabled]
# More:    Downloads (7) · Library (8) · Settings (9)
key KEYCODE_DPAD_LEFT; sleep 0.5
adb shell input keyevent KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_UP
# Step down 5 from Search (0) → Music (5) in Spaces group, then Comics (6), verifying Spaces & More groups
adb shell input keyevent KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN
shot 40-tv-sidenav-spaces 1
check sidenav-spaces
# Step up 1 from Music (5) → TV (4) in primary group and open Live TV & Sports
key KEYCODE_DPAD_UP
key KEYCODE_DPAD_CENTER; shot 41-tv-live-tv 6
anr; check live-tv
timeout 15 adb logcat -d > "$OUT/logcat.txt" || cp "$OUT/logcat-live.txt" "$OUT/logcat.txt" 2>/dev/null || true
if grep -A35 -E 'FATAL EXCEPTION|E AndroidRuntime:' "$OUT/logcat.txt" | grep -q "$PKG" \
   || grep -qE "F DEBUG.*$PKG|Fatal signal.*mythbrix\.cinetor" "$OUT/logcat.txt"; then
  echo "::error::TV app crashed ($PKG FATAL EXCEPTION / AndroidRuntime / native crash)"
  grep -A35 -E 'FATAL EXCEPTION|E AndroidRuntime:|F DEBUG|Fatal signal' "$OUT/logcat.txt" | head -80
  exit 1
elif grep -q 'FATAL EXCEPTION' "$OUT/logcat.txt"; then
  echo "::warning::System process FATAL EXCEPTION in TV logcat (not $PKG):"
  grep -A6 'FATAL EXCEPTION' "$OUT/logcat.txt" | head -24
fi
if ! alive; then echo "::error::TV app process not alive at end of smoke"; exit 1; fi
echo "TV smoke OK"
