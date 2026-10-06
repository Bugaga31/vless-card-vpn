#!/usr/bin/env bash
# Emulator end-to-end check of the real chain:
#   probe app (other UID) → VPN TUN → hev-socks5-tunnel → SOCKS 127.0.0.1:10808 → Xray-core → server on the host → internet.
# Server-side Xray access log proves which server carried the traffic. Run inside android-emulator-runner.
set -uo pipefail
PKG=com.vlesscardvpn; PROBE=com.vlesscardvpn.netprobe
W=/tmp/e2e; mkdir -p $W; FAILS=0
XRAY=${XRAY:-/tmp/xray/xray}
fail() { echo "::error title=e2e::$*"; echo "FAIL: $*"; FAILS=$((FAILS+1)); }

# ---- servers on the host (emulator sees the host as 10.0.2.2)
KEYS=$($XRAY x25519); PRIV=$(echo "$KEYS" | sed -n 's/^PrivateKey: //p'); PBK=$(echo "$KEYS" | sed -n 's/^Password (PublicKey): //p')
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -keyout $W/k.pem -out $W/c.pem -days 2 -subj "/CN=test.local" 2>/dev/null
U=a00975c5-597e-4c22-a7a7-a84ce5e0691e
sed -e "s#@PRIV@#$PRIV#g" -e "s#@U@#$U#g" -e "s#@W@#$W#g" tools/e2e/server.json > $W/server.json
$XRAY run -c $W/server.json > $W/server.log 2>&1 & SRV=$!
trap 'kill $SRV 2>/dev/null' EXIT
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
  echo "=== case $1"
  adb shell am start -n $PKG/.MainActivity --ez e2e_disconnect true >/dev/null; sleep 3; : > $W/access.log
  load $2; adb logcat -c
  adb shell am start -n $PKG/.MainActivity --ez e2e_connect true >/dev/null
  C=$(waitlog "check ok=|connect failed" ${3:-60}) || { fail "$1: no connect result"; return; }
  echo "app: $C"
  echo "$C" | grep -q "check ok=true" || fail "$1: in-app check through 127.0.0.1:10808 failed"
  R=$(probe $1); okprobe "$R" || fail "$1: probe app traffic through VPN failed"
  S=$(adb logcat -d -s E2E:I | grep "socks port=" | tail -1); echo "app: $S"
  echo "$S" | grep -q "auth=true" || fail "$1: local SOCKS is not password-protected (stealth)"
  adb shell cat /proc/net/tcp /proc/net/tcp6 | awk '$4=="0A"{print $2}' | grep -qi ":2A38$" && fail "$1: something listens on 10808 (proxy must be hidden)"
}

: > $W/access.log
run_case single single.json
N1=$(grep -c "email: u8443" $W/access.log); echo "server :8443 accepted after single: $N1"
[ "$N1" -gt 0 ] || fail "single: server saw no traffic (traffic did not go through the server)"

: > $W/access.log
run_case byedpi byedpi.json
c=$(grep -c "email: u" $W/access.log); echo "byedpi: server lines $c (must be 0; runs before multi so no balancer observatory can linger)"
[ "$c" -eq 0 ] || { fail "byedpi: traffic unexpectedly went through a server"; cat $W/access.log | tail -5; }

: > $W/access.log
run_case multi multi.json
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
yt=$(grep "email: u8443" $W/access.log | grep -c "youtube"); gs=$(grep "email: u8443" $W/access.log | grep -c "gstatic\|cloudflare")
echo "services: through server youtube=$yt other=$gs"
[ "$yt" -gt 0 ] || fail "services: YouTube did not go through the server"
[ "$gs" -eq 0 ] || fail "services: other sites went through the server (must be direct)"

: > $W/access.log
run_case auto auto.json 180
adb logcat -d -s E2E:I | grep "socks port=" | tail -1 | grep -q "mode=SERVERS" || fail "auto: did not pick the working server"
c=$(grep -c "email: u8443" $W/access.log); echo "auto: server :8443 accepted $c"
[ "$c" -gt 0 ] || fail "auto: traffic did not go through the auto-picked server"

echo "=== DPI strategy search (all engines start and carry HTTPS)"
adb shell am start -n $PKG/.MainActivity --ez e2e_disconnect true >/dev/null; sleep 2
adb logcat -c
adb shell am start -n $PKG/.MainActivity --ez e2e_dpi true >/dev/null
D=$(waitlog "action\[Подбор обхода DPI\]" 240) || fail "dpi search: no result"
echo "dpi: $D"
N=$(echo "$D" | sed -n 's/.*работают \([0-9]*\) из \([0-9]*\).*/\1/p')
[ "${N:-0}" -ge 15 ] || fail "dpi search: expected at least 15 working strategies (no DPI on the emulator)"
echo "$D" | grep -q "zapret: [1-9]" || fail "dpi search: no zapret strategy worked"
echo "$D" | grep -q "Xray: [1-9]" || fail "dpi search: no VLESS Card × Xray strategy worked"

echo "=== in-app tester (multi-inbound Xray instance)"
adb shell am start -n $PKG/.MainActivity --ez e2e_disconnect true >/dev/null; sleep 2
load test.json; adb logcat -c
adb shell am start -n $PKG/.MainActivity --ez e2e_test true >/dev/null
T=$(waitlog "action\[Проверка серверов\]" 120) || fail "tester: no result"
echo "tester: $T"
echo "$T" | grep -q "Работают: 3 из 3" || fail "tester: expected 3 working of 3 reachable"

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
