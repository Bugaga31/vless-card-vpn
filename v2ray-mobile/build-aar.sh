#!/usr/bin/env bash
# Builds libv2ray.aar from v2fly/v2ray-core v4 + tun2socks via gomobile.
# Requires: Go, gomobile, gobind, Android NDK (ANDROID_NDK_HOME or sdk/ndk).
set -euo pipefail
cd "$(dirname "$0")"

export PATH="$PATH:/usr/local/go/bin:$HOME/go/bin"

if ! command -v gomobile >/dev/null; then
  echo "gomobile not found. Install: go install golang.org/x/mobile/cmd/gomobile@latest" >&2
  exit 1
fi

SDK="${ANDROID_HOME:-$HOME/android-sdk}"
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  NDK=$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1 || true)
  if [ -z "$NDK" ]; then
    echo "Android NDK not found. Install via sdkmanager 'ndk;27.2.12479018'." >&2
    exit 1
  fi
  export ANDROID_NDK_HOME="$NDK"
fi

mkdir -p build
echo "Using NDK: $ANDROID_NDK_HOME"
gomobile bind -v -target=android -androidapi 24 -o build/libv2ray.aar .
sha256sum build/libv2ray.aar
