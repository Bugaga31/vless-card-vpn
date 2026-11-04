#!/usr/bin/env bash
# Check the real published 1.2.50 -> exact signed candidate. No user device involved.
set -euo pipefail
trap 'rc=$?; if [ "$rc" -ne 0 ]; then echo "::error title=Release install phase::published-upgrade failed; exit=$rc"; fi' EXIT
CODE=${VERSION##*-}
[[ "$CODE" =~ ^[0-9]+$ ]]
# Keep the original upgrade gate and additionally test the immediately preceding release.
for spec in \
  '1.2.50 d5a5285872a70f2ebc4a4c9ad6274e3084b0c0dead85f2699f48124d119a05a8' \
  '1.2.55 b4a319c1753ae28761cba62e1ea3df40167116c6a946b160643a35dc1afdb7c1' \
  '1.2.56 ac84d095cdb97e73fee8c0849e0667b55d671f5420d0ba151acec30b87a26137'; do
  read -r version digest <<< "$spec"
  BASE="release/baseline-$version.apk"
  curl --fail --location --silent --show-error --max-time 180 --output "$BASE" \
    "https://github.com/Bugaga31/vless-card-vpn/releases/download/v$version/VLESS-Card-Beta-$version.apk"
  echo "$digest  $BASE" | sha256sum --check --strict
  adb wait-for-device
  adb install "$BASE" | tee "release/upgrade-baseline-$version.txt"
  grep -q '^Success' "release/upgrade-baseline-$version.txt"
  adb install -r release/VLESS-Card-Beta.apk | tee "release/upgrade-result-$version.txt"
  grep -q '^Success' "release/upgrade-result-$version.txt"
  adb shell dumpsys package com.vlesscardvpn.beta2 | grep -E "versionCode=$CODE([^0-9]|$)"
  # Leave existing clean-install/launch/UI checks unchanged and independent.
  adb uninstall com.vlesscardvpn.beta2 >/dev/null
  rm -f "$BASE"
  echo "Published $version -> signed versionCode $CODE upgrade: PASS"
done
