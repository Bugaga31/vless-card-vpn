#!/usr/bin/env bash
# Emulator end-to-end check of the real chain:
#   probe app (other UID) → VPN TUN → hev-socks5-tunnel → SOCKS 127.0.0.1:10808 → Xray-core → server on the host → internet.
# Server-side Xray access log proves which server carried the traffic. Run inside android-emulator-runner.
set -uo pipefail
PKG=com.vlesscardvpn; PROBE=com.vlesscardvpn.netprobe
W=/tmp/e2e; mkdir -p $W; FAILS=0
XRAY=${XRAY:-/tmp/xray/xray}
save_case() {
  adb logcat -d -s E2E:I XrayCore:W '*:S' > "$W/case-$1.log" 2>&1
  adb shell uiautomator dump /sdcard/e2e-window.xml >/dev/null 2>&1
  adb shell cat /sdcard/e2e-window.xml > "$W/case-$1.xml" 2>&1
  cp "$W/access.log" "$W/case-$1-access.log"
  adb shell "run-as $PROBE cat files/result.json" > "$W/case-$1-probe.json" 2>/dev/null || true
}
fail() { echo "::error title=e2e::$*"; echo "FAIL: $*"; FAILS=$((FAILS+1)); }

# ---- servers on the host (emulator sees the host as 10.0.2.2)
KEYS=$($XRAY x25519); PRIV=$(echo "$KEYS" | sed -n 's/^PrivateKey: //p'); PBK=$(echo "$KEYS" | sed -n 's/^Password (PublicKey): //p')
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -keyout $W/k.pem -out $W/c.pem -days 2 -subj "/CN=test.local" 2>/dev/null
U=a00975c5-597e-4c22-a7a7-a84ce5e0691e
sed -e "s#@PRIV@#$PRIV#g" -e "s#@U@#$U#g" -e "s#@W@#$W#g" tools/e2e/server.json > $W/server.json
FIXTURE_PID=
if [ -n "${E2E_SERVICE_FIXTURE:-}" ]; then
  python3 tools/e2e/service_fixture.py patch "$W/server.json"
  python3 tools/e2e/service_fixture.py serve "$E2E_SERVICE_FIXTURE" > "$W/service-fixture.log" 2>&1 & FIXTURE_PID=$!
  echo 'E2E uses an isolated protocol/media fixture, not live Telegram/YouTube availability'
fi
$XRAY run -c $W/server.json > $W/server.log 2>&1 & SRV=$!
trap 'kill $SRV ${FIXTURE_PID:-} 2>/dev/null || true' EXIT
sleep 2
python3 tools/e2e/make_state.py $W "$PBK"

adb wait-for-device
adb install -r -g app/build/outputs/apk/debug/app-debug.apk | tail -1
adb install -r -t vpn-test-probe/build/outputs/apk/debug/vpn-test-probe-debug.apk | tail -1
adb shell appops set $PKG ACTIVATE_VPN allow
adb shell am start -W -n $PKG/.MainActivity >/dev/null; sleep 3

load() { # $1 = state file
  adb push $W/$1 /data/local/tmp/state.json >/dev/null
  adb shell "run-as $PKG sh -c 'mkdir -p files && cp /data/local/tmp/state.json files/state.json'"
  adb shell am start -n $PKG/.MainActivity --ez e2e_reload true >/dev/null; sleep 1
}
waitlog() { # $1 = regex, $2 = seconds
  for i in $(seq 1 $2); do L=$(adb logcat -d -s E2E:I | grep -E "$1" | tail -1); [ -n "$L" ] && { echo "$L"; return 0; }; sleep 1; done; return 1
}
probe() { # $1 = tag ; prints result json
  adb shell "run-as $PROBE rm -f files/result.json" 2>/dev/null
  local urls="${2:-https://www.gstatic.com/generate_204,https://speed.cloudflare.com/__down?bytes=1048576,https://www.google.com/,https://www.youtube.com/generate_204}"
  adb shell am start -n $PROBE/.ProbeActivity --es tag "$1" --es urls "$urls" >/dev/null
  for i in $(seq 1 90); do R=$(adb shell "run-as $PROBE cat files/result.json" 2>/dev/null); [ -n "$R" ] && { echo "$R"; return 0; }; sleep 1; done
  echo "{}"; return 1
}
okprobe() { python3 tools/e2e/validate_probe.py "$1" "${2:-0}"; }

