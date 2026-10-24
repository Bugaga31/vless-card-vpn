#!/usr/bin/env bash
# Execute the packaged Android Tor binary and verify the exact JVM-generated torrc.
# Offline only: --verify-config plus DisableNetwork. This is NOT a bootstrap test.
set -euo pipefail
PKG=${1:-com.vlesscardvpn}
W=${TOR_SMOKE_OUTPUT_DIR:-/tmp/e2e}
mkdir -p "$W"
export TOR_SMOKE_OUTPUT_DIR="$W"
STAGE=resolve-native-library
cleanup() {
  adb shell run-as "$PKG" rm -rf files/tor-native-check >/dev/null 2>&1 || true
  adb shell rm -f /data/local/tmp/tor-native-check.conf >/dev/null 2>&1 || true
}
on_error() {
  local rc=$?
  trap - ERR
  TOR_SMOKE_STAGE="$STAGE" TOR_SMOKE_RC="$rc" python3 - <<'PYCODE'
import os, pathlib, re
# Only fixed-fixture Tor diagnostics, never full logcat or CI logs.
lines=[]
for p in sorted(pathlib.Path(os.environ['TOR_SMOKE_OUTPUT_DIR']).glob('tor-*.txt')):
    for line in p.read_text(errors='replace').splitlines():
        if re.search(r'(?i)error|failed|cannot|denied|invalid|not found|missing', line):
            line=re.sub(r'/data/[^\s\"]+', '<path>', line)
            line=re.sub(r'\b(?:\d{1,3}\.){3}\d{1,3}\b', '<fixture-ip>', line)
            line=re.sub(r'\b[0-9A-Fa-f]{32,}\b', '<redacted>', line)
            lines.append(line[:240])
text=f"stage={os.environ['TOR_SMOKE_STAGE']}; exit={os.environ['TOR_SMOKE_RC']}"
if lines: text+='; '+' | '.join(lines[:4])
text=text.replace('%','%25').replace('\r','%0D').replace('\n','%0A')
print('::error title=Offline packaged Tor smoke::'+text)
PYCODE
  exit "$rc"
}
trap on_error ERR
trap cleanup EXIT
PACKAGE_APK=$(adb shell pm path "$PKG" | tr -d '\r' | sed -n 's/^package://p' | head -1)
LIB=""
# PackageManager labels differ between API levels; confirm the executable, not a label.
CANDIDATES=$(adb shell dumpsys package "$PKG" | tr -d '\r' | sed -n -e 's/^ *nativeLibraryDir=//p' -e 's/^ *legacyNativeLibraryDir=//p')
for base in $CANDIDATES "$(dirname "$PACKAGE_APK")/lib"; do
  for dir in "$base" "$base/x86_64"; do
    if adb shell run-as "$PKG" test -x "$dir/libTor.so" 2>> "$W/tor-native-locate.txt"; then LIB=$dir; break 2; fi
  done
done
test -n "$LIB"
STAGE=check-native-executables
adb shell run-as "$PKG" test -x "$LIB/libTor.so"
adb shell run-as "$PKG" test -x "$LIB/libLyrebird.so"
STAGE=execute-version
adb shell run-as "$PKG" "$LIB/libTor.so" --version 2>&1 | tee "$W/tor-version.txt"
adb shell run-as "$PKG" mkdir -p files/tor-native-check/data
for mode in none obfs4 webtunnel; do
  STAGE=prepare-$mode-config
  conf="app/build/tor-verify-configs/$mode.conf"
  test -f "$conf"
  sed "s#@NATIVE_LIB_DIR@#$LIB#g" "$conf" > "$W/tor-config.conf"
  adb push "$W/tor-config.conf" /data/local/tmp/tor-native-check.conf >/dev/null
  adb shell run-as "$PKG" cp /data/local/tmp/tor-native-check.conf files/tor-native-check/config
  STAGE=verify-$mode-config
  adb shell run-as "$PKG" "$LIB/libTor.so" --verify-config -f "/data/user/0/$PKG/files/tor-native-check/config" > "$W/tor-$mode.txt" 2>&1
  grep -q 'Configuration was valid' "$W/tor-$mode.txt" || { tail -8 "$W/tor-$mode.txt"; exit 1; }
  echo "Embedded Tor native config $mode: PASS (offline; no bootstrap claim)"
done
adb shell rm -f /data/local/tmp/tor-native-check.conf
