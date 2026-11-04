#!/usr/bin/env bash
# Emulator end-to-end check of the real chain:
#   probe app (other UID) → VPN TUN → hev-socks5-tunnel → SOCKS 127.0.0.1:10808 → Xray-core → server on the host → internet.
# Server-side Xray access log proves which server carried the traffic. Run inside android-emulator-runner.
set -uo pipefail
CASE=setup
PKG=com.vlesscardvpn; PROBE=com.vlesscardvpn.netprobe
W=/tmp/e2e; mkdir -p $W; FAILS=0
XRAY=${XRAY:-/tmp/xray/xray}
python3 -m unittest discover -s tools/e2e -p 'test_*.py' || exit 1
diagnostic() {
  # Capture before a later scenario clears logcat/access.log. Never print the raw snapshots.
  adb logcat -d -s E2E:I VLESS:W AndroidRuntime:E | python3 tools/e2e/snapshot_failure.py --directory "$W" --case "$CASE" --failure "$FAILS"
  { # Preserve only the bounded/redacted summary at the failure, before the next case clears logcat.
  python3 tools/e2e/summarize_failure.py --case "$CASE" < "$W/failure-${CASE}-${FAILS}-logcat.txt"
  # Host log output is never printed: only fixed-fixture event counts.
  python3 - "$W/failure-${CASE}-${FAILS}-access.txt" "$CASE" <<'PYCOUNTS'
import json,pathlib,re,sys
try:s=pathlib.Path(sys.argv[1]).read_text(errors='replace')[-2000000:]
except OSError:s=''
counts={str(p):len(re.findall(r'email: u'+str(p)+r'\b',s)) for p in (8443,8444,8447)}
print('::notice title=E2E fixture server counts::'+json.dumps({'case':sys.argv[2],'accepted':counts},separators=(',',':')))
PYCOUNTS
  if [ -n "${R:-}" ]; then printf '%s' "$R" | python3 tools/e2e/summarize_failure.py --case "$CASE" --probe; fi
  } | tee "$W/failure-${CASE}-${FAILS}.txt"
}
fail() { echo "::error title=e2e::$*"; echo "FAIL: $*"; FAILS=$((FAILS+1)); diagnostic; }

# ---- servers on the host (emulator sees the host as 10.0.2.2)
KEYS=$($XRAY x25519); PRIV=$(echo "$KEYS" | sed -n 's/^PrivateKey: //p'); PBK=$(echo "$KEYS" | sed -n 's/^Password (PublicKey): //p')
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -keyout $W/k.pem -out $W/c.pem -days 2 -subj "/CN=test.local" 2>/dev/null
U=a00975c5-597e-4c22-a7a7-a84ce5e0691e
sed -e 's/"loglevel":"warning"/"loglevel":"info"/' -e "s#@PRIV@#$PRIV#g" -e "s#@U@#$U#g" -e "s#@W@#$W#g" tools/e2e/server.json > $W/server.json
$XRAY run -c $W/server.json > $W/server.log 2>&1 & SRV=$!
trap 'kill $SRV 2>/dev/null' EXIT
sleep 2
python3 tools/e2e/make_state.py $W "$PBK"

adb wait-for-device
adb install -r -g app/build/outputs/apk/debug/app-debug.apk | tail -1
adb install -r -t vpn-test-probe/build/outputs/apk/debug/vpn-test-probe-debug.apk | tail -1
bash tools/e2e/tor-native-smoke.sh "$PKG" || exit 1
adb shell appops set $PKG ACTIVATE_VPN allow
adb shell am start -W -n $PKG/.MainActivity >/dev/null; sleep 3

