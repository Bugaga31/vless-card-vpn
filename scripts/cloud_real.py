#!/usr/bin/env python3
"""Cloud real check: the servers that answered the ping get a real HTTPS request through Xray-core from GitHub.

Reads out/alive_ru.txt and out/alive.txt (written by cloud_servers.py), checks the first REAL_RU / REAL_ALL of them
through a local Xray (one SOCKS inbound per server), and rewrites both lists with the servers that really carried
HTTPS first (fastest first), then the rest. Also writes out/works.txt and out/works_ru.txt (only working ones).
GitHub is abroad: this proves the server works at all, not that it passes Russian DPI — that is still the phone's job."""
import base64, concurrent.futures as cf, hashlib, json, os, socket, ssl, subprocess, sys, tempfile, time, urllib.parse

XRAY = os.environ.get("XRAY", "/tmp/xray/xray")
REAL_RU, REAL_ALL = int(os.environ.get("REAL_RU", "1500")), int(os.environ.get("REAL_ALL", "1000"))
BATCH, PAR, BASE = 250, 96, 21000
URL = "https://www.gstatic.com/generate_204"


def b64(s):
    s = s.strip().replace("-", "+").replace("_", "/")
    return base64.b64decode(s + "=" * (-len(s) % 4)).decode("utf-8", "ignore")


def q1(q, k, d=""):
    return (q.get(k) or [d])[0]


def cert_pin(host, port, sni):
    """SHA-256 of the server's certificate: Xray 26 has no allowInsecure, free servers are often self-signed."""
    try:
        c = ssl.create_default_context(); c.check_hostname = False; c.verify_mode = ssl.CERT_NONE
        with socket.create_connection((host, port), 4) as s, c.wrap_socket(s, server_hostname=sni or None) as t:
            return hashlib.sha256(t.getpeercert(binary_form=True)).hexdigest()
    except Exception:
        return ""


def stream(net, sec, q, host_hdr, path, sni, fp, alpn, pin):
    st = {"network": {"h2": "xhttp", "http": "xhttp"}.get(net, net or "raw")}
    if st["network"] == "tcp": st["network"] = "raw"
    n = st["network"]
    if n == "ws": st["wsSettings"] = {"path": path or "/", "host": host_hdr}
    elif n == "httpupgrade": st["httpupgradeSettings"] = {"path": path or "/", "host": host_hdr}
    elif n == "grpc": st["grpcSettings"] = {"serviceName": q1(q, "serviceName", path), "multiMode": q1(q, "mode") == "multi"}
    elif n == "xhttp": st["xhttpSettings"] = {"path": path or "/", "host": host_hdr, "mode": q1(q, "mode", "auto") or "auto"}
    elif n == "raw" and q1(q, "headerType") == "http":
        st["rawSettings"] = {"header": {"type": "http", "request": {"path": [path or "/"], "headers": {"Host": [host_hdr]} if host_hdr else {}}}}
    elif n not in ("raw",): return None  # kcp/quic/etc.: not worth it here
    if sec == "tls":
        t = {"serverName": sni, "fingerprint": fp or "chrome"}
        if alpn: t["alpn"] = alpn.split(",")
        if pin: t["pinnedPeerCertSha256"] = pin
        st["security"], st["tlsSettings"] = "tls", t
    elif sec == "reality":
        pbk = q1(q, "pbk")
        if not pbk: return None
        st["security"], st["realitySettings"] = "reality", {"serverName": sni, "fingerprint": fp if fp in ("chrome", "firefox", "safari") else "chrome",
                                                            "publicKey": pbk, "shortId": q1(q, "sid"), "spiderX": q1(q, "spx")}
    return st


def outbound(link):
    """Xray outbound for a vless/vmess/trojan/ss link, or None. Returns (outbound, host, port, sni, tls)."""
    try:
        if link.startswith("vmess://"):
            o = json.loads(b64(link[8:].split("#")[0]))
            host, port = o["add"].strip("[]"), int(o["port"])
            sec = "tls" if o.get("tls") == "tls" else ""
            sni = o.get("sni") or o.get("host") or host
            q = {"headerType": [o.get("type", "")], "serviceName": [o.get("path", "")]}
            return ("vmess", {"vnext": [{"address": host, "port": port, "users": [{"id": o["id"], "security": o.get("scy") or "auto"}]}]},
                    o.get("net", "tcp"), sec, q, o.get("host", ""), o.get("path", ""), sni, o.get("fp", ""), o.get("alpn", ""), host, port)
        scheme, body = link.split("://", 1)
        body = body.split("#")[0]
        if scheme == "ss":
            if "@" not in body.split("?")[0]: body = b64(body.split("?")[0])
            u = urllib.parse.urlsplit("x://" + body)
            user = urllib.parse.unquote(u.username or "")
            if ":" not in user: user = b64(user)
            method, pw = (user + ":" + urllib.parse.unquote(u.password or "")).split(":")[:2] if u.password else user.split(":", 1)
            return ("shadowsocks", {"servers": [{"address": u.hostname, "port": u.port, "method": method, "password": pw}]},
                    "tcp", "", {}, "", "", "", "", "", u.hostname, u.port)
        u = urllib.parse.urlsplit("x://" + body)
        q = urllib.parse.parse_qs(u.query)
        host, port = u.hostname, u.port
        sec = q1(q, "security", "tls" if scheme == "trojan" else "")
        sni = q1(q, "sni") or q1(q, "host") or host
        user = urllib.parse.unquote(u.username or "")
        if scheme == "vless":
            if q1(q, "encryption", "none") not in ("none", ""): return None
            usr = {"id": user, "encryption": "none"}
            if q1(q, "flow"): usr["flow"] = q1(q, "flow")
            s = {"vnext": [{"address": host, "port": port, "users": [usr]}]}
        elif scheme == "trojan":
            s = {"servers": [{"address": host, "port": port, "password": user}]}
        else:
            return None
        return (scheme, s, q1(q, "type", "tcp"), sec, q, q1(q, "host"), urllib.parse.unquote(q1(q, "path")), sni, q1(q, "fp"), q1(q, "alpn"), host, port)
    except Exception:
        return None