run_case() { # $1 name, $2 state, $3 seconds to wait for the connect result
  echo "=== case $1"
  adb shell am start -n $PKG/.MainActivity --ez e2e_disconnect true >/dev/null; sleep 3; : > $W/access.log
  load $2; adb logcat -c
  adb shell am start -n $PKG/.MainActivity --ez e2e_connect true >/dev/null
  C=$(waitlog "check ok=|connect failed" ${3:-60}) || { fail "$1: no connect result"; save_case "$1"; return; }
  echo "app: $C"
  echo "$C" | grep -q "check ok=true" || fail "$1: in-app check through 127.0.0.1:10808 failed"
  R=$(probe $1); okprobe "$R" || fail "$1: probe app traffic through VPN failed"
  S=$(adb logcat -d -s E2E:I | grep "socks port=" | tail -1); echo "app: $S"
  echo "$S" | grep -q "auth=true" || fail "$1: local SOCKS is not password-protected (stealth)"
  save_case "$1"
  adb shell cat /proc/net/tcp /proc/net/tcp6 | awk '$4=="0A"{print $2}' | grep -qi ":2A38$" && fail "$1: something listens on 10808 (proxy must be hidden)"
}

: > $W/access.log
run_case single single.json
N1=$(grep -c "email: u8443" $W/access.log); echo "server :8443 accepted after single: $N1"
[ "$N1" -gt 0 ] || fail "single: server saw no traffic (traffic did not go through the server)"
# The remaining suite cannot establish anything if the baseline chain/resource is broken.
# Fail red immediately, retaining the exact helper/TLS result instead of waiting through every case.
if [ "$FAILS" -gt 0 ]; then
  adb logcat -d > "$W/logcat.txt"
  echo "E2E baseline failed; remaining cases NOT RUN (not PASS)"
  exit "$FAILS"
fi

: > $W/access.log
run_case byedpi byedpi.json
c=$(grep -c "email: u" $W/access.log); echo "byedpi: server lines $c (must be 0; runs before multi so no balancer observatory can linger)"
[ "$c" -eq 0 ] || { fail "byedpi: traffic unexpectedly went through a server"; cat $W/access.log | tail -5; }

: > $W/access.log
run_case multi multi.json
# Background speed-up: the selected e2e-dead server must be noticed and dropped (no buttons).
adb shell am start -n $PKG/.MainActivity --ez e2e_optimize true >/dev/null
O=$(waitlog "optimize result=" 150) || fail "optimize: no result"
echo "optimize: $O"; adb logcat -d -s E2E:I | grep "optimize sel=" | tail -1
# The scheduled optimizer may have already removed the dead selection before this explicit call.
# A no-op return is not failure, but stored selection alone is not success: require the real core.
adb logcat -d -s E2E:I | grep -q "optimize sel=4 ok=3 .*changed=true" || fail "optimize: dead selection was not removed"
P=$(waitlog "pool size=[1-3] ids=" 180) || fail "optimize: running Xray pool did not drop the dead server"
echo "optimized core: $P"
printf '%s\n' "$P" | python3 tools/e2e/validate_pool.py "$W/multi.json" || fail "optimize: running core includes dead/unknown route"
save_case multi # include the applied-pool outcome, not only the initial connect snapshot
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
# Xray starts on fallback outbound 0 until its first observatory round finishes.
# Drive fresh real app connections in a bounded window; still require BOTH actual masked exits.
for round in 1 2 3; do
  grep -q "email: u8443" "$W/access.log" && grep -q "email: u8444" "$W/access.log" && break
  sleep 5
  R=$(probe "ownmask-balanced-$round"); okprobe "$R" || fail "ownmask: balanced app traffic failed"
done
save_case ownmask
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
[ "$gs" -eq 0 ] || fail "services: other sites went through the server (must be direct)"

: > $W/access.log
run_case auto auto.json 180
adb logcat -d -s E2E:I | grep "socks port=" | tail -1 | grep -q "mode=SERVERS" || fail "auto: did not pick the working server"
c=$(grep -c "email: u8443" $W/access.log); echo "auto: server :8443 accepted $c"
[ "$c" -gt 0 ] || fail "auto: traffic did not go through the auto-picked server"

