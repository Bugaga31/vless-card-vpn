#!/usr/bin/env bash
set -u
# One shell preserves the test exit status; screenshots are collected before emulator shutdown.
test_status=0
./gradlew :app:connectedDebugAndroidTest -PstabilityPreview=true --stacktrace --max-workers=2 || test_status=$?
mkdir -p release/screenshots
adb pull /sdcard/Android/data/com.vlesscardvpn.preview/files/. release/screenshots/ || true
exit "$test_status"
