#!/usr/bin/env bash
# Runs every (server × mask) variant from the generated local-matrix.json through real Xray-core and
# checks HTTPS + a 256 KB download through each SOCKS port. Usage: xray-local-e2e.sh <xray> <dir-with-local-matrix>
set -uo pipefail
XRAY="$1"; DIR="$2"
"$XRAY" run -c "$DIR/local-matrix.json" > "$DIR/client.log" 2>&1 & PID=$!
trap 'kill $PID 2>/dev/null' EXIT
sleep 3
check() {
  port="$1"; rest="$2"
  a=$(curl -s -o /dev/null -m 15 -w "%{http_code}" --socks5-hostname 127.0.0.1:$port https://www.gstatic.com/generate_204)
  b=$(curl -s -o /dev/null -m 25 -w "%{size_download}" --socks5-hostname 127.0.0.1:$port "https://speed.cloudflare.com/__down?bytes=262144")
  if [ "$a" = 204 ] && [ "${b:-0}" -ge 262144 ]; then echo "OK $port $rest"; else echo "FAIL $port $rest http=$a bytes=$b"; fi
}
export -f check
xargs -P 24 -L 1 bash -c 'check "$0" "$*"' < "$DIR/local-matrix.txt" > "$DIR/result.txt"
echo "ok: $(grep -c ^OK "$DIR/result.txt")  fail: $(grep -c ^FAIL "$DIR/result.txt")"
grep ^FAIL "$DIR/result.txt" | head -20
