#!/usr/bin/env bash
set -euo pipefail
PKG=com.vlesscardvpn.beta2
trap 'adb shell settings put system font_scale 1.0' EXIT
for scale in 1.0 1.3; do
  adb shell settings put system font_scale "$scale"
  adb shell am force-stop "$PKG"
  adb logcat -c
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 &
  pid=$!
  found=0
  for n in $(seq 1 150); do
    if adb logcat -d -s VCIntro:I '*:S' | grep -q INTRO_VISIBLE; then
      if [ "$scale" = 1.0 ]; then dst=intro.png; else dst=intro-large-text.png; fi
      adb exec-out screencap -p > "$dst"
      test -s "$dst"
      found=1; break
    fi
    sleep 0.05
  done
  wait "$pid"
  test "$found" = 1 || { echo "Native intro was not observed"; exit 1; }
  sleep 2
done
