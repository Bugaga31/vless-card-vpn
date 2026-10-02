#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cc -D_DEFAULT_SOURCE -std=c99 -O2 -fPIE -pie -fstack-protector-strong \
  -Wl,-z,relro,-z,now -I "$root/native/byedpi" \
  "$root"/native/byedpi/{packets,main,conev,proxy,desync,mpool,extend}.c \
  -o "$work/byedpi"
python3 "$root/tools/test-sni-records-live.py" --byedpi "$work/byedpi"
