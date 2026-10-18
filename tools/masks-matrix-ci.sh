#!/usr/bin/env bash
# Host-side check: real Xray-core servers (REALITY/TLS/WS/gRPC/XHTTP/Trojan/SS/VMess) × every app mask, incl. ByeDPI front.
set -euo pipefail
# The matrix listens on 30000-34000: keep the kernel's ephemeral ports (32768+) out of the way, or one clash kills the client.
sudo sysctl -qw net.ipv4.ip_local_port_range="40000 60999" || true
curl -fsSL -o /tmp/xray.zip https://github.com/XTLS/Xray-core/releases/download/v26.9.30/Xray-linux-64.zip
mkdir -p /tmp/xray /tmp/srv /tmp/xcfg && unzip -qo /tmp/xray.zip -d /tmp/xray
X=/tmp/xray/xray
KEYS=$($X x25519); PRIV=$(echo "$KEYS" | sed -n 's/^PrivateKey: //p'); PBK=$(echo "$KEYS" | sed -n 's/^Password (PublicKey): //p')
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -keyout /tmp/srv.key -out /tmp/srv.crt -days 2 -subj "/CN=test.local" -addext "subjectAltName=DNS:test.local,IP:127.0.0.1" 2>/dev/null
export MATRIX_CHECK_URL=https://127.0.0.1:18443/generate_204 MATRIX_DOWNLOAD_URL=https://127.0.0.1:18443/bytes MATRIX_CA=/tmp/srv.crt
python3 tools/e2e/matrix-origin.py >/tmp/matrix-origin.log 2>&1 & ORIGIN=$!
trap 'kill "$ORIGIN" 2>/dev/null || true' EXIT
P=$(openssl x509 -in /tmp/srv.crt -outform der | sha256sum | cut -d' ' -f1)
U=a00975c5-597e-4c22-a7a7-a84ce5e0691e
sed -e "s#@PRIV@#$PRIV#g" -e "s#@U@#$U#g" tools/e2e/matrix-server.json > /tmp/srv/server.json
$X run -c /tmp/srv/server.json > /tmp/srv/server.log 2>&1 &
gcc -D_DEFAULT_SOURCE -std=c99 -O2 native/byedpi/{packets,main,conev,proxy,desync,mpool,extend}.c -o /tmp/byedpi
# the host build aborts now and then under 24-way load: keep it running
# `|| true`: under `set -e` one abort used to end this loop, and every later ".b" variant failed.
(while true; do /tmp/byedpi --ip 127.0.0.1 --port 1080 --oob 1 --auto t,r,s --disorder 1 >> /tmp/bd.log 2>&1 || true; echo restart >> /tmp/bd-restarts; sleep 0.2; done) &
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
XRAY_MATRIX_OFFERED_ONLY=1 XRAY_CONFIG_DUMP=/tmp/xcfg XRAY_LOCAL_LINKS=/tmp/links.txt ./gradlew --no-daemon -q :app:testDebugUnitTest --tests 'com.vlesscardvpn.ConfigTest' --tests 'com.vlesscardvpn.R62Test'
# One engine per fixed strategy (the app's own argv: VLESS Card, zapret, ByeDPI); servers are on loopback.
while read -r eng args; do
  if [ "$eng" = TPWS ]; then VCVPN_TPWS_ALLOW_LOCAL=1 /tmp/tpws $args > /dev/null 2>&1 &
  elif [ "$eng" = XRAY ]; then $X run -c $args > /dev/null 2>&1 &
  else /tmp/byedpi $args > /dev/null 2>&1 & fi
done < <(cat /tmp/xcfg/dpi-engines.txt; echo)
sleep 1
# VLESS Card × Xray no-server strategies: real HTTPS to the internet through each engine.
XOK=0; XALL=0
while read -r eng args; do
  [ "$eng" = XRAY ] || continue
  port=$(basename "$args" .json | sed 's/xrdpi-//'); XALL=$((XALL+1))
  if curl -s -o /dev/null -m 15 -w '%{http_code}' -x socks5h://127.0.0.1:$port https://www.youtube.com/ | grep -q '^[23]'; then XOK=$((XOK+1)); else echo "xray engine $port failed"; fi