# Revised contract: foreground Auto must NOT search all 41 configurations for a tail route.
adb shell am start -n $PKG/.MainActivity --ez e2e_disconnect true >/dev/null; sleep 3
load auto-auth-tail.json; adb logcat -c
adb shell am start -n $PKG/.MainActivity --ez e2e_connect true >/dev/null
F=$(waitlog "action\[Авто: проверяю соединение\]" 25) || fail "auto-budget: attempt did not terminate within bounded allowance"
adb logcat -d -s E2E:I | grep -q "auto-fast catalog=41 candidates=8" || fail "auto-budget: foreground candidate bound missing"
adb logcat -d -s E2E:I | grep -q "check ok=true" && fail "auto-budget: untested tail route was falsely connected"
save_case auto-budget

# Explicit full checking is separate: all 41 authenticated configurations receive results,
# including the only valid credentials after the old 30-route limit.
for fixture in auto-wide auto-auth-tail; do
  adb shell am start -n $PKG/.MainActivity --ez e2e_disconnect true >/dev/null; sleep 3
  load $fixture.json; adb logcat -c
  adb shell am start -n $PKG/.MainActivity --ez e2e_test true >/dev/null
  A=$(waitlog "action\[Проверка серверов\]" 300) || fail "$fixture: full check did not finish"
  echo "$A" | grep -q "Работают: 1 из 41. Проверен весь каталог" || fail "$fixture: not all 41 protocol results obtained"
  save_case $fixture
 done

# Do not merely assert the selected checkboxes: this log is emitted after the real Xray core starts.
: > $W/access.log
run_case auto-eight auto-eight.json 180
# Two bounded reserve rounds plus the existing 60 s active-traffic handover deferral.
# This is an eventual-pool correctness gate, not a claimed connection-time benchmark.
P=$(waitlog "pool size=8 " 240) || fail "auto-eight: real Xray pool never reached eight outbounds"
echo "eight: $P"
save_case auto-eight # retain background pool-fill diagnostics, not just the initial two routes

# Once eight are selected, all eight remaining configurations must get REAL Xray/service results.
: > $W/access.log
run_case auto-background auto-background.json 180
B=$(waitlog "catalog-background .*completed=8 total=8" 300) || fail "auto-background: remaining catalog did not complete real checks"
echo "background: $B"
save_case auto-background

