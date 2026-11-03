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
  '1.2.56 ac84d095cdb97e73fee8c0849e0667b55d671f5420d0ba151acec30b87a26137' \
  '1.2.118 3e994e4004beeaf0cc6cef04d3c4fa9e1b4e48c0bf58cc0af541cf1553af713e' \
  '1.2.119 7d2448905ca6d83bf3ab965465582d88ae17de90566c966d5ad22d1460d67515'; do
  read -r version digest <<< "$spec"
  BASE="release/baseline-$version.apk"
  TAG="v$version"; FILE="VLESS-Card-Beta-$version.apk"
  if [[ "$version" == "1.2.118" ]]; then TAG="beta-v1.2.118-experimental"; FILE="VLESS-Card-Beta-$version-experimental.apk"; fi
  if [[ "$version" == "1.2.119" ]]; then FILE="VLESS-Card-$version.apk"; fi
  curl --fail --location --silent --show-error --max-time 180 --output "$BASE" \
    "https://github.com/Bugaga31/vless-card-vpn/releases/download/$TAG/$FILE"
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
