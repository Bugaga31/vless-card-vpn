#!/usr/bin/env bash
set -euo pipefail
PKG=com.vlesscardvpn.beta2
trap 'adb shell settings put system font_scale 1.0' EXIT
# Capture actual Compose UI. Bounded retries only; never publish a blank frame.
adb shell wm size reset
adb shell wm density reset
sleep 3
for scale in 1.0 1.3; do
  adb shell settings put system font_scale "$scale"
  adb shell am force-stop "$PKG"
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  if [ "$scale" = 1.0 ]; then dst=home-ocean.png; else dst=home-large-text.png; fi
  found=0
  for n in $(seq 1 15); do
    sleep 2
    # An intro frame is not a ready home frame. Require the Compose hierarchy too.
    adb shell uiautomator dump /sdcard/vcard-ui.xml >/dev/null 2>&1 || continue
    adb shell cat /sdcard/vcard-ui.xml > /tmp/vcard-ui.xml
    adb exec-out screencap -p > "$dst"
    if python3 tools/release-gates/check_screenshot.py "$dst" && python3 - "$PKG" <<'PY'
import sys, xml.etree.ElementTree as E
r=E.parse('/tmp/vcard-ui.xml').getroot()
nodes=[n for n in r.iter('node') if n.get('package')==sys.argv[1] and (n.get('text') or n.get('content-desc'))]
raise SystemExit(0 if len(nodes)>=4 else 1)
PY
    then found=1; break; fi
  done
  test "$found" = 1 || { echo 'Home UI was not visibly ready; release capture failed'; exit 1; }
done
