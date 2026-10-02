#!/usr/bin/env bash
set -euo pipefail
# The Lab package is independent of the Auto package and never requires uninstalling it.
: "${LAB_KEYSTORE:?Set private Lab keystore path}"
: "${LAB_PASSWORD_FILE:?Set private password-file path}"
: "${ANDROID_HOME:?Set Android SDK path}"
version="${GITHUB_RUN_NUMBER:-49}"
GITHUB_RUN_NUMBER="$version" ./gradlew -PlabPreview=true --no-daemon --max-workers=1 \
  :app:testDebugUnitTest :app:assembleRelease :app:lintRelease
mkdir -p release
bt="$ANDROID_HOME/build-tools/34.0.0"
"$bt/zipalign" -f -p 4 app/build/outputs/apk/release/app-release-unsigned.apk release/lab-aligned.apk
"$bt/apksigner" sign --ks "$LAB_KEYSTORE" --ks-key-alias lab \
  --ks-pass "file:$LAB_PASSWORD_FILE" --key-pass "file:$LAB_PASSWORD_FILE" \
  --out "release/VLESS-Card-Lab-1.0.$version.apk" release/lab-aligned.apk
"$bt/apksigner" verify --verbose --print-certs "release/VLESS-Card-Lab-1.0.$version.apk"
"$bt/zipalign" -c -p 4 "release/VLESS-Card-Lab-1.0.$version.apk"
"$bt/aapt" dump badging "release/VLESS-Card-Lab-1.0.$version.apk" | grep -E '^package:|^application-label:|^native-code:'
sha256sum "release/VLESS-Card-Lab-1.0.$version.apk" > release/SHA256SUMS-Lab.txt
rm release/lab-aligned.apk
