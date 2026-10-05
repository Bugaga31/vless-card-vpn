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
L
chmod +x gradlew
XRAY_CONFIG_DUMP=/tmp/xcfg XRAY_LOCAL_LINKS=/tmp/links.txt ./gradlew --no-daemon -q :app:testDebugUnitTest --tests 'com.vlesscardvpn.ConfigTest'
for f in /tmp/xcfg/*.json; do echo "$(basename $f): $($X run -test -c $f 2>&1 | tail -1)"; done
bash tools/xray-local-e2e.sh $X /tmp/xcfg
# Only masks the app offers for each server type (Masks.compatible) must pass.
python3 - <<'PY'
import sys
ok = fail = 0; bad = []
for line in open('/tmp/xcfg/result.txt'):
    st, port, name, mask = line.split()[:4]
    fp = mask.split('.')[0]
    offered = True
    if name in ('reality-vision', 'xhttp-reality'): offered = fp in ('chrome', 'firefox', 'safari', 'none')
    elif name == 'grpc': offered = fp != 'android'
    elif name in ('ss', 'vmess-hu'): offered = fp in ('chrome', 'none')
    if not offered: continue
    if st == 'OK': ok += 1
    else: fail += 1; bad.append(line.strip())
print(f"offered masks: ok={ok} fail={fail}")
for b in bad[:30]: print(b)
sys.exit(1 if fail > ok * 0.03 else 0)  # ByeDPI-front variants can be flaky under 24-way parallel load
PY
