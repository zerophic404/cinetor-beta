#!/usr/bin/env bash
# Phase 5 smoke (sourced by emulator-smoke.sh; uses its shot/tap_desc/tap_text/W/H/BAR_Y).
# Me (single profile, Switch profile removed in v2.1.0) → Library → simplified Cinetor Beta Settings → About ("All rights reserved Mythbrix"). Shots 70-79.

# Fresh uiautomator dump (old file removed → never a stale tree), retried; prints the error when it fails.
dump_ui() {
  local i out
  for i in 1 2 3; do
    adb shell rm -f /sdcard/ui.xml >/dev/null 2>&1
    out=$(adb shell uiautomator dump /sdcard/ui.xml 2>&1)
    if adb shell ls /sdcard/ui.xml >/dev/null 2>&1; then return 0; fi
    echo "dump failed ($i): $out"; sleep 2
  done
  return 1
}
bounds_of() { # $1 = attr (content-desc|text), $2 = value
  adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep -m1 "$1=\"$2\"" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | tr '\n' ' '
}
tap_desc() {
  dump_ui || return 1
  local b; b=$(bounds_of content-desc "$1")
  [ -z "$b" ] && { echo "no node '$1'"; return 1; }
  set -- $b; adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
tap_text() {
  dump_ui || return 1
  local b; b=$(bounds_of text "$1")
  [ -z "$b" ] && { echo "no text '$1'"; return 1; }
  set -- $b; adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}

hide_kb() {
  adb shell dumpsys input_method 2>/dev/null | grep -q "mInputShown=true" && adb shell input keyevent KEYCODE_BACK
  sleep 1
}

# Emulator flake: system "Pixel Launcher isn't responding" dialog covers the app and breaks uiautomator lookups.
dismiss_anr() {
  dump_ui || return 0
  if adb shell cat /sdcard/ui.xml | grep -q "t responding"; then
    echo "ANR dialog → Wait"; tap_text "Wait" || adb shell input keyevent KEYCODE_BACK; sleep 2
  fi
}

echo "== phase 5: me & library =="
dismiss_anr
ensure_fg
# Back to the video shell from wherever 4b ended (an isolated space has no Me tab).
for i in 1 2 3 4; do
  dump_ui || break
  if adb shell cat /sdcard/ui.xml | grep -q 'text="Me"'; then break; fi
  echo "not in video shell (try $i)"
  tap_text "Video" || adb shell input keyevent KEYCODE_BACK
  sleep 2
  ensure_fg
done
adb shell input tap $(( W * 9 / 10 )) $BAR_Y; sleep 1
adb shell input swipe $((W/2)) $((H/3)) $((W/2)) $((H*4/5)) 250
shot 70-me-profile 3
if tap_desc "Library" || tap_text "Library"; then
  shot 74-library 3
  tap_desc "Library History" && shot 75-library-history 2
  adb shell input keyevent KEYCODE_BACK; sleep 2
  ensure_fg
fi

find_on_me() {
  local i
  for i in 1 2 3 4 5; do
    tap_desc "$1" && return 0
    tap_text "$1" && return 0
    adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/3)) 300; sleep 1
  done
  return 1
}

echo "== phase 5: settings =="
dismiss_anr
if find_on_me "Settings"; then
  shot 76-settings-top 2
  if tap_desc "Theme Ember"; then
    shot 77-settings-ember 3                                       # live theme switch, no restart
    tap_desc "Theme Noir"; sleep 2
  fi
  adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/4)) 300; shot 78-settings-privacy 2
  adb shell input keyevent KEYCODE_BACK; sleep 2
  ensure_fg
fi

echo "== phase 5: about =="
dismiss_anr
if find_on_me "About"; then
  shot 79-about 4
  if dump_ui && adb shell cat /sdcard/ui.xml | grep -qi "All rights reserved Mythbrix"; then
    echo "About verified: All rights reserved Mythbrix"
  fi
  adb shell input keyevent KEYCODE_BACK; sleep 1
  ensure_fg
fi
