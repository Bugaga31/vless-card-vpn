#!/usr/bin/env bash
set -euo pipefail
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = "1"
python3 tools/native-route-fixture.py --sing-box "$PWD/sing-box-1.14.2-linux-amd64/sing-box" > fixture-r185.log 2>&1 &
FIXTURE_PID=$!
trap 'kill "$FIXTURE_PID" 2>/dev/null || true; wait "$FIXTURE_PID" 2>/dev/null || true' EXIT
for i in $(seq 1 30); do grep -q '^Fixture CA' fixture-r185.log && break; sleep 1; done
CA_PATH=$(sed -n 's/^Fixture CA (public certificate only): //p' fixture-r185.log | head -1)
test -n "$CA_PATH"
CA_B64=$(base64 -w0 "$CA_PATH")
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
set +e
adb shell am instrument -w -r -e fixture_ca_b64 "$CA_B64" -e class com.vlesscardvpn.NativeAutoRuntimeR185Test   com.vlesscardvpn.test/androidx.test.runner.AndroidJUnitRunner | tee runtime-results-r185.txt
CODE=${PIPESTATUS[0]}
set -e
export CODE
adb logcat -d > runtime-r185.log
python3 - <<'CHECK'
from pathlib import Path
import json,re,os
s=Path('runtime-results-r185.txt').read_text();m=re.search(r'OK \((\d+) tests?\)',s)
passed=os.environ['CODE']=='0' and m is not None and int(m[1])==5 and not re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_STATUS_CODE: -[1-4]|INSTRUMENTATION_STATUS_CODE: -[34]',s)
Path('docs/validation').mkdir(parents=True,exist_ok=True)
d={"source_sha":os.environ['GITHUB_SHA'],"run_id":os.environ['GITHUB_RUN_ID'],"passed":passed,"runtime_tests_executed":m is not None,"count":int(m[1]) if m else 0,"scope":"android33_native_vless_tls_not_full_app_tun_not_operator_dpi","result_tail":s[-7000:]}
Path('docs/validation/r185-device.json').write_text(json.dumps(d,indent=2))
if not passed: print('::error title=Controlled Android runtime::'+s[-3500:].replace('%','%25').replace('\r','%0D').replace('\n','%0A'));raise SystemExit(1)
CHECK
adb shell am start -n com.vlesscardvpn/.MainActivity
sleep 5
adb shell screencap -p /sdcard/runtime-r185.png
adb pull /sdcard/runtime-r185.png runtime-r185.png
