#!/usr/bin/env bash
# Check the real published 1.2.50 -> exact signed candidate. No user device involved.
set -euo pipefail
trap 'rc=$?; if [ "$rc" -ne 0 ]; then echo "::error title=Release install phase::published-upgrade failed; exit=$rc"; fi' EXIT
CODE=${VERSION##*-}
[[ "$CODE" =~ ^[0-9]+$ ]]
BASE=release/baseline-1.2.50.apk
curl --fail --location --silent --show-error --max-time 180 --output "$BASE" \
  https://github.com/Bugaga31/vless-card-vpn/releases/download/v1.2.50/VLESS-Card-Beta-1.2.50.apk
echo "d5a5285872a70f2ebc4a4c9ad6274e3084b0c0dead85f2699f48124d119a05a8  $BASE" | sha256sum --check --strict
adb wait-for-device
adb install "$BASE" | tee release/upgrade-baseline.txt
grep -q '^Success' release/upgrade-baseline.txt
adb install -r release/VLESS-Card-Beta.apk | tee release/upgrade-result.txt
grep -q '^Success' release/upgrade-result.txt
adb shell dumpsys package com.vlesscardvpn.beta2 | grep -E "versionCode=$CODE([^0-9]|$)"
# Leave existing clean-install/launch/UI checks unchanged and independent.
adb uninstall com.vlesscardvpn.beta2 >/dev/null
rm -f "$BASE"
echo 'Published 1.2.50 -> signed 1.2.51 upgrade: PASS'
