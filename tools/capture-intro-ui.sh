#!/usr/bin/env bash
set -euo pipefail
PKG=com.vlesscardvpn.beta2
trap 'adb shell settings put system font_scale 1.0; adb shell settings put system accelerometer_rotation 1; adb shell wm size reset; adb shell wm density reset' EXIT
# Warm Activity resume tests the real "every opening" path; no test-only APK flag or delayed fake intro.
adb shell wm size reset
adb shell wm density reset
physical_size=$(adb shell wm size | tr -d '\r' | sed -n 's/^Physical size: //p')
physical_height=${physical_size#*x}
density=$(adb shell wm density | tr -d '\r' | sed -n 's/^Physical density: //p')
test -n "$physical_height" && test -n "$density"
for scale in 1.0 1.3 2.0; do
  adb shell settings put system font_scale "$scale"
  adb shell settings put system accelerometer_rotation 0
  if [[ "$scale" = 2.0 ]]; then adb shell wm size "$((320 * density / 160))x$physical_height"; else adb shell wm size reset; fi
  adb shell am start -a android.settings.SETTINGS >/dev/null
  sleep 1
  adb logcat -c
  adb shell am start -n "$PKG/com.vlesscardvpn.MainActivity" >/dev/null &
  pid=$!
  found=0
  for n in $(seq 1 120); do
    if adb logcat -d -s VCIntro:I '*:S' | grep -q INTRO_VISIBLE; then
      if [[ "$scale" = 1.0 ]]; then dst=intro.png
      elif [[ "$scale" = 1.3 ]]; then dst=intro-large-text.png
      else dst=intro-320dp-font-scale-2.png; fi
      bash tools/assert-vibe-foreground.sh
      adb exec-out screencap -p > "$dst"
      test -s "$dst"
      found=1; break
    fi
    sleep 0.05
  done
  wait "$pid"
  test "$found" = 1 || { echo 'Native intro on foreground return was not observed'; exit 1; }
  sleep 3
  bash tools/assert-vibe-foreground.sh
  adb shell uiautomator dump /sdcard/window.xml >/dev/null
  adb shell cat /sdcard/window.xml | grep -q 'Готовы к подключению' || { echo 'Intro did not return to the home screen'; exit 1; }
done
