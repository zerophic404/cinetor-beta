#!/usr/bin/env bash
# Android TV emulator smoke (Task 6.2): D-pad only (adb keyevents), screenshots of TV home,
# side nav, rows, details, back-restore, player (+ seek OSD, controls). Fails on crash.
# Usage: tv-smoke.sh <apk_dir> <shots_dir>
set -uo pipefail
APK_DIR="$1"; OUT="$2"; PKG=com.mythbrix.cinetor
mkdir -p "$OUT"
APK=$(ls "$APK_DIR"/*.apk | grep -v -- '-x86_64\|-arm' | head -1)
[ -z "$APK" ] && APK=$(ls "$APK_DIR"/*.apk | head -1)
echo "apk: $APK"
adb wait-for-device
adb shell settings put global window_animation_scale 0 || true
adb shell getprop ro.build.characteristics || true
adb shell pm list features | grep -E 'leanback|touchscreen' || true
adb install -r -g "$APK" || { echo "install failed"; exit 1; }
adb logcat -c || true

shot() { sleep "${2:-3}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }
key() { for k in "$@"; do adb shell input keyevent "$k"; sleep 0.7; done; }
alive() { adb shell pidof "$PKG" >/dev/null 2>&1; }
check() {
  if ! alive; then
    echo "APP DIED at $1"
    adb logcat -d | grep -A 30 'FATAL EXCEPTION' | head -60
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
key KEYCODE_BACK; shot 12-tv-home-restored 3
check back
# Up to the hero, OK on Play → TV player.
key KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_UP; sleep 1
shot 13-tv-hero-focus 1
key KEYCODE_DPAD_CENTER; shot 20-tv-player 8
anr; check player
shot 21-tv-player-hidden 5
key KEYCODE_DPAD_RIGHT; shot 22-tv-player-seek 0.5
# hold Right (repeats) → accelerating scrub
adb shell input keyevent --longpress KEYCODE_DPAD_RIGHT 2>/dev/null || true
shot 23-tv-player-scrub 0.3
sleep 2
key KEYCODE_DPAD_UP; shot 24-tv-player-controls 1
key KEYCODE_BACK; sleep 1
key KEYCODE_DPAD_DOWN; shot 25-tv-player-rail 1
key KEYCODE_BACK; sleep 1
key KEYCODE_MENU; shot 26-tv-player-settings 2
key KEYCODE_BACK; sleep 1
key KEYCODE_BACK; shot 27-tv-after-player 3
check after-player
# Menu → Settings; Search reachable from the side nav.
key KEYCODE_MENU; shot 30-tv-settings 3
key KEYCODE_BACK; sleep 1
key KEYCODE_DPAD_LEFT; sleep 0.5
adb shell input keyevent KEYCODE_DPAD_UP; adb shell input keyevent KEYCODE_DPAD_UP; adb shell input keyevent KEYCODE_DPAD_UP
key KEYCODE_DPAD_CENTER; shot 31-tv-search 3
check search
adb logcat -d | grep -E 'FATAL EXCEPTION|AndroidRuntime' | head -20 || true
echo "TV smoke OK"
