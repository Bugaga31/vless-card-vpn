#!/usr/bin/env bash
# Builds libhev-socks5-tunnel.so (JNI, class com/vlesscardvpn/core/TProxyService) like v2rayNG's compile-hevtun.sh.
set -euo pipefail
ndk="$1"; out="$2"; mode="${3:-all}"
src="$(cd "$(dirname "$0")/hev-socks5-tunnel" && pwd)"
abis="armeabi-v7a arm64-v8a x86 x86_64"
[[ "$mode" != arm ]] || abis="armeabi-v7a arm64-v8a"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/jni"; ln -s "$src" "$tmp/jni/hev-socks5-tunnel"
echo 'include $(call all-subdir-makefiles)' > "$tmp/jni/Android.mk"
( cd "$tmp" && "$ndk/ndk-build" NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=jni/Android.mk "APP_ABI=$abis" APP_PLATFORM=android-24 \
  NDK_LIBS_OUT="$tmp/libs" NDK_OUT="$tmp/obj" "APP_CFLAGS=-O3 -DPKGNAME=com/vlesscardvpn/core -DCLSNAME=TProxyService" \
  "APP_LDFLAGS=-Wl,--build-id=none -Wl,--hash-style=gnu" -j"$(nproc)" >/dev/null )
mkdir -p "$out"
for abi in $abis; do mkdir -p "$out/$abi"; cp "$tmp/libs/$abi/libhev-socks5-tunnel.so" "$out/$abi/"; done
