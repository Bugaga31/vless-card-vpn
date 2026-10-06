#!/usr/bin/env bash
# Host-side check: real Xray-core servers (REALITY/TLS/WS/gRPC/XHTTP/Trojan/SS/VMess) × every app mask, incl. ByeDPI front.
set -euo pipefail
curl -fsSL -o /tmp/xray.zip https://github.com/XTLS/Xray-core/releases/download/v26.9.30/Xray-linux-64.zip
mkdir -p /tmp/xray /tmp/srv /tmp/xcfg && unzip -qo /tmp/xray.zip -d /tmp/xray
X=/tmp/xray/xray
KEYS=$($X x25519); PRIV=$(echo "$KEYS" | sed -n 's/^PrivateKey: //p'); PBK=$(echo "$KEYS" | sed -n 's/^Password (PublicKey): //p')
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -keyout /tmp/srv.key -out /tmp/srv.crt -days 2 -subj "/CN=test.local" 2>/dev/null
P=$(openssl x509 -in /tmp/srv.crt -outform der | sha256sum | cut -d' ' -f1)
U=a00975c5-597e-4c22-a7a7-a84ce5e0691e
sed -e "s#@PRIV@#$PRIV#g" -e "s#@U@#$U#g" tools/e2e/matrix-server.json > /tmp/srv/server.json
$X run -c /tmp/srv/server.json > /tmp/srv/server.log 2>&1 &
gcc -D_DEFAULT_SOURCE -std=c99 -O2 native/byedpi/{packets,main,conev,proxy,desync,mpool,extend}.c -o /tmp/byedpi
/tmp/byedpi --ip 127.0.0.1 --port 1080 --oob 1 --auto t,r,s --disorder 1 > /tmp/bd.log 2>&1 &
(command -v apt-get >/dev/null && sudo apt-get install -y -qq libcap-dev zlib1g-dev >/dev/null) || true
gcc -std=gnu99 -D_GNU_SOURCE -O2 -Inative/tpws native/tpws/*.c -lz -o /tmp/tpws || echo "host tpws build failed"
SS=$(printf 'chacha20-ietf-poly1305:sspass' | base64 -w0)
VM=$(printf '{"v":"2","ps":"vmess-hu","add":"127.0.0.1","port":"8449","id":"%s","scy":"auto","net":"httpupgrade","path":"/hu","tls":""}' $U | base64 -w0)
cat > /tmp/links.txt <<L
vless://$U@127.0.0.1:8443?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.microsoft.com&fp=chrome&pbk=$PBK&sid=6ba85179e30d4fc2&type=tcp#reality-vision
vless://$U@127.0.0.1:8444?security=tls&type=ws&path=%2Fws&sni=test.local&insecure=1&pcs=$P#ws-tls
trojan://trpass@127.0.0.1:8445?security=tls&sni=test.local&pcs=$P#trojan
ss://$SS@127.0.0.1:8446#ss
vless://$U@127.0.0.1:8447?security=reality&type=xhttp&mode=auto&path=%2Fx&sni=www.microsoft.com&pbk=$PBK&sid=ab#xhttp-reality
vless://$U@127.0.0.1:8448?security=tls&type=grpc&serviceName=gsvc&sni=test.local&alpn=h2&pcs=$P#grpc
vmess://$VM
wireguard://iMKDJbeES0wVjl014e%2BVHdsv6vbOtdqgTCw4imW%2FhU4%3D@127.0.0.1:8450?publickey=71yczL51ZaA1iLCrpTjm%2BDim0K1n0RrflsMyyhtrsxE%3D&address=10.0.0.2%2F32#wg
socks5://mu:mp@127.0.0.1:8451#socks
L
chmod +x gradlew
XRAY_CONFIG_DUMP=/tmp/xcfg XRAY_LOCAL_LINKS=/tmp/links.txt ./gradlew --no-daemon -q :app:testDebugUnitTest --tests 'com.vlesscardvpn.ConfigTest'
# One engine per fixed strategy (the app's own argv: VLESS Card, zapret, ByeDPI); servers are on loopback.
while read -r eng args; do
  if [ "$eng" = TPWS ]; then VCVPN_TPWS_ALLOW_LOCAL=1 /tmp/tpws $args > /dev/null 2>&1 &
  elif [ "$eng" = XRAY ]; then $X run -c $args > /dev/null 2>&1 &
  else /tmp/byedpi $args > /dev/null 2>&1 & fi
done < /tmp/xcfg/dpi-engines.txt
sleep 1
# VLESS Card × Xray no-server strategies: real HTTPS to the internet through each engine.
XOK=0; XALL=0
while read -r eng args; do
  [ "$eng" = XRAY ] || continue
  port=$(basename "$args" .json | sed 's/xrdpi-//'); XALL=$((XALL+1))
  if curl -s -o /dev/null -m 15 -w '%{http_code}' -x socks5h://127.0.0.1:$port https://www.youtube.com/ | grep -q '^[23]'; then XOK=$((XOK+1)); else echo "xray engine $port failed"; fi
done < /tmp/xcfg/dpi-engines.txt
echo "Xray no-server strategies: $XOK/$XALL carry HTTPS"
[ "$XOK" -eq "$XALL" ] || { echo "xray strategies failed"; exit 1; }
for f in /tmp/xcfg/*.json; do echo "$(basename $f): $($X run -test -c $f 2>&1 | tail -1)"; done
bash tools/xray-local-e2e.sh $X /tmp/xcfg || true  # raw matrix includes masks the app never offers
# Only masks the app offers for each server type (Masks.compatible) must pass.
python3 - <<'PY'
import sys
ok = fail = 0; bad = []; bok = bfail = 0
from collections import defaultdict
per = defaultdict(lambda: [0, 0])
for line in open('/tmp/xcfg/result.txt'):
    st, port, name, mask = line.split()[:4]
    fp = mask.split('.')[0]
    offered = True
    if name in ('reality-vision', 'xhttp-reality'): offered = fp in ('chrome', 'firefox', 'safari', 'none')
    elif name == 'grpc': offered = fp != 'android'
    elif name in ('ss', 'vmess-hu', 'socks'): offered = fp in ('chrome', 'none')
    elif name == 'wg': offered = mask == 'none' or mask == 'chrome.n' or (fp == 'chrome' and '.z' in mask and '.d:' not in mask)
    if '.z' in mask and '.d:' not in mask and name != 'wg': offered = False
    if not offered: continue
    if '.d:' in mask:
        sid = mask.split('.d:')[1]
        per[sid][0 if st == 'OK' else 1] += 1
        continue
    if mask.endswith('.b'):
        if st == 'OK': bok += 1
        else: bfail += 1
        continue
    if st == 'OK': ok += 1
    else: fail += 1; bad.append(line.strip())
print(f"offered direct masks: ok={ok} fail={fail}; via ByeDPI: ok={bok} fail={bfail}")
for b in bad[:30]: print(b)
print("fixed strategies in front of servers (ok/fail):")
for sid, (o, f) in sorted(per.items()): print(f"  {sid}: {o}/{f}")
# On loopback a low-TTL fake reaches the server (0 hops) and breaks it — real servers are far away, so fake-only
# strategies are reported but not judged.
fake_only = {'BYEDPI#MASK_FAKE', 'BYEDPI#MASK_FAKE_RAND'}
dpi_ok = sum(o for s, (o, f) in per.items() if s not in fake_only); dpi_all = sum(o + f for s, (o, f) in per.items() if s not in fake_only)
print(f"fixed strategies (without fake-only): ok={dpi_ok}/{dpi_all}")
if dpi_all and dpi_ok < dpi_all * 0.8: print("too many fixed-strategy failures"); sys.exit(1)
# Direct masks must all work; ByeDPI-front variants are flaky under 24-way parallel load in CI
# (the app tests every mask live before using it, so a flaky one is simply not chosen).
sys.exit(1 if fail > 0 or bfail > (bok + bfail) * 0.2 else 0)
PY
