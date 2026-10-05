#!/usr/bin/env python3
"""Writes app state.json files for the emulator e2e cases (servers on the host = 10.0.2.2)."""
import json, sys
U = "a00975c5-597e-4c22-a7a7-a84ce5e0691e"
PBK = sys.argv[2]
H = "10.0.2.2"
reality = {"name": "e2e-reality", "protocol": "vless", "address": H, "port": 8443, "secret": U, "flow": "xtls-rprx-vision",
           "security": "reality", "sni": "www.microsoft.com", "fp": "chrome", "pbk": PBK, "sid": "6ba85179e30d4fc2"}
ws = {"name": "e2e-ws-tls", "protocol": "vless", "address": H, "port": 8444, "secret": U, "network": "ws", "path": "/ws",
      "security": "tls", "sni": "test.local", "insecure": True}
xhttp = {"name": "e2e-xhttp", "protocol": "vless", "address": H, "port": 8447, "secret": U, "network": "xhttp", "path": "/x",
         "mode": "auto", "security": "reality", "sni": "www.microsoft.com", "pbk": PBK, "sid": "ab"}
dead = {"name": "e2e-dead", "protocol": "vless", "address": H, "port": 8999, "secret": U, "security": "reality",
        "sni": "www.microsoft.com", "pbk": PBK, "sid": "ab"}

def write(path, servers, selected, masks, mode="SERVERS", balance="LEAST_PING", **extra):
    settings = {"mode": mode, "balance": balance, "ruDirect": False, "byeDpiArgs": "-o1 -At,r,s -d1", "byeDpiSni": "ya.ru"}
    settings.update(extra)
    st = {"servers": servers, "states": {}, "settings": settings,
          "e2e_select": [s["name"] for s in servers if s["name"] in selected], "e2e_masks": masks}
    json.dump(st, open(path, "w"))

out = sys.argv[1]
write(f"{out}/single.json", [reality], ["e2e-reality"], {})
write(f"{out}/multi.json", [reality, ws, xhttp, dead], ["e2e-reality", "e2e-ws-tls", "e2e-xhttp", "e2e-dead"],
      {"e2e-reality": "chrome.h4", "e2e-ws-tls": "firefox.p2.b", "e2e-xhttp": "safari.h1"}, balance="ROUND_ROBIN")
write(f"{out}/byedpi.json", [reality], [], {}, mode="BYEDPI")
write(f"{out}/test.json", [reality, ws, xhttp, dead], [], {})
write(f"{out}/tpws.json", [reality], [], {}, mode="BYEDPI", dpiStrategy="TPWS#SPLIT_DISORDER")
# Own VLESS Card cascade (ByeDPI) and zapret host shredder in front of two servers at once.
write(f"{out}/ownmask.json", [reality, ws], ["e2e-reality", "e2e-ws-tls"],
      {"e2e-reality": "chrome.d:BYEDPI#VCARD_CASCADE", "e2e-ws-tls": "firefox.d:TPWS#VCARD_SHRED"}, balance="ROUND_ROBIN")
