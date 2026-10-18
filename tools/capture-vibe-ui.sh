#!/usr/bin/env bash
set -euo pipefail
PKG=com.vlesscardvpn.beta2
# Capture actual Compose UI, not an HTML approximation. No imported user secrets on this CI emulator.
adb shell settings put system font_scale 1.0
# Respect the emulator's real screen and density; overrides can lag SurfaceFlinger.
adb shell wm size reset
adb shell wm density reset
sleep 3
adb shell wm size
adb shell wm density
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
