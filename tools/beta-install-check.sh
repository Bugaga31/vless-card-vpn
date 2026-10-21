#!/usr/bin/env bash
# Installs the exact signed release APK on an emulator and checks that it launches.
set -Euo pipefail
exec > >(tee install-check.log) 2>&1
trap 'rc=$?; echo "::error title=Signed install gate::line $LINENO: $BASH_COMMAND (exit $rc)"' ERR
collect_install_diagnostics() {
  rc=$?
  adb logcat -d > logcat.txt 2>&1 || true
  adb shell dumpsys activity activities > install-activities.txt 2>&1 || true
  adb shell dumpsys window windows > install-windows.txt 2>&1 || true
  return "$rc"
}
trap collect_install_diagnostics EXIT
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
C=$(adb shell pm install-commit $SID | tr -d '\r'); echo "$C"; echo "$C" | grep -q Success || { echo "::error::APK install did not report Success"; exit 1; }
adb uninstall $PKG >/dev/null
echo "== new APK via pm install (like a file manager)"
adb push "$APK" /data/local/tmp/b.apk >/dev/null
OUT=$(adb shell pm install /data/local/tmp/b.apk 2>&1 | tr -d '\r'); echo "$OUT"
echo "$OUT" | grep -q Success || { echo "::error::APK install did not report Success"; exit 1; }
adb uninstall $PKG >/dev/null
echo "== new APK via adb install"
adb install "$APK" 2>&1 | tail -1 | grep -q Success || { echo "::error::APK install did not report Success"; exit 1; }
# API33+: acknowledge only the notification permission on the test emulator before launch.
# Otherwise GrantPermissionsActivity legitimately covers the app and the foreground assertion fails.
API=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
if [ "$API" -ge 33 ]; then
  adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || exit 1
fi
adb logcat -c
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 20
adb exec-out screencap -p > screen.png
adb logcat -d > logcat.txt
PID=$(adb shell pidof $PKG | tr -d '\r')
echo "pid: $PID"
# The emulator runs ARM code through ndk_translation, which lacks arm64 MRS system-register reads (CNTVCT_EL0, MIDR_EL1) used by the
# Go runtime inside Xray-core (libgojni). Real ARM phones support it. Legacy ARM-only APKs can skip exactly that.
# A universal APK with native x86_64 may NOT use the translation exception; its launch must really pass.
if ! unzip -l "$APK" | grep -q "lib/x86_64/libgojni.so" && grep -q "ndk_translation: Undefined instruction 0xd53[0-9a-f]" logcat.txt && grep -E "Fatal signal 4 \(SIGILL\)" logcat.txt | grep -q "xray"; then
  echo "LAUNCH SKIPPED: ARM translator cannot run Go/Xray (SIGILL on MRS CNTVCT_EL0/MIDR_EL1) - emulator limitation, not an app bug"
  grep -E "FATAL EXCEPTION" -A25 logcat.txt | grep -q "$PKG" && { grep -E "FATAL EXCEPTION" -A25 logcat.txt | head -40; exit 1; }
else
if grep -E "FATAL EXCEPTION|Fatal signal" -A20 logcat.txt | grep -q "$PKG"; then grep -E "FATAL EXCEPTION|Fatal signal" -A25 logcat.txt | head -60; exit 1; fi
test -n "$PID" || { echo "::error::Signed app process is not running"; exit 1; }
# Avoid adb SIGPIPE under pipefail; keep both transport and foreground failures fatal.
activities=$(adb shell dumpsys activity activities | tr -d '\r') || exit 1
grep -E "mResumedActivity|topResumedActivity" <<< "$activities"
grep -qE "(mResumedActivity|topResumedActivity).*$PKG" <<< "$activities" || { echo "::error::Signed app Activity is not resumed"; exit 1; }
echo "LAUNCH OK"
fi
bash tools/capture-vibe-ui.sh || exit 1
bash tools/capture-intro-ui.sh || exit 1
echo "== upgrade over published 1.2.37 (same package and certificate)"
adb uninstall $PKG >/dev/null 2>&1
curl -fsL -o prev.apk https://github.com/Bugaga31/vless-card-vpn/releases/download/v1.2.37/VLESS-Card-Beta-1.2.37.apk
adb install prev.apk 2>&1 | tail -1 | grep -q Success || { echo "::error::Published 1.2.37 did not install"; exit 1; }
BEFORE=$(adb shell dumpsys package "$PKG" | sed -n 's/.*versionCode=\([0-9]*\).*//p' | head -1 | tr -d '\r')
test "$BEFORE" = 148 || { echo "::error::Expected installed versionCode 148, got $BEFORE"; exit 1; }
U=$(adb install -r "$APK" 2>&1 | tail -1); echo "$U"; echo "$U" | grep -q Success || { echo "::error::1.2.38 did not upgrade 1.2.37"; exit 1; }
AFTER=$(adb shell dumpsys package "$PKG" | sed -n 's/.*versionCode=\([0-9]*\).*//p' | head -1 | tr -d '\r')
test "$AFTER" = 149 || { echo "::error::Expected upgraded versionCode 149, got $AFTER"; exit 1; }
echo "UPGRADE 1.2.37 (148) -> 1.2.38 (149) OK"
echo "ALL INSTALL CHECKS OK"
