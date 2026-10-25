#!/usr/bin/env bash
set -euo pipefail
trap 'rc=$?; if [ "$rc" -ne 0 ]; then echo "::error title=Release install phase::storage-security failed; exit=$rc"; fi' EXIT
source "$(dirname "$0")/security-results-valid.sh"
PKG=com.vlesscardvpn.securityprobe
adb install -r -t release/security-test-probe.apk >/dev/null
# -r is required: without it Android prints only the human-readable stream, hiding result counters/code.
adb shell am instrument -w -r "$PKG/.ProbeInstrumentation" | tr -d '\r' | tee security-results.txt
if ! security_results_valid security-results.txt; then
  python3 - <<'PY'
from pathlib import Path
text = Path('security-results.txt').read_text(errors='replace')[:4000]
text = text.replace('%','%25').replace('\r','%0D').replace('\n','%0A')
print('::error title=Android storage security checks::' + text)
PY
  exit 1
fi
adb uninstall "$PKG" >/dev/null
