#!/usr/bin/env bash
set -euo pipefail
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = "1"
python3 tools/native-route-fixture.py --sing-box "$PWD/sing-box-1.14.2-linux-amd64/sing-box" > fixture-r192.log 2>&1 &
FIXTURE_PID=$!
trap 'kill "$FIXTURE_PID" 2>/dev/null || true; wait "$FIXTURE_PID" 2>/dev/null || true' EXIT
for i in $(seq 1 30); do grep -q '^Fixture CA' fixture-r192.log && break; sleep 1; done
CA_PATH=$(sed -n 's/^Fixture CA (public certificate only): //p' fixture-r192.log | head -1)
test -n "$CA_PATH"
CA_B64=$(base64 -w0 "$CA_PATH")
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm grant com.vlesscardvpn android.permission.POST_NOTIFICATIONS
set +e
adb shell am instrument -w -r -e fixture_ca_b64 "$CA_B64" -e class com.vlesscardvpn.NativeAutoRuntimeR190Test,com.vlesscardvpn.DashboardUiR190Test,com.vlesscardvpn.FirstConnectRuntimeR190Test,com.vlesscardvpn.ProductRuntimeR190Test,com.vlesscardvpn.MaintenanceUiR190Test,com.vlesscardvpn.WarpRuntimeR191Test,com.vlesscardvpn.PowerRoutingRuntimeR192Test,com.vlesscardvpn.RuWhitelistUiR192Test,com.vlesscardvpn.RuNativeConfigR192Test   com.vlesscardvpn.test/androidx.test.runner.AndroidJUnitRunner | tee runtime-results-r192.txt
CODE=${PIPESTATUS[0]}
set -e
export CODE
adb logcat -d > runtime-r192.log
mkdir -p docs/validation/r192-ui
adb pull /sdcard/Android/data/com.vlesscardvpn/files/ui192/. docs/validation/r192-ui/ || true
python3 - <<'CHECK'
from pathlib import Path
import json,re,os
s=Path('runtime-results-r192.txt').read_text();m=re.search(r'OK \((\d+) tests?\)',s)
passed=os.environ['CODE']=='0' and m is not None and int(m[1])==124 and not re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_STATUS_CODE: -[1-4]|INSTRUMENTATION_STATUS_CODE: -[34]',s)
Path('docs/validation').mkdir(parents=True,exist_ok=True)
d={"source_sha":os.environ.get('VALIDATED_SOURCE_SHA',os.environ['GITHUB_SHA']),"run_id":os.environ['GITHUB_RUN_ID'],"passed":passed,"runtime_tests_executed":m is not None,"count":int(m[1]) if m else 0,"scope":"android33_native_vless_tls_not_full_app_tun_not_operator_dpi","result_tail":s[-7000:]}
Path('docs/validation/r192-device.json').write_text(json.dumps(d,indent=2))
if not passed: print('::error title=Controlled Android runtime::'+s[-3500:].replace('%','%25').replace('\r','%0D').replace('\n','%0A'));raise SystemExit(1)
CHECK
mkdir -p docs/validation/r192-ui
adb pull /sdcard/Android/data/com.vlesscardvpn/files/ui192/. docs/validation/r192-ui/
python3 - <<'UI_CHECK'
from pathlib import Path
import json,hashlib,sys
sys.path.insert(0,'tools/release-gates')
from check_screenshot import check_png
files=sorted(Path('docs/validation/r192-ui').glob('*.png'))
assert len(files)==16, [str(f) for f in files]
for f in files:check_png(f.read_bytes())
p=Path('docs/validation/r192-device.json');d=json.loads(p.read_text())
d['ui_captures_executed']=True
d['ui_scope']='synthetic_states_real_production_compose_not_live_vpn'
d['screenshots']=[dict(file=f.name,sha256=hashlib.sha256(f.read_bytes()).hexdigest()) for f in files]
p.write_text(json.dumps(d,indent=2))
UI_CHECK
