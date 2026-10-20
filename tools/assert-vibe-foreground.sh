#!/usr/bin/env bash
set -euo pipefail
PKG=com.vlesscardvpn.beta2
focus=$(adb shell dumpsys window | tr -d '\r' | grep -E 'mCurrentFocus=|mFocusedApp=' || true)
echo "$focus"
echo "$focus" | grep -q "mCurrentFocus=.*$PKG" || { echo 'UI capture refused: app is not the focused window'; exit 1; }
if echo "$focus" | grep -qiE 'Application (Error|Not Responding)|PermissionController|GrantPermissions'; then
  echo 'UI capture refused: dialog covers the app'; exit 1
fi
# Consume all adb output before matching: grep -q otherwise closes the pipe early under pipefail.
activities=$(adb shell dumpsys activity activities | tr -d '\r')
grep -qE "(mResumedActivity|topResumedActivity).*$PKG" <<< "$activities" || {
  echo 'UI capture refused: app Activity is not resumed'; exit 1;
}
