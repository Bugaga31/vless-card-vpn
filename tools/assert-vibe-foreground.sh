#!/usr/bin/env bash
set -euo pipefail
PKG=com.vlesscardvpn.beta2
focus=$(adb shell dumpsys window windows | tr -d '\r' | grep -E 'mCurrentFocus=|mFocusedApp=' || true)
echo "$focus"
echo "$focus" | grep -q "mCurrentFocus=.*$PKG" || { echo 'UI capture refused: app is not the focused window'; exit 1; }
if echo "$focus" | grep -qiE 'Application (Error|Not Responding)|PermissionController|GrantPermissions'; then
  echo 'UI capture refused: dialog covers the app'; exit 1
fi
adb shell dumpsys activity activities | grep -qE "(mResumedActivity|topResumedActivity).*$PKG" || exit 1
