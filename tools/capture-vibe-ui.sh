#!/usr/bin/env bash
set -euo pipefail
PKG=com.vlesscardvpn.beta2
trap 'timeout 5s adb shell settings put system font_scale 1.0 || true' EXIT
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
  last_operation=waiting
  for n in $(seq 1 15); do
    sleep 2
    # An intro frame is not a ready home frame. Require the Compose hierarchy too.
    # Remove stale files first; failed adb calls must not leave accepted old evidence.
    rm -f /tmp/vcard-ui.xml "$dst"
    last_operation=hierarchy-dump
    timeout 12s adb shell uiautomator dump /sdcard/vcard-ui.xml >/dev/null 2>&1 || continue
    last_operation=hierarchy-read
    timeout 5s adb shell cat /sdcard/vcard-ui.xml > /tmp/vcard-ui.xml || continue
    # A fresh launch may still show the real, skippable intro. Use its exact app-owned
    # enabled button, not fixed coordinates or a hidden app preference/debug bypass.
    if xy=$(python3 tools/release-gates/intro_button.py /tmp/vcard-ui.xml "$PKG"); then
      last_operation=intro-dismiss
      read -r x y <<< "$xy"
      timeout 5s adb shell input tap "$x" "$y" || continue
      continue
    fi
    last_operation=screenshot
    timeout 5s adb exec-out screencap -p > "$dst" || continue
    last_operation=home-evidence
    if python3 tools/release-gates/check_screenshot.py "$dst" && python3 tools/release-gates/check_home_hierarchy.py /tmp/vcard-ui.xml "$PKG"
    then found=1; break; fi
  done
  if [ "$found" != 1 ]; then
    python3 - "$dst" "$PKG" "$last_operation" <<'PYDIAG'
import json,sys,xml.etree.ElementTree as E
from pathlib import Path
sys.path.insert(0,'tools/release-gates')
from check_screenshot import check_png
r={'last_operation':sys.argv[3],'app_nodes':0,'app_label_nodes':0,'system_dialog':False,'png_nonblank':False}
try:
    nodes=list(E.parse('/tmp/vcard-ui.xml').getroot().iter('node'))
    r['app_nodes']=sum(n.get('package')==sys.argv[2] for n in nodes)
    r['app_label_nodes']=sum(n.get('package')==sys.argv[2] and bool(n.get('text') or n.get('content-desc')) for n in nodes)
    r['system_dialog']=any('permissioncontroller' in n.get('package','') for n in nodes)
except (OSError,E.ParseError):pass
try:check_png(Path(sys.argv[1]).read_bytes());r['png_nonblank']=True
except Exception:pass
print('::notice title=Release UI bounded diagnostic::'+json.dumps(r,separators=(',',':')))
PYDIAG
    echo '::error title=Release home UI::Home hierarchy or nonblank screencap not ready after bounded wait'
    exit 1
  fi
done
