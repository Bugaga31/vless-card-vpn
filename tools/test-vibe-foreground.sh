#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
cat > "$TMP/adb" <<'PY'
#!/usr/bin/env python3
import os,sys
case=os.environ['CASE']; activity='activity' in sys.argv
if case == ('activity-error' if activity else 'window-error'): sys.exit(1)
pkg='com.vlesscardvpn.beta2'
if activity:
 print('mResumedActivity: '+(pkg if case!='not-resumed' else 'com.android.launcher3'))
 # Early grep -q must not cause adb SIGPIPE on long historical dumps.
 for i in range(30000): print('historical activity ' + 'x'*80)
else:
 focus={'launcher':'com.android.launcher3','permission':'PermissionController.GrantPermissions','anr':'Application Not Responding: '+pkg}.get(case,pkg)
 print('mCurrentFocus=Window{'+focus+'}')
 print('mFocusedApp=ActivityRecord{'+pkg+'}')
PY
chmod +x "$TMP/adb"
for CASE in valid launcher permission anr not-resumed window-error activity-error; do
  export CASE
  if PATH="$TMP:$PATH" bash "$ROOT/tools/assert-vibe-foreground.sh" > "$TMP/result" 2>&1; then
    [[ "$CASE" == valid ]] || { echo "FAIL: accepted $CASE"; exit 1; }
  else
    [[ "$CASE" != valid ]] || { cat "$TMP/result"; echo 'FAIL: valid foreground rejected'; exit 1; }
  fi
  echo "PASS $CASE"
done
