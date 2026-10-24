#!/usr/bin/env bash
# Execute the packaged Android Tor binary and verify the exact JVM-generated torrc.
# Offline only: --verify-config plus DisableNetwork. This is NOT a bootstrap test.
set -euo pipefail
PKG=${1:-com.vlesscardvpn}
PACKAGE_APK=$(adb shell pm path "$PKG" | tr -d '\r' | sed -n 's/^package://p' | head -1)
LIB=""
# PackageManager labels differ between API levels; confirm the executable, not a label.
CANDIDATES=$(adb shell dumpsys package "$PKG" | tr -d '\r' | sed -n -e 's/^ *nativeLibraryDir=//p' -e 's/^ *legacyNativeLibraryDir=//p')
for base in $CANDIDATES "$(dirname "$PACKAGE_APK")/lib"; do
  for dir in "$base" "$base/x86_64"; do
    if adb shell run-as "$PKG" test -x "$dir/libTor.so"; then LIB=$dir; break 2; fi
  done
done
test -n "$LIB"
adb shell run-as "$PKG" test -x "$LIB/libTor.so"
adb shell run-as "$PKG" test -x "$LIB/libLyrebird.so"
adb shell run-as "$PKG" "$LIB/libTor.so" --version | tee /tmp/e2e/tor-version.txt
trap 'adb shell run-as "$PKG" rm -rf files/tor-native-check >/dev/null 2>&1 || true' EXIT
adb shell run-as "$PKG" mkdir -p files/tor-native-check/data
for mode in none obfs4 webtunnel; do
  conf="app/build/tor-verify-configs/$mode.conf"
  test -f "$conf"
  sed "s#@NATIVE_LIB_DIR@#$LIB#g" "$conf" > /tmp/e2e/tor-config.conf
  adb push /tmp/e2e/tor-config.conf /data/local/tmp/tor-native-check.conf >/dev/null
  adb shell run-as "$PKG" cp /data/local/tmp/tor-native-check.conf files/tor-native-check/config
  adb shell run-as "$PKG" "$LIB/libTor.so" --verify-config -f "/data/user/0/$PKG/files/tor-native-check/config" > "/tmp/e2e/tor-$mode.txt" 2>&1
  grep -q 'Configuration was valid' "/tmp/e2e/tor-$mode.txt" || { tail -8 "/tmp/e2e/tor-$mode.txt"; exit 1; }
  echo "Embedded Tor native config $mode: PASS (offline; no bootstrap claim)"
done
adb shell rm -f /data/local/tmp/tor-native-check.conf
