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
count() { grep -c "email: $1\|accepted.*$2" $W/access.log 2>/dev/null || echo 0; }

run_case() { # $1 name, $2 state
  echo "=== case $1"
  adb shell am start -n $PKG/.MainActivity --ez e2e_disconnect true >/dev/null; sleep 2
  load $2; adb logcat -c
  adb shell am start -n $PKG/.MainActivity --ez e2e_connect true >/dev/null
  C=$(waitlog "check ok=|connect failed" 60) || { fail "$1: no connect result"; return; }
  echo "app: $C"
  echo "$C" | grep -q "check ok=true" || fail "$1: in-app check through 127.0.0.1:10808 failed"
  R=$(probe $1); okprobe "$R" || fail "$1: probe app traffic through VPN failed"
}

: > $W/access.log
run_case single single.json
N1=$(grep -c "accepted" $W/access.log); echo "server accepted lines after single: $N1"
[ "$N1" -gt 0 ] || fail "single: server saw no traffic (traffic did not go through the server)"

: > $W/access.log
run_case multi multi.json
for p in 8443 8444 8447; do
  c=$(grep -c "inbound-$p" $W/access.log); echo "multi: server :$p accepted $c"
  [ "$c" -gt 0 ] || fail "multi: round-robin never used server :$p"
done

: > $W/access.log
run_case byedpi byedpi.json
c=$(grep -c "accepted" $W/access.log); echo "byedpi: server lines $c (must be 0)"
[ "$c" -eq 0 ] || fail "byedpi: traffic unexpectedly went through a server"

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
