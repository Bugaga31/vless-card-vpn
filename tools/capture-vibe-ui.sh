#!/usr/bin/env bash
set -euo pipefail
PKG=com.vlesscardvpn.beta2
# Capture actual Compose UI, not an HTML approximation. No imported user secrets on this CI emulator.
adb shell settings put system font_scale 1.0
adb shell wm size 1080x2340
adb shell wm density 440
adb shell am force-stop "$PKG"
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 6
adb exec-out screencap -p > home-ocean.png
adb shell settings put system font_scale 1.3
adb shell am force-stop "$PKG"
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 4
adb exec-out screencap -p > home-large-text.png
adb shell settings put system font_scale 1.0
