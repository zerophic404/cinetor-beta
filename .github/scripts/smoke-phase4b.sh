#!/usr/bin/env bash
# Phase 4b smoke (sourced by emulator-smoke.sh; uses its shot/tap_desc/W/H/BAR_Y/PKG/OUT).
# Comics: home → manga details → RTL paged reader → manhwa vertical reader.
# Adult: Settings toggle → 18+ confirm → set PIN → switcher shows Adult → PIN screen → wrong → right (FLAG_SECURE).

tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 1
  local b
  b=$(adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep -m1 "text=\"$1\"" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | tr '\n' ' ')
  [ -z "$b" ] && { echo "no text '$1'"; return 1; }
  set -- $b
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}

# Types a PIN on the glass pad: one uiautomator dump, then taps "PIN key N" for each digit.
pin_enter() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 1
  local xml d b
  xml=$(adb shell cat /sdcard/ui.xml | tr '>' '\n')
  for d in $(echo "$1" | grep -o .); do
    b=$(echo "$xml" | grep -m1 "content-desc=\"PIN key $d\"" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | tr '\n' ' ')
    [ -z "$b" ] && { echo "no PIN key $d"; return 1; }
    set -- $b
    adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
    sleep 0.3
  done
}

echo "== phase 4b: comics =="
tap_desc "Open spaces" && sleep 1 && tap_text "Comics" && shot 50-comics-home 5
adb shell input tap $((W/2)) $((H/3)); shot 51-comic-details-loading 1
shot 52-comic-details 3
if tap_desc "Read"; then
  shot 53-reader-manga-rtl 4
  # RTL: swipe left→right turns to the NEXT page
  adb shell input swipe $((W/5)) $((H/2)) $((W*4/5)) $((H/2)) 250; shot 54-reader-manga-page2 3
  adb shell input tap $((W/2)) $((H/2)); shot 55-reader-manga-ui 2
  tap_desc "Reader settings" && shot 56-reader-settings 2
  adb shell input keyevent KEYCODE_BACK; sleep 1                    # close reader
fi
shot 57-comic-details-progress 2
adb shell input keyevent KEYCODE_BACK; sleep 2                      # details → comics home
if tap_desc "Tab Manhwa"; then
  shot 58-comics-manhwa 4
  adb shell input tap $((W/2)) $((H/3)); sleep 4
  if tap_desc "Read"; then
    shot 59-reader-manhwa-vertical 4
    adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/4)) 300; shot 60-reader-manhwa-scrolled 3
    adb shell input keyevent KEYCODE_BACK; sleep 1
  fi
  adb shell input keyevent KEYCODE_BACK; sleep 2
fi

echo "== phase 4b: adult gate =="
tap_desc "Open spaces" && sleep 1 && tap_text "Video"; sleep 2
adb shell input tap $(( W * 9 / 10 )) $BAR_Y; sleep 2               # Me
if tap_desc "Settings" || tap_text "Settings"; then
  sleep 2
  if tap_desc "Toggle 18+ section"; then
    shot 61-adult-age-confirm 2
    if tap_desc "I am 18 or older" || tap_text "I am 18+"; then
      shot 62-adult-set-pin 2
      pin_enter 1234; sleep 1
      shot 63-adult-confirm-pin 1
      pin_enter 1234; sleep 2
      shot 64-settings-adult-on 1
    fi
  fi
  adb shell input keyevent KEYCODE_BACK; sleep 1
fi
if tap_desc "Open spaces"; then
  shot 65-switcher-with-adult 2
  if tap_text "Adult"; then
    shot 66-adult-pin 3
    pin_enter 0000; shot 67-adult-pin-wrong 1
    sleep 1
    pin_enter 1234; shot 68-adult-unlocked-secure 4                  # FLAG_SECURE → black capture expected
    adb logcat -d | grep -iE "FLAG_SECURE|SurfaceFlinger.*secure" | tail -3 || true
    tap_desc "Open spaces" && sleep 1 && tap_text "Video"; sleep 2
  fi
fi
shot 69-phase4b-end 1
