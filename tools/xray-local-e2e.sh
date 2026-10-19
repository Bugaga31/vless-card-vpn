#!/usr/bin/env bash
# Runs every (server × mask) variant from the generated local-matrix.json through real Xray-core and
# checks HTTPS + a 256 KB download through each SOCKS port. Usage: xray-local-e2e.sh <xray> <dir-with-local-matrix>
set -uo pipefail
XRAY="$1"; DIR="$2"
python3 - "$DIR" <<'PYCFG'
import json,sys
from pathlib import Path
p=Path(sys.argv[1]); c=json.loads((p/'local-matrix.json').read_text())
tags={o['tag'] for o in c['outbounds'] if o.get('protocol')=='wireguard'}
c['outbounds']=[o for o in c['outbounds'] if o.get('tag') not in tags]
c['routing']['rules']=[v for v in c['routing']['rules'] if v.get('outboundTag') not in tags]
(p/'parallel-matrix.json').write_text(json.dumps(c))
PYCFG
"$XRAY" run -c "$DIR/parallel-matrix.json" > "$DIR/client.log" 2>&1 & PID=$!
trap 'kill $PID 2>/dev/null' EXIT
sleep 3
if ! kill -0 $PID 2>/dev/null; then echo "client Xray exited:"; tail -5 "$DIR/client.log"; fi
check() {
  port="$1"; rest="$2"
  a=$(curl --noproxy '' -s -o /dev/null -m 15 -w "%{http_code}" --socks5-hostname 127.0.0.1:$port "${MATRIX_CHECK_URL:-https://www.gstatic.com/generate_204}" ${MATRIX_CA:+--cacert "$MATRIX_CA"})
  b=$(curl --noproxy '' -s -o /dev/null -m 25 -w "%{size_download}" --socks5-hostname 127.0.0.1:$port "${MATRIX_DOWNLOAD_URL:-https://speed.cloudflare.com/__down?bytes=262144}" ${MATRIX_CA:+--cacert "$MATRIX_CA"})
  if [ "$a" = 204 ] && [ "${b:-0}" -ge 262144 ]; then echo "OK $port $rest"; else echo "FAIL $port $rest http=$a bytes=$b"; fi
}
export -f check
# Fail fast on a broken fixture rather than timing out thousands of mask variants.
while read -r port name mask; do
  [ "$name" = wg ] && continue
  [ "$mask" = none ] || continue
  result=$(check "$port" "$name $mask")
  echo "baseline $result"
  [[ "$result" = OK* ]] || { echo 'Baseline failed; mask results would be invalid'; exit 1; }
done < "$DIR/local-matrix.txt"
# Do not instantiate any WireGuard outbounds in the shared client: even an unused
# keepalive can steal the peer from a later single-variant probe.
xargs -P 12 -L 1 bash -c 'check "$0" "$*"' < <(awk '$2 != "wg"' "$DIR/local-matrix.txt") > "$DIR/result.txt"
echo "ok: $(grep -c ^OK "$DIR/result.txt")  fail: $(grep -c ^FAIL "$DIR/result.txt")"
grep ^FAIL "$DIR/result.txt" | head -20
kill -0 $PID 2>/dev/null || { echo "client Xray died during the run:"; tail -5 "$DIR/client.log"; }
pgrep -f "byedpi --ip 127.0.0.1 --port 1080" >/dev/null || echo "ByeDPI :1080 is not running (via-ByeDPI masks fail because of it)"
# KEEP_CLIENT=1: the client stays up (pid in client.pid) so the caller can retry the failures that matter
if [ -n "${KEEP_CLIENT:-}" ] && kill -0 $PID 2>/dev/null; then trap - EXIT; echo $PID > "$DIR/client.pid"; fi
