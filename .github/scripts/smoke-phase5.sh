#!/usr/bin/env bash
# Phase 5 smoke (sourced by emulator-smoke.sh; uses its shot/tap_desc/tap_text/W/H/BAR_Y).
# Me → profiles (create a 2nd Kids profile) → Library → Settings (Ember theme, back to Noir) → About. Shots 70-79.

hide_kb() {
  adb shell dumpsys input_method 2>/dev/null | grep -q "mInputShown=true" && adb shell input keyevent KEYCODE_BACK
  sleep 1
}

# Emulator flake: system "Pixel Launcher isn't responding" dialog covers the app and breaks uiautomator lookups.
dismiss_anr() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 0
  if adb shell cat /sdcard/ui.xml | grep -q "t responding"; then
    echo "ANR dialog → Wait"; tap_text "Wait" || adb shell input keyevent KEYCODE_BACK; sleep 2
  fi
}

echo "== phase 5: profiles =="
dismiss_anr
tap_desc "Open spaces" && sleep 1 && { tap_text "Video" || adb shell input keyevent KEYCODE_BACK; }; sleep 2
adb shell input tap $(( W * 9 / 10 )) $BAR_Y; shot 70-me-profiles 3
if tap_desc "Switch profile"; then
  shot 71-profile-picker 2
  if tap_desc "Add profile"; then
    sleep 2
    if tap_desc "Profile name"; then
      sleep 1; adb shell input text "Kids"; sleep 1
      hide_kb
    fi
    tap_desc "Avatar 3"; sleep 0.5
    tap_desc "Colour 5"; sleep 0.5
    tap_desc "Kids profile"; shot 72-edit-profile-kids 1
    tap_desc "Save profile"; shot 73-picker-two-profiles 3
  fi
  adb shell input keyevent KEYCODE_BACK; sleep 2                    # picker → Me
fi
if tap_desc "Library"; then
  shot 74-library 3
  tap_desc "Library History" && shot 75-library-history 2
  adb shell input keyevent KEYCODE_BACK; sleep 2
fi

echo "== phase 5: settings =="
dismiss_anr
if tap_desc "Settings"; then
  shot 76-settings-top 2
  if tap_desc "Theme Ember"; then
    shot 77-settings-ember 3                                       # live theme switch, no restart
    tap_desc "Theme Noir"; sleep 2
  fi
  adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/4)) 300; shot 78-settings-playback 2
  adb shell input keyevent KEYCODE_BACK; sleep 2
fi

echo "== phase 5: about =="
dismiss_anr
if tap_desc "About"; then
  shot 79-about 4
  adb shell input keyevent KEYCODE_BACK; sleep 1
fi