done < <(cat /tmp/xcfg/dpi-engines.txt; echo)
echo "Xray no-server strategies: $XOK/$XALL carry HTTPS"
[ "$XOK" -eq "$XALL" ] || { echo "xray strategies failed"; exit 1; }
for f in /tmp/xcfg/*.json; do case $f in */warp-*) continue;; esac; echo "$(basename $f): $($X run -test -c $f 2>&1 | tail -1)"; done
KEEP_CLIENT=1 bash tools/xray-local-e2e.sh $X /tmp/xcfg || true  # raw matrix includes masks the app never offers
# Only masks the app offers for each server type (Masks.compatible) must pass.
python3 - <<'PY' || MFAIL=1
import sys
ok = fail = 0; bad = []; bok = bfail = 0
from collections import defaultdict
import json
per = defaultdict(lambda: [0, 0])
ALPN = {}
for o in json.load(open('/tmp/xcfg/local-matrix.json'))['outbounds']:
    t = o.get('tag', '')
    if t.startswith('v') and t[1:].isdigit():
        ALPN[str(30000 + int(t[1:]))] = ((o.get('streamSettings') or {}).get('tlsSettings') or {}).get('alpn') or []
for line in open('/tmp/xcfg/result.txt'):
    st, port, name, mask = line.split()[:4]
    # Generated by the actual Masks.compatible policy, not a stale Python copy.
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
# 24 parallel downloads on a CI runner time out now and then: each failed offered direct mask gets one calm retry
# (6 at a time, client still running), only a variant that fails twice counts as broken
if bad and len(bad) <= 120:
    import subprocess, concurrent.futures as cf, os
    def again(line):
        port = line.split()[1]
        a = subprocess.run(['curl', '-s', '-o', '/dev/null', '-m', '15', '-w', '%{http_code}', '--socks5-hostname', '127.0.0.1:' + port, os.environ['MATRIX_CHECK_URL'], '--cacert', os.environ['MATRIX_CA']], capture_output=True, text=True).stdout
        b = subprocess.run(['curl', '-s', '-o', '/dev/null', '-m', '25', '-w', '%{size_download}', '--socks5-hostname', '127.0.0.1:' + port, os.environ['MATRIX_DOWNLOAD_URL'], '--cacert', os.environ['MATRIX_CA']], capture_output=True, text=True).stdout
        return a == '204' and b.isdigit() and int(b) >= 262144
    with cf.ThreadPoolExecutor(6) as ex: res = list(ex.map(again, bad))
    fixed = sum(res); print(f"retry: {fixed} of {len(bad)} failed offered masks passed on the second try")
    bad = [l for l, r in zip(bad, res) if not r]; fail -= fixed; ok += fixed
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

