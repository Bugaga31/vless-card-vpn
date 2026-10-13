#!/usr/bin/env bash
set -euo pipefail
# Exercise the unchanged mandatory counters without needing an Android emulator.
tmp=$(mktemp); trap 'rm -f "$tmp"' EXIT
source "$(dirname "$0")/security-results-valid.sh"
printf '%s\n' 'INSTRUMENTATION_RESULT: failures=0' 'INSTRUMENTATION_RESULT: tests=7' 'INSTRUMENTATION_CODE: -1' > "$tmp"
security_results_valid "$tmp"
sed -i 's/failures=0/failures=1/' "$tmp"
if security_results_valid "$tmp"; then exit 1; fi
printf '%s\n' 'PASS: only human-readable text without mandatory result counters' > "$tmp"
if security_results_valid "$tmp"; then exit 1; fi
echo 'Security result parser: valid accepted, failed and missing result rejected'
