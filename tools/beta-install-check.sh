#!/usr/bin/env bash
# Installs the exact signed release APK on an emulator and checks that it launches.
set -uo pipefail
PKG=com.vlesscardvpn.beta2
APK=release/VLESS-Card-Beta.apk
adb wait-for-device
echo "abi: $(adb shell getprop ro.product.cpu.abilist | tr -d '\r')"
echo "== old Beta 1.0.51 installed first (must not conflict)"
curl -fsL -o old.apk https://github.com/Bugaga31/vless-card-vpn/releases/download/beta-v1.0.51/VLESS-Card-Beta-1.0.51.apk && adb install old.apk 2>&1 | tail -1
echo "== session install (same API as system installer / file managers)"
SIZE=$(stat -c %s "$APK")
SID=$(adb shell pm install-create -S $SIZE | tr -d '\r' | sed -n 's/.*\[\([0-9]*\)\].*/\1/p')
adb push "$APK" /data/local/tmp/s.apk >/dev/null
adb shell pm install-write -S $SIZE $SID base.apk /data/local/tmp/s.apk | tr -d '\r'
C=$(adb shell pm install-commit $SID | tr -d '\r'); echo "$C"; echo "$C" | grep -q Success || exit 1
adb uninstall $PKG >/dev/null
echo "== new APK via pm install (like a file manager)"
adb push "$APK" /data/local/tmp/b.apk >/dev/null
OUT=$(adb shell pm install /data/local/tmp/b.apk 2>&1 | tr -d '\r'); echo "$OUT"
echo "$OUT" | grep -q Success || exit 1
adb uninstall $PKG >/dev/null
echo "== new APK via adb install"
adb install "$APK" 2>&1 | tail -1 | grep -q Success || exit 1
adb logcat -c
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 20
adb exec-out screencap -p > screen.png
adb logcat -d > logcat.txt
PID=$(adb shell pidof $PKG | tr -d '\r')
echo "pid: $PID"
if grep -E "FATAL EXCEPTION|Fatal signal" -A20 logcat.txt | grep -q "$PKG"; then grep -E "FATAL EXCEPTION|Fatal signal" -A25 logcat.txt | head -60; exit 1; fi
test -n "$PID" || exit 1
adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" | head -2
adb shell dumpsys activity activities | grep -qE "(mResumedActivity|topResumedActivity).*$PKG" || exit 1
echo "LAUNCH OK"