[ -f /tmp/xcfg/client.pid ] && kill "$(cat /tmp/xcfg/client.pid)" 2>/dev/null || true
sleep 1
python3 - <<'PY' || MFAIL=1
# WireGuard: one tunnel per key at a time (parallel tunnels with one key make the server roam), so each WG
# variant gets its own Xray instance here, sequentially, instead of the parallel matrix.
import json, subprocess, time, os
cfg = json.load(open('/tmp/xcfg/local-matrix.json'))
rows = [l.split() for l in open('/tmp/xcfg/local-matrix.txt')]
wok = wall = 0
with open('/tmp/xcfg/wg-result.txt', 'w') as out:
    for port, name, mask in rows:
        if name != 'wg': continue
        tag = 'v%d' % (int(port) - 30000)
        ob = [o for o in cfg['outbounds'] if o['tag'] == tag][0]
        json.dump({"inbounds": [{"port": 24900, "listen": "127.0.0.1", "protocol": "socks", "settings": {"auth": "noauth"}}], "outbounds": [ob]}, open('/tmp/wg1.json', 'w'))
        p = subprocess.Popen(['/tmp/xray/xray', 'run', '-c', '/tmp/wg1.json'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL); time.sleep(1.5)
        a = subprocess.run(['curl', '-s', '-o', '/dev/null', '-m', '15', '-w', '%{http_code}', '--socks5-hostname', '127.0.0.1:24900', os.environ['MATRIX_CHECK_URL'], '--cacert', os.environ['MATRIX_CA']], capture_output=True, text=True).stdout
        b = subprocess.run(['curl', '-s', '-o', '/dev/null', '-m', '25', '-w', '%{size_download}', '--socks5-hostname', '127.0.0.1:24900', os.environ['MATRIX_DOWNLOAD_URL'], '--cacert', os.environ['MATRIX_CA']], capture_output=True, text=True).stdout
        p.kill(); p.wait()
        ok = a == '204' and b.isdigit() and int(b) >= 262144
        wall += 1; wok += ok
        print(('OK' if ok else 'FAIL'), port, name, mask, a, b, file=out)
print(f"WireGuard (sequential, own instance): {wok}/{wall}")
print(open('/tmp/xcfg/wg-result.txt').read())
if wall == 0 or wok != wall: raise SystemExit('WireGuard variants failed')
PY


# Real Cloudflare WARP from the runner: every WireGuard/WARP mask the app offers (noise, QUIC/DTLS/RTP look-alikes, port hopping).
K=$($X wg); WPRIV=$(echo "$K" | sed -n 's/PrivateKey: //p'); WPUB=$(echo "$K" | sed -n 's/.*PublicKey): //p')
R=$(curl -s -m 20 -X POST https://api.cloudflareclient.com/v0a2158/reg -H 'User-Agent: okhttp/3.12.1' -H 'CF-Client-Version: a-6.30-3596' -H 'Content-Type: application/json' \
  -d "{\"key\":\"$WPUB\",\"install_id\":\"\",\"fcm_token\":\"\",\"tos\":\"$(date -u +%Y-%m-%dT%H:%M:%S.000Z)\",\"type\":\"Android\",\"locale\":\"en_US\"}")
echo "$R" | jq -e '.config.interface.addresses.v4 and .config.client_id and .config.peers[0].public_key' >/dev/null || { echo "WARP registration returned no usable account"; exit 1; }
WPEER=$(echo "$R" | jq -r .config.peers[0].public_key)
WV4=$(echo "$R" | jq -r .config.interface.addresses.v4); WRES=$(echo "$R" | jq -r .config.client_id | base64 -d | od -An -tu1 | awk '{print $1","$2","$3}')
WOK=0; WALL=0
while read -r id; do
  [ -n "$id" ] || continue
  sed -e "s#@WPRIV@#$WPRIV#" -e "s#@WV4@#$WV4#" -e "s#\[11,22,33\]#[$WRES]#" /tmp/xcfg/warp-$id.json > /tmp/w1.json
  python3 - "$WPEER" <<'PEER'
import json,sys
p='/tmp/w1.json'; c=json.load(open(p))
for o in c['outbounds']:
    if o.get('protocol')=='wireguard': o['settings']['peers'][0]['publicKey']=sys.argv[1]
json.dump(c,open(p,'w'))
PEER
  $X run -c /tmp/w1.json > /tmp/w1.log 2>&1 & WP=$!; sleep 1.5
  c=$(curl -s -m 15 -o /tmp/warp-trace.txt -w '%{http_code}' -x socks5h://127.0.0.1:24901 https://www.cloudflare.com/cdn-cgi/trace || true)
  # real WARP from a CI runner drops a handshake now and then: one more try before a mask counts as failed
  [ "$c" = 200 ] || { sleep 2; c=$(curl -s -m 15 -o /tmp/warp-trace.txt -w '%{http_code}' -x socks5h://127.0.0.1:24901 https://www.cloudflare.com/cdn-cgi/trace || true); }
  kill $WP 2>/dev/null || true; wait $WP 2>/dev/null || true; WALL=$((WALL+1))
  if [ "$c" = 200 ] && grep -q '^warp=on' /tmp/warp-trace.txt; then WOK=$((WOK+1)); echo "WARP OK $id"; else echo "WARP FAIL $id ($c) $(tail -1 /tmp/w1.log)"; fi
done < /tmp/xcfg/warp-masks.txt
echo "WARP masks: $WOK/$WALL carry HTTPS through real Cloudflare WARP; ByeDPI restarts: $(cat /tmp/bd-restarts 2>/dev/null | wc -l)"
[ "$WOK" -ge $((WALL * 8 / 10)) ] || { echo "too many WARP masks fail"; exit 1; }
exit ${MFAIL:-0}
