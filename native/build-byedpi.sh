#!/usr/bin/env bash
set -euo pipefail
ndk="$1"; out="$2"; mode="${3:-all}"
root="$(cd "$(dirname "$0")/byedpi" && pwd)"
bin="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
abis=(armeabi-v7a arm64-v8a x86 x86_64)
[[ "$mode" != arm ]] || abis=(armeabi-v7a arm64-v8a)
rm -rf "$out"; mkdir -p "$out"
for abi in "${abis[@]}"; do
 case "$abi" in
 armeabi-v7a) triple=armv7a-linux-androideabi24;; arm64-v8a) triple=aarch64-linux-android24;;
 x86) triple=i686-linux-android24;; x86_64) triple=x86_64-linux-android24;; esac
 mkdir -p "$out/$abi"
 "$bin/$triple-clang" -D_DEFAULT_SOURCE -I"$root" -std=c99 -Dmain=byedpi_main -O2 -fPIE -pie -fstack-protector-strong \
  -Wl,-z,relro,-z,now,-z,max-page-size=16384 "$root"/{packets,main,conev,proxy,desync,mpool,extend}.c "$root/../launcher.c" -o "$out/$abi/libbyedpi.so"
 "$bin/llvm-strip" "$out/$abi/libbyedpi.so"
done
