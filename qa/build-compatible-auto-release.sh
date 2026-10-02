#!/usr/bin/env bash
# Build only a same-certificate Auto update; never substitute a new debug certificate.
# Keep keystore/passwords out of GitHub. Pass passwords via the environment, not arguments.
set -euo pipefail
: "${ANDROID_HOME:?Set ANDROID_HOME}"
: "${RELEASE_KEYSTORE_FILE:?Provide the original signing keystore}"
: "${KEYSTORE_PASSWORD:?Provide the keystore password privately}"
: "${KEY_PASSWORD:?Provide the key password privately}"
: "${RELEASE_KEY_ALIAS:?Provide the signing alias}"
version="${VERSION_CODE:-48}"
[[ "$version" =~ ^[0-9]+$ ]] && (( version > 46 )) || { echo 'Version must be greater than 46' >&2; exit 1; }
expected='a90f21276a337bb1cbec545d23fce00e173b46cec83f5af015076e53d5488d54'
root="$(cd "$(dirname "$0")/.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
keytool -exportcert -keystore "$RELEASE_KEYSTORE_FILE" -alias "$RELEASE_KEY_ALIAS" \
  -storepass:env KEYSTORE_PASSWORD -file "$tmp/certificate.der" > /dev/null 2>&1
actual="$(sha256sum "$tmp/certificate.der" | cut -d' ' -f1)"
if [[ "$actual" != "$expected" ]]; then
  echo 'Signing certificate mismatch: refusing an incompatible Auto update. Existing app data must not be deleted to work around this.' >&2
  exit 2
fi
cd "$root"
GITHUB_RUN_NUMBER="$version" ./gradlew -PautoPreview=true :app:testDebugUnitTest :app:assembleRelease
sdk_tools="$ANDROID_HOME/build-tools/34.0.0"
"$sdk_tools/zipalign" -f -p 4 app/build/outputs/apk/release/app-release-unsigned.apk "$tmp/aligned.apk"
"$sdk_tools/apksigner" sign --ks "$RELEASE_KEYSTORE_FILE" --ks-key-alias "$RELEASE_KEY_ALIAS" \
  --ks-pass env:KEYSTORE_PASSWORD --key-pass env:KEY_PASSWORD \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false \
  --out "$tmp/signed.apk" "$tmp/aligned.apk"
"$sdk_tools/apksigner" verify --min-sdk-version 33 --max-sdk-version 33 "$tmp/signed.apk"
"$sdk_tools/zipalign" -c -p 4 "$tmp/signed.apk"
"$sdk_tools/aapt" dump badging "$tmp/signed.apk" | grep -q "package: name='com.vlesscardvpn.auto'"
mkdir -p release
out="release/VLESS-Card-Auto-1.0.$version.apk"
cp "$tmp/signed.apk" "$out"
sha256sum "$out" > "$out.sha256"
echo "Compatible signed APK verified: $out"
