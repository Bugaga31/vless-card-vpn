#!/usr/bin/env bash
set -euo pipefail
# Permission automation belongs only on controlled emulator fixtures, never a user's phone.
python3 -m unittest discover -s tools -p 'test_*.py'
bash tools/run-byedpi-host-check.sh
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = "1"
./gradlew --no-daemon --max-workers=2 :vpn-test-probe:assembleDebug
python3 tools/native-route-fixture.py --sing-box "$PWD/sing-box-1.14.2-linux-amd64/sing-box" > fixture.log 2>&1 &
FIXTURE_PID=$!
trap 'kill "$FIXTURE_PID" 2>/dev/null || true; wait "$FIXTURE_PID" 2>/dev/null || true' EXIT
for i in $(seq 1 30); do grep -q '^Fixture CA' fixture.log && break; sleep 1; done
CA_PATH=$(sed -n 's/^Fixture CA (public certificate only): //p' fixture.log | head -1)
test -n "$CA_PATH"
REALITY_KEY_PATH=$(sed -n 's/^Fixture REALITY public key file: //p' fixture.log | head -1)
test -n "$REALITY_KEY_PATH"
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb push "$CA_PATH" /data/local/tmp/vless-fixture-ca.pem
adb push "$REALITY_KEY_PATH" /data/local/tmp/vless-fixture-reality-public.txt
adb install -r -t vpn-test-probe/build/outputs/apk/debug/vpn-test-probe-debug.apk
adb shell appops set com.vlesscardvpn.preview ACTIVATE_VPN allow
set +e
adb shell am instrument -w -r -e fixture_ca_path /data/local/tmp/vless-fixture-ca.pem -e fixture_reality_key_path /data/local/tmp/vless-fixture-reality-public.txt -e fixture_full_tun 1 \
  -e class com.vlesscardvpn.AutoTcpPreflightAndroidTest,com.vlesscardvpn.NativeConfigValidationTest,com.vlesscardvpn.NativeCallbackContractTest,com.vlesscardvpn.NodeStorageMigrationTest,com.vlesscardvpn.NodeMetadataUpdateTest,com.vlesscardvpn.NativeOutboundFixtureTest,com.vlesscardvpn.NativeFullTunFixtureTest,com.vlesscardvpn.HomeScreenSmokeTest,com.vlesscardvpn.LauncherStartupTest,com.vlesscardvpn.VpnServiceCancellationFixtureTest \
  com.vlesscardvpn.preview.test/androidx.test.runner.AndroidJUnitRunner | tee native-results.txt
INSTRUMENT_EXIT=${PIPESTATUS[0]}
set -e
export INSTRUMENT_EXIT
# A public check annotation exposes only controlled-fixture assertion summaries,
# so debugging does not require another personal login to download an artifact.
python3 - <<'CHECK'
from pathlib import Path
import re, os
text = Path("native-results.txt").read_text()
passed = os.environ["INSTRUMENT_EXIT"] == "0" and "OK (53 tests)" in text and not re.search(r"FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_STATUS_CODE: -[1-4]", text)
if not passed:
    summary = text[text.rfind("Time:"):] if "Time:" in text else text[-3500:]
    summary = "\n".join(line for line in summary.splitlines() if not line.lstrip().startswith(("at ", "... ")))
    summary = summary[:3500].replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    print("::error title=Native Android fixture assertions::" + summary)
    raise SystemExit(1)
print("Confirmed: 53 instrumented fixture checks, no skipped/failed tests")
CHECK