def build(links):
    """[(link, outbound-json)] for the links Xray can express; TLS servers get their certificate pinned."""
    parsed = [(l, outbound(l)) for l in links]
    parsed = [(l, p) for l, p in parsed if p and p[10] and p[11]]
    with cf.ThreadPoolExecutor(128) as ex:
        pins = list(ex.map(lambda lp: cert_pin(lp[1][10], lp[1][11], lp[1][7]) if lp[1][3] == "tls" else "", parsed))
    out = []
    for (l, p), pin in zip(parsed, pins):
        proto, settings, net, sec, q, hh, path, sni, fp, alpn, _, _ = p
        st = stream(net, sec, q, hh, path, sni, fp, alpn, pin)
        if st is None: continue
        out.append((l, {"protocol": proto, "settings": settings, "streamSettings": st}))
    return out


def config(items):
    ins, outs, rules = [], [], []
    for i, (_, ob) in enumerate(items):
        ins.append({"tag": f"i{i}", "listen": "127.0.0.1", "port": BASE + i, "protocol": "socks", "settings": {"auth": "noauth"}})
        outs.append(dict(ob, tag=f"o{i}"))
        rules.append({"inboundTag": [f"i{i}"], "outboundTag": f"o{i}"})
    return {"log": {"loglevel": "none"}, "inbounds": ins, "outbounds": outs, "routing": {"rules": rules}}


def valid(items, d):
    p = os.path.join(d, "t.json"); json.dump(config(items), open(p, "w"))
    return subprocess.run([XRAY, "run", "-test", "-c", p], capture_output=True).returncode == 0


def accepted(items, d):
    """Drops the outbounds Xray refuses (bisection), so one broken link cannot sink a batch."""
    if not items or valid(items, d): return items
    if len(items) == 1: return []
    h = len(items) // 2
    return accepted(items[:h], d) + accepted(items[h:], d)


def probe(port):
    r = subprocess.run(["curl", "-s", "-o", "/dev/null", "-m", "10", "-w", "%{http_code} %{time_total}", "--socks5-hostname", f"127.0.0.1:{port}", URL],
                       capture_output=True, text=True).stdout.split()
    return int(float(r[1]) * 1000) or 1 if len(r) == 2 and r[0] == "204" else 0


def check(links):
    """link -> real ms (0 = failed)."""
    res = {}
    items = build(links)
    print(f"real check: {len(links)} links, {len(items)} expressible", flush=True)
    with tempfile.TemporaryDirectory() as d:
        for k in range(0, len(items), BATCH):
            chunk = accepted(items[k:k + BATCH], d)
            if not chunk: continue
            p = os.path.join(d, "c.json"); json.dump(config(chunk), open(p, "w"))
            x = subprocess.Popen([XRAY, "run", "-c", p], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            time.sleep(1.5)
            try:
                with cf.ThreadPoolExecutor(PAR) as ex:
                    for (l, _), ms in zip(chunk, ex.map(probe, [BASE + i for i in range(len(chunk))])): res[l] = ms
            finally:
                x.kill(); x.wait()
            print(f"  batch {k // BATCH + 1}: {sum(1 for l, _ in chunk if res.get(l))}/{len(chunk)} work", flush=True)
    return res


def main():
    lists = {}
    for name in ("alive_ru.txt", "alive.txt"):
        p = "out/" + name
        lists[name] = open(p, encoding="utf-8").read().splitlines() if os.path.exists(p) else []
    head = {n: [l for l in ls if not l.startswith("#")] for n, ls in lists.items()}
    todo = list(dict.fromkeys(head["alive_ru.txt"][:REAL_RU] + head["alive.txt"][:REAL_ALL]))
    t = time.time()
    res = check(todo)
    stamp = time.strftime("%Y-%m-%d %H:%M UTC", time.gmtime())
    total = 0
    for name, ls in lists.items():
        comments = [l for l in ls if l.startswith("#")]
        body = head[name]
        works = sorted((l for l in body if res.get(l)), key=lambda l: res[l])
        rest = [l for l in body if not res.get(l)]
        tested_bad = [l for l in rest if l in res]  # answered the ping but carried nothing: to the very end
        rest = [l for l in rest if l not in res] + tested_bad
        with open("out/" + name, "w", encoding="utf-8") as f:
            f.write(f"# VLESS Card cloud {stamp}: {len(works)} carried real HTTPS through Xray from GitHub (first, fastest first), then {len(rest)} that only answered the ping\n")
            for l in works + rest: f.write(l + "\n")
        with open("out/" + name.replace("alive", "works"), "w", encoding="utf-8") as f:
            f.write(f"# VLESS Card cloud {stamp}: {len(works)} servers carried real HTTPS through Xray-core from GitHub, fastest first\n")
            for l in works: f.write(l + "\n")
        total = max(total, len(works))
    ok = sum(1 for v in res.values() if v)
    try:
        st = json.load(open("out/stats.json"))
    except Exception:
        st = {}
    st.update({"real_checked": len(res), "real_ok": ok, "real_s": int(time.time() - t)})
    json.dump(st, open("out/stats.json", "w"))
    print(f"real: {ok}/{len(res)} carried HTTPS in {int(time.time() - t)} s")


if __name__ == "__main__":
    main()
