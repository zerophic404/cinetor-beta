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
# in-app glass bar sits above the system nav bar (~48dp) → ~89% of screen height
BAR_Y=$((H * 89 / 100))
i=0
for name in home anime drama search me; do
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
# back to home: hero tap → details (skeleton, then loaded)
adb shell input tap $((W/10)) $BAR_Y; sleep 3
adb shell input tap $((W/2)) $((H/3)); shot 20-details-loading 1
shot 21-details 4
adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/4)) 400; shot 22-details-scrolled 2
adb shell input swipe $((W/2)) $((H/4)) $((W/2)) $((H*3/4)) 400; sleep 1
if tap_desc "Download"; then
  shot 23-download-sheet 6
  # real download through the engine (options sheet → Start); fallback: close sheet
  if tap_desc "Start download" || { adb shell input swipe $((W/2)) $((H*4/5)) $((W/2)) $((H/3)) 300; sleep 1; tap_desc "Start download"; }; then
    shot 23b-download-started 3
  else
    adb shell input keyevent KEYCODE_BACK; sleep 1
  fi
fi
adb shell input keyevent KEYCODE_BACK; shot 24-back-home 2
# category tab swap (Series) → hero swaps
tap_desc "Tab Series" && shot 25-home-series 4
# scroll to rows and open a poster card
adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/3)) 400; shot 26-home-rows 2
adb shell input tap $((W/6)) $((H*2/3)); shot 27-card-details 4
adb shell input keyevent KEYCODE_BACK; sleep 1
# Me → Downloads → first download → details sheet
tap_prefix() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 1
  local b
  b=$(adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep -m1 "content-desc=\"$1" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | tr '\n' ' ')
  [ -z "$b" ] && { echo "no node prefix '$1'"; return 1; }
  set -- $b
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
adb shell input tap $(( W * 9 / 10 )) $BAR_Y; shot 29-me 3
# Downloads row ≈ 49% H on Me (fallback when the uiautomator dump misses it)
if tap_desc "Downloads" || { adb shell input tap $((W/2)) $((H * 49 / 100)); sleep 2; true; }; then
  shot 30-downloads 4
  # dump can fail while progress animates → fallback: first row ≈ 35% H
  if tap_prefix "Download " || { adb shell input tap $((W/2)) $((H * 35 / 100)); true; }; then
    shot 31-download-details 3
    adb shell input swipe $((W/2)) $((H*4/5)) $((W/2)) $((H/4)) 300; shot 32-download-details-more 2
    adb shell input keyevent KEYCODE_BACK; sleep 1
  fi
  tap_desc "Tab Completed" && shot 33-downloads-completed 2
  adb shell input keyevent KEYCODE_BACK; sleep 1
fi

adb logcat -d > "$OUT/logcat.txt" || true
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then
  echo "::error::App crashed"; grep -A30 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -60; exit 1
fi
if ! alive; then echo "::error::App process not alive"; exit 1; fi
echo "smoke OK"
