#!/usr/bin/env bash
set -euo pipefail
python3 tools/native-route-fixture.py --sing-box "$PWD/sing-box-1.14.2-linux-amd64/sing-box" > fixture.log 2>&1 &
FIXTURE_PID=$!
trap 'kill "$FIXTURE_PID" 2>/dev/null || true; wait "$FIXTURE_PID" 2>/dev/null || true' EXIT
for i in $(seq 1 30); do grep -q '^Fixture CA' fixture.log && break; sleep 1; done
CA_PATH=$(sed -n 's/^Fixture CA (public certificate only): //p' fixture.log | head -1)
test -n "$CA_PATH"
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb push "$CA_PATH" /data/local/tmp/vless-fixture-ca.pem
adb shell am instrument -w -r -e fixture_ca_path /data/local/tmp/vless-fixture-ca.pem \
  -e class com.vlesscardvpn.NativeConfigValidationTest,com.vlesscardvpn.NativeCallbackContractTest,com.vlesscardvpn.NodeStorageMigrationTest,com.vlesscardvpn.NodeMetadataUpdateTest,com.vlesscardvpn.NativeOutboundFixtureTest \
  com.vlesscardvpn.preview.test/androidx.test.runner.AndroidJUnitRunner | tee native-results.txt
grep -q 'OK (19 tests)' native-results.txt
! grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' native-results.txt