echo "=== case proxy (no VPN: SOCKS5 10808 + HTTP 10809 for other apps)"
adb shell am start -n $PKG/.MainActivity --ez e2e_disconnect true >/dev/null; sleep 3; : > $W/access.log
load proxy.json; adb logcat -c
adb shell am start -n $PKG/.MainActivity --ez e2e_connect true >/dev/null
C=$(waitlog "check ok=|connect failed" 60) || fail "proxy: no connect result"
echo "app: $C"; echo "$C" | grep -q "check ok=true" || fail "proxy: in-app check failed"
adb logcat -d -s E2E:I | grep "socks port=" | tail -1 | grep -q "port=10808 auth=false.*proxy=true" || fail "proxy: not a plain SOCKS on 10808"
adb shell ip addr | grep -q "tun0" && fail "proxy: a TUN interface exists (must be proxy only)"
adb forward tcp:18081 tcp:10809 >/dev/null; adb forward tcp:18080 tcp:10808 >/dev/null
H=$(curl -s -m 20 -o /dev/null -w '%{http_code}' -x http://127.0.0.1:18081 https://www.gstatic.com/generate_204); echo "proxy: HTTP proxy → $H"
[ "$H" = "204" ] || fail "proxy: HTTP proxy 10809 does not work"
S5=$(curl -s -m 20 -o /dev/null -w '%{http_code}' -x socks5h://127.0.0.1:18080 https://www.google.com/); echo "proxy: SOCKS5 → $S5"
[ "$S5" = "200" ] || fail "proxy: SOCKS5 10808 does not work"
adb forward --remove-all
c=$(grep -c "email: u8443" $W/access.log); echo "proxy: server :8443 accepted $c"
[ "$c" -gt 0 ] || fail "proxy: traffic did not go through the server"

echo "=== case warp (register free WARP accounts, find endpoint + mask, connect)"
adb shell am start -n $PKG/.MainActivity --ez e2e_disconnect true >/dev/null; sleep 3
load warp.json; adb logcat -c
adb shell am start -n $PKG/.MainActivity --ez e2e_warp true >/dev/null
WR=$(waitlog "action\[WARP\]" 420) || fail "warp: no result"
adb logcat -d -s E2E:I | grep "warp " ; echo "warp: $WR"
# Preserve registration evidence before the connect stage clears logcat.
adb logcat -d -s E2E:I XrayCore:W '*:S' > "$W/warp-registration.log" 2>&1
adb logcat -d -s E2E:I | grep "warp accounts" | grep -q "plus=[1-9]" && echo "warp: WARP+ key bound" || echo "::warning title=warp::no WARP+ key could be bound (all built-in keys busy?)"
if echo "$WR" | grep -q "WARP готов"; then
  adb logcat -c
  adb shell am start -n $PKG/.MainActivity --ez e2e_connect true >/dev/null
  C=$(waitlog "check ok=|connect failed" 90) || fail "warp: no connect result"
  echo "app: $C"; echo "$C" | grep -q "check ok=true" || fail "warp: in-app check through WARP failed"
  # Search homepages can return anti-bot 429 on shared WARP exits; that is not a transport verdict.
  # Require two independent full 1 MiB HTTPS transfers instead, with TLS/status/byte checks intact.
  WARP_URLS="https://www.gstatic.com/generate_204,https://speed.cloudflare.com/__down?bytes=1048576,https://cachefly.cachefly.net/1mb.test,https://www.youtube.com/generate_204"
  before=$(adb logcat -d -s E2E:I | grep -c "physical network changed")
  R=$(probe warp "$WARP_URLS")
  after=$(adb logcat -d -s E2E:I | grep -c "physical network changed")
  if [ "$after" -gt "$before" ] && python3 tools/e2e/validate_probe.py --dns-handoff "$R"; then
    printf '%s\n' "$R" > "$W/warp-initial-probe.json"
    echo "WARP: one DNS miss during an observed physical handoff; retry the FULL strict probe once"
    sleep 12 # let Android's negative DNS cache expire; no DNS/TLS bypass or direct fallback
    R=$(probe warp-after-handoff "$WARP_URLS")
  fi
  okprobe "$R" 1048576 || fail "warp: probe app traffic through WARP failed"
else fail "warp: no working WARP endpoint"; fi
# Always capture the real cross-UID result, including FAIL, before DPI resets logcat/result.json.
save_case warp

echo "=== DPI strategy search (all engines start and carry HTTPS)"
adb shell am start -n $PKG/.MainActivity --ez e2e_disconnect true >/dev/null; sleep 2
adb logcat -c
adb shell am start -n $PKG/.MainActivity --ez e2e_dpi true >/dev/null
D=$(waitlog "action\[Подбор обхода DPI\]" 240) || fail "dpi search: no result"
echo "dpi: $D"
# Stage 1 stops after 12 passing strategies (the rest is skipped on purpose), so count passes, not "all of them".
N=$(echo "$D" | sed -n 's/.*YouTube открыли \([0-9]*\) из \([0-9]*\).*/\1/p')
[ "${N:-0}" -ge 10 ] || fail "dpi search: expected at least 10 strategies to open YouTube (no DPI on the emulator), got ${N:-0}"
echo "$D" | grep -q "Выбрана:" || fail "dpi search: no strategy chosen"
E=$(adb logcat -d -s E2E:I | grep "dpi engines" | tail -1); echo "dpi: $E"
echo "$E" | grep -q "byedpi=[1-9]" || fail "dpi search: no ByeDPI strategy worked"
echo "$E" | grep -qE "tpws=[1-9]|zapret=[1-9]" || echo "::warning title=dpi::no zapret strategy among the first passes"
echo "$E" | grep -q "xray=[1-9]" || echo "::warning title=dpi::no Xray strategy among the first passes"

echo "=== in-app tester (multi-inbound Xray instance)"
adb shell am start -n $PKG/.MainActivity --ez e2e_disconnect true >/dev/null; sleep 2
load test.json; adb logcat -c
adb shell am start -n $PKG/.MainActivity --ez e2e_test true >/dev/null
T=$(waitlog "action\[Проверка серверов\]" 120) || fail "tester: no result"
echo "tester: $T"
echo "$T" | grep -q "Работают: 3 из 4" || fail "tester: expected all 4 configurations checked, 3 working"

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
