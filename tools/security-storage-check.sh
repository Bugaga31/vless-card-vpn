#!/usr/bin/env bash
set -euo pipefail
PKG=com.vlesscardvpn.securityprobe
adb install -r -t release/security-test-probe.apk >/dev/null
adb shell am instrument -w "$PKG/.ProbeInstrumentation" | tee security-results.txt
grep -q 'INSTRUMENTATION_RESULT: failures=0' security-results.txt
grep -q 'INSTRUMENTATION_RESULT: tests=7' security-results.txt
grep -q 'INSTRUMENTATION_CODE: -1' security-results.txt
adb uninstall "$PKG" >/dev/null
