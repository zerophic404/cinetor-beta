#!/usr/bin/env bash
# Emulator smoke: install APK, launch, walk main tabs, screenshot, fail on crash.
# Usage: emulator-smoke.sh <apk_dir> <shots_dir>
set -uo pipefail
APK_DIR="$1"; OUT="$2"; PKG=com.mythbrix.cinetor
mkdir -p "$OUT"
APK=$(ls "$APK_DIR"/*.apk | head -1)
adb wait-for-device
adb shell settings put global window_animation_scale 0 || true
adb install -r -g "$APK" || { echo "install failed"; exit 1; }
adb logcat -c || true

shot() { sleep "${2:-3}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }
alive() { adb shell pidof "$PKG" >/dev/null 2>&1; }

adb shell am start -W -n "$PKG/.MainActivity"
shot 01-splash 1
shot 02-home 8
SIZE=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1); W=${SIZE%x*}; H=${SIZE#*x}
echo "screen ${W}x${H}"
# scroll home rows
adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/4)) 400; shot 03-home-scrolled 3
adb shell input swipe $((W/2)) $((H/4)) $((W/2)) $((H*3/4)) 400; sleep 1
# bottom bar: 5 tabs Home Anime Drama Search Me
BAR_Y=$((H - H/30))
i=0
for name in home anime drama search me; do
  X=$(( W * (2*i + 1) / 10 ))
  adb shell input tap $X $BAR_Y
  shot "1$i-tab-$name" 4
  i=$((i+1))
done
# back to home and tap first hero/card (details)
adb shell input tap $((W/10)) $BAR_Y; sleep 3
adb shell input tap $((W/2)) $((H/3)); shot 20-after-hero-tap 4
adb shell input keyevent KEYCODE_BACK; shot 21-back 2

adb logcat -d > "$OUT/logcat.txt" || true
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then
  echo "::error::App crashed"; grep -A30 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -60; exit 1
fi
if ! alive; then echo "::error::App process not alive"; exit 1; fi
echo "smoke OK"