load() { # $1 = state file
  # Isolate cases: a delayed save/background coroutine from the previous process
  # must not overwrite the new state between copying it and Store.reload().
  adb shell am force-stop "$PKG" || return 1
  adb push "$W/$1" /data/local/tmp/state.json >/dev/null || return 1
  adb shell "run-as $PKG sh -c 'mkdir -p files && cp /data/local/tmp/state.json files/state.json'" || return 1
  adb shell am start -W -n "$PKG/.MainActivity" --ez e2e_reload true --ez e2e_connect "${2:-false}" >/dev/null || return 1
  sleep 1
}
waitlog() { # $1 = regex, $2 = seconds
  for i in $(seq 1 $2); do L=$(adb logcat -d -s E2E:I | grep -E "$1" | tail -1); [ -n "$L" ] && { echo "$L"; return 0; }; sleep 1; done; return 1
}
logcount() {
  local lines
  lines=$(adb logcat -d -s E2E:I | grep -E "$1" || true)
  printf '%s\n' "$lines" | grep -c . || true
}
waitlog_after() {
  local pattern="$1" baseline="$2" seconds="$3" lines count
  for i in $(seq 1 "$seconds"); do
    lines=$(adb logcat -d -s E2E:I | grep -E "$pattern" || true)
    count=$(printf '%s\n' "$lines" | grep -c . || true)
    if [ "$count" -gt "$baseline" ]; then printf '%s\n' "$lines" | tail -1; return 0; fi
    sleep 1
  done
  return 1
}
ui_coords() {
  adb shell uiautomator dump /sdcard/e2e-window.xml >/dev/null 2>&1 || return 1
  adb shell cat /sdcard/e2e-window.xml > "$W/window.xml" || return 1
  python3 - "$W/window.xml" "$1" <<'PYUI'
import re,sys,xml.etree.ElementTree as ET
root=ET.parse(sys.argv[1]).getroot()
for node in root.iter('node'):
    attrs=node.attrib
    if sys.argv[2] not in (attrs.get('text','')+' '+attrs.get('content-desc','')):
        continue
    match=re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',attrs.get('bounds',''))
    if match:
        x1,y1,x2,y2=map(int,match.groups())
        print((x1+x2)//2,(y1+y2)//2)
        break
PYUI
}
tap_ui() {
  local coords x y
  for _ in $(seq 1 30); do
    coords=$(ui_coords "$1")
    if [ -n "$coords" ]; then
      read -r x y <<<"$coords"
      adb shell input tap "$x" "$y"
      return 0
    fi
    sleep 1
  done
  return 1
}
probe() { # $1 = tag ; prints result json
  adb shell "run-as $PROBE rm -f files/result.json" 2>/dev/null
  adb shell am start -n $PROBE/.ProbeActivity --es tag "$1" --es urls "https://www.gstatic.com/generate_204,https://speed.cloudflare.com/__down?bytes=1048576,https://www.google.com/,https://www.youtube.com/generate_204" >/dev/null
  for i in $(seq 1 90); do R=$(adb shell "run-as $PROBE cat files/result.json" 2>/dev/null); [ -n "$R" ] && { echo "$R"; return 0; }; sleep 1; done
  echo "{}"; return 1
}
okprobe() { python3 -c '
import json,sys
r=json.loads(sys.argv[1]); res=r.get("results",[])
ok = r.get("vpn") and len(res)==4 and res[0]["code"]==204 and res[1]["code"]==200 and res[1]["bytes"]>=1048576 and res[2]["code"]==200 and res[3]["code"]==204
print(("PASS " if ok else "BAD ")+json.dumps(r)); sys.exit(0 if ok else 1)' "$1"; }

run_case() { # $1 name, $2 state, $3 seconds to wait for the connect result
  CASE=$1; R=""
  echo "=== case $1"
  adb shell am start -n $PKG/.MainActivity --ez e2e_disconnect true >/dev/null; sleep 3; : > $W/access.log
  if [ "$1" = single ]; then
    adb shell settings put system accelerometer_rotation 0
    adb shell settings put system user_rotation 0
    sleep 1
  fi
  adb logcat -c
  # Start onCreate with both commands: configuration recreation must not replay either.
  load "$2" true || { fail "$1: fixture state load failed"; return; }
  C=$(waitlog "check ok=|connect failed" ${3:-60}) || { fail "$1: no connect result"; return; }
  echo "app: $C"
  echo "$C" | grep -q "check ok=true" || fail "$1: in-app check through 127.0.0.1:10808 failed"
  if [ "$1" = single ]; then
    # The initial Intent contains connect+reload. Rotation recreates this Activity;
    # replaying it would tear down the live TUN and restart Xray.
    adb shell settings put system accelerometer_rotation 0
    adb shell settings put system user_rotation 1
    waitlog "fixture activity restored" 20 >/dev/null || fail "single: rotation did not recreate Activity"
    adb shell settings put system user_rotation 0
    sleep 2
    LC=$(adb logcat -d -s E2E:I)
    printf '%s' "$LC" | python3 tools/e2e/verify_activity_recreation.py || fail "single: recreation replayed a command or restarted VPN"
  fi
  R=$(probe $1); okprobe "$R" || fail "$1: probe app traffic through VPN failed"
  S=$(adb logcat -d -s E2E:I | grep "socks port=" | tail -1); echo "app: $S"
  echo "$S" | grep -q "auth=true" || fail "$1: local SOCKS is not password-protected (stealth)"
  adb shell cat /proc/net/tcp /proc/net/tcp6 | awk '$4=="0A"{print $2}' | grep -qi ":2A38$" && fail "$1: something listens on 10808 (proxy must be hidden)"
}

: > $W/access.log
run_case single single.json
N1=$(grep -c "email: u8443" $W/access.log); echo "server :8443 accepted after single: $N1"
[ "$N1" -gt 0 ] || fail "single: server saw no traffic (traffic did not go through the server)"

# An empty server catalogue must fail closed as a VPN connection.
# Android's ordinary internet while disconnected is not a VPN success.
CASE=byedpi-rejected; R=""
adb shell am start -n $PKG/.MainActivity --ez e2e_disconnect true >/dev/null
sleep 3; : > $W/access.log; adb logcat -c
load byedpi.json true || fail "serverless: fixture load failed"
C=$(waitlog "connect failed|check ok=" 60) || fail "serverless: no explicit rejection"
printf '%s\n' "$C" | grep -q 'connect failed' || fail "serverless: empty catalogue was not rejected"
adb logcat -d -s E2E:I | grep -q 'check ok=true' && fail "serverless: reported false VPN success"
R=$(probe serverless-rejected)
printf '%s' "$R" | python3 -c 'import json,sys;r=json.load(sys.stdin);sys.exit(0 if r.get("vpn") is False else 1)' || fail "serverless: VPN unexpectedly active"
c=$(grep -c "email: u" $W/access.log)
[ "$c" -eq 0 ] || fail "serverless: unexpected fixture server traffic"

: > $W/access.log
run_case multi multi.json
# Optional mask improvements must not send another hard START during the multi transfer.
STARTS=$(adb logcat -d -s E2E:I | grep -c 'service command=start state=')
[ "$STARTS" -eq 1 ] || fail "multi: optional background work hard-restarted VPN"
# Background speed-up: the selected e2e-dead server must be noticed and dropped (no buttons).
adb shell am start -n $PKG/.MainActivity --ez e2e_optimize true >/dev/null
O=$(waitlog "optimize result=" 150) || fail "optimize: no result"
echo "optimize: $O"; adb logcat -d -s E2E:I | grep "optimize sel=" | tail -1
# The return value is a delta, not the final selection: background optimization may
# have already removed the dead route. Require completed optimization AND actual state.
echo "$O" | python3 tools/e2e/verify_optimize_selection.py || fail "optimize: dead server remains selected, no working fixture selected, or optimizer failed"
R=$(probe multi-after-optimize); okprobe "$R" || fail "optimize: other UID cannot transfer through the resulting VPN"
for p in 8443 8444 8447; do
  c=$(grep -c "email: u$p" $W/access.log); echo "multi: server :$p accepted $c"
  [ "$c" -gt 0 ] || fail "multi: round-robin never used server :$p"
done

: > $W/access.log
run_case tpws tpws.json
adb logcat -d -s E2E:I | grep "socks port=" | tail -1 | grep -q "dpi=zapret" || fail "tpws: zapret strategy was not used"

: > $W/access.log
run_case ownmask ownmask.json
adb logcat -d -s E2E:I | grep "socks port=" | tail -1 | grep -q "VCARD_CASCADE" || fail "ownmask: own cascade engine not started"
for p in 8443 8444; do
  c=$(grep -c "email: u$p" $W/access.log); echo "ownmask: server :$p accepted $c"
  [ "$c" -gt 0 ] || fail "ownmask: server :$p not reached through own masking"
done

: > $W/access.log
run_case xraydpi xraydpi.json
adb logcat -d -s E2E:I | grep "socks port=" | tail -1 | grep -q "dpi=VLESS Card · Xray" || fail "xraydpi: Xray fragment engine was not used"

: > $W/access.log
run_case services services.json
# Access logs keep the original IP even after SNI sniffing. Host-side route tags prove the requested
# domain without relying on IP-to-name guesses; unrelated control-plane DNS has its own qa-other tag.
yt=$(grep "email: u8443" $W/access.log | grep -c "qa-youtube"); gs=$(grep "email: u8443" $W/access.log | grep -c "qa-gstatic\|qa-speed\|qa-google")
echo "services: through server youtube=$yt other=$gs"
[ "$yt" -gt 0 ] || fail "services: YouTube did not go through the server"
[ "$gs" -gt 0 ] || fail "services: other sites bypassed the required server"

: > $W/access.log
run_case auto auto.json 180
adb logcat -d -s E2E:I | grep "socks port=" | tail -1 | grep -q "mode=SERVERS" || fail "auto: did not pick the working server"
c=$(grep -c "email: u8443" $W/access.log); echo "auto: server :8443 accepted $c"
[ "$c" -gt 0 ] || fail "auto: traffic did not go through the auto-picked server"

# Do not merely assert the selected checkboxes: this log is emitted after the real Xray core starts.
: > $W/access.log
run_case auto-eight auto-eight.json 180
P=$(waitlog "pool size=8 " 150) || fail "auto-eight: real Xray pool never reached eight outbounds"
echo "eight: $P"

# Production runner -> measured winner -> actual live VPN config, using native DPI.
# Tor is intentionally not faked here; Tor socket chaining has a separate closed host fixture.
: > $W/access.log
run_case auto-chain auto-chain.json 180
adb logcat -c
adb shell am start -n $PKG/.MainActivity --ez e2e_chain true >/dev/null
CH=$(waitlog "auto-chain-e2e applied=|auto-chain-e2e FAIL" 90) || fail "auto-chain: no completion"
echo "chain: $CH"
echo "$CH" | grep -q "applied=DPI_SERVER" || fail "auto-chain: measured DPI-server chain not applied"
R=$(probe auto-chain); okprobe "$R" || fail "auto-chain: other UID cannot transfer through selected live chain"
c=$(grep -c "email: u8443" $W/access.log)
[ "$c" -gt 0 ] || fail "auto-chain: authenticated server saw no traffic"
if echo "$CH" | grep -q "applied=DPI_SERVER" && okprobe "$R" >/dev/null && [ "$c" -gt 0 ]; then
  echo "::notice title=Auto chain verified::Production selector applied DPI_SERVER; other-UID HTTPS transfer PASS; authenticated server reached"
fi

# Stored legacy proxyOnly must not reopen unauthenticated public listeners.
run_case retired-proxy proxy.json
adb logcat -d -s E2E:I | grep "socks port=" | tail -1 | grep -q 'auth=true.*proxy=false' || fail "retired-proxy: legacy preference exposed proxy instead of protected VPN"
adb shell cat /proc/net/tcp /proc/net/tcp6 | awk '$4=="0A"{print $2}' | grep -qi ':2A39$' && fail "retired-proxy: HTTP port 10809 unexpectedly open"
c=$(grep -c "email: u8443" $W/access.log)
[ "$c" -gt 0 ] || fail "retired-proxy: required server saw no traffic"

CASE=warp; R=""
echo "=== case warp (register free WARP accounts, find endpoint + mask, connect)"
adb shell am start -n $PKG/.MainActivity --ez e2e_disconnect true >/dev/null; sleep 3
load warp.json; adb logcat -c
adb shell am start -n $PKG/.MainActivity --ez e2e_warp true >/dev/null
WR=$(waitlog "action\[WARP\]" 420) || fail "warp: no result"
adb logcat -d -s E2E:I | grep "warp " ; echo "warp: $WR"
adb logcat -d -s E2E:I | grep "warp accounts" | grep -q "plus=[1-9]" && echo "warp: WARP+ key bound" || echo "::warning title=warp::no WARP+ key could be bound (all built-in keys busy?)"
if echo "$WR" | grep -q "WARP готов"; then
  # setupWarp starts the VPN itself when permission is already granted.
  # Preserve that start/check in logcat; a second START tears down a live tunnel.
  C=$(waitlog "check ok=|connect failed" 90) || fail "warp: no connect result"
  echo "app: $C"; echo "$C" | grep -q "check ok=true" || fail "warp: in-app check through WARP failed"
  R=$(probe warp)
  # Proven CI failure: Google 429, while VPN/data/YouTube checks pass. One bounded backoff,
  # not a bypass: the original four exact status/body checks still decide success.
  if printf '%s' "$R" | python3 tools/e2e/http429_retry.py; then
    echo "::notice title=E2E HTTP rate limit::Google returned 429; one 15-second backoff before the unchanged full probe"
    sleep 15
    R=$(probe warp-rate-limit-retry)
  elif printf '%s' "$R" | python3 tools/e2e/transient_dns_retry.py; then
    # Preserve the original failure. No cached success merging or relaxed assertions.
    printf '%s' "$R" | python3 tools/e2e/summarize_failure.py --case warp --probe
    echo "::notice title=E2E DNS diagnostic::First origin UnknownHostException with three passing probes; one full fresh probe after 15 seconds; root cause not established"
    sleep 15
    R=$(probe warp-dns-retry)
  fi
  okprobe "$R" || fail "warp: probe app traffic through WARP failed"
  STARTS=$(adb logcat -d -s E2E:I | grep -c 'service command=start state=')
  [ "$STARTS" -eq 1 ] || fail "warp: expected one automatic VPN start"
else fail "warp: no working WARP endpoint"; fi

echo "=== in-app tester (multi-inbound Xray instance)"
CASE=tester; R=""
adb shell am start -n $PKG/.MainActivity --ez e2e_disconnect true >/dev/null; sleep 2
load test.json; adb logcat -c
adb shell am start -n $PKG/.MainActivity --ez e2e_test true >/dev/null
T=$(waitlog "action\[Проверка серверов\]" 120) || fail "tester: no result"
echo "tester: $T"
echo "$T" | grep -q "Работают: 3 из 3" || fail "tester: expected 3 working of 3 reachable"

# Standalone DPI was retired: search now tests masks on authenticated servers.
# TPWS, Xray and fixed ByeDPI engine transfers remain separate mandatory cases above.
CASE=server-mask-search; R=""
adb logcat -c
adb shell am start -n $PKG/.MainActivity --ez e2e_dpi true >/dev/null
D=$(waitlog "action\[Подбор маскировки VLESS\]" 240) || fail "mask search: no result"
printf '%s' "$D" | python3 tools/e2e/verify_server_mask_search.py || fail "mask search: incomplete or invalid server-backed result"
echo "mask search: $D"
# Do not reload the state: use the actual masks persisted by the production search.
: > $W/access.log; adb logcat -c
adb shell am start -n $PKG/.MainActivity --ez e2e_connect true >/dev/null
C=$(waitlog "check ok=|connect failed" 60) || fail "mask search: selected masks did not connect"
printf '%s' "$C" | grep -q 'check ok=true' || fail "mask search: no live VPN success"
R=$(probe server-mask-search); okprobe "$R" || fail "mask search: selected masks cannot carry other-UID HTTPS bodies"
c=$(grep -c "email: u" $W/access.log)
[ "$c" -gt 0 ] || fail "mask search: traffic bypassed authenticated servers"

adb logcat -c
echo "=== manual all-server check on a 128-entry catalogue"
CASE=manual-check; R=""
load mass-check.json || fail "manual-check: fixture state load failed"
adb logcat -c
tap_ui "Серверы" || fail "manual-check: could not open the server screen"
pattern='action\[Проверка серверов\]'
baseline=$(logcount "$pattern")
tap_ui "Проверить все серверы" || fail "manual-check: check-all button not accessible"
T=$(waitlog "action\[Проверка серверов\]" 120) || fail "manual-check: no result"
T=$(waitlog_after "$pattern" "$baseline" 120) || fail "manual-check: no new result"
echo "manual-check: $T"
echo "$T" | grep -q "Работают: 1 из 1 доступных по TCP (всего 128)" || fail "manual-check: expected one working of one reachable in a 128-server list"
adb logcat -d -s E2E:I | grep -q "action\\[Подбор маскировки\\]" && fail "manual-check: check-all unexpectedly started mask search"
adb shell pidof "$PKG" >/dev/null || fail "manual-check: app exited during server scan"
adb logcat -d | grep -q "FATAL EXCEPTION" && fail "manual-check: app crashed during server scan"
adb exec-out screencap -p > "$W/server-check.png"
tap_ui "Телефон" || fail "manual-check: could not open the device screen"
sleep 2
adb exec-out screencap -p > "$W/device-screen.png"

load test.json || fail "masks: fixture state load failed"
adb logcat -c
adb shell am start -n $PKG/.MainActivity --ez e2e_masks true >/dev/null
M=$(waitlog "action\[Подбор маскировки\]" 240) || fail "masks: no result"
echo "masks: $M"
echo "$M" | grep -q "найдена для 3 из 4" || fail "masks: expected masks for 3 of 4 servers"

adb shell am start -n $PKG/.MainActivity --ez e2e_disconnect true >/dev/null
adb logcat -d > $W/logcat.txt
grep -E "FATAL EXCEPTION|Fatal signal" -A20 $W/logcat.txt | head -40
cp $W/server.log $W/access.log . 2>/dev/null
echo "E2E failures: $FAILS"
[ $FAILS -eq 0 ] && echo "E2E ALL OK"
exit $FAILS
