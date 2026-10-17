#!/usr/bin/env python3
"""Cloud real check: the servers that answered the ping get a real HTTPS request through Xray-core from GitHub.

Reads out/alive_ru.txt and out/alive.txt (written by cloud_servers.py), checks the first REAL_RU / REAL_ALL of them
through a local Xray (one SOCKS inbound per server), and rewrites both lists with the servers that really carried
HTTPS first (fastest first), then the rest. Also writes out/works.txt and out/works_ru.txt (only working ones).
GitHub is abroad: this proves the server works at all, not that it passes Russian DPI — that is still the phone's job."""
import base64, concurrent.futures as cf, hashlib, json, os, socket, ssl, subprocess, sys, tempfile, time, urllib.parse

XRAY = os.environ.get("XRAY", "/tmp/xray/xray")
REAL_RU, REAL_ALL = int(os.environ.get("REAL_RU", "1500")), int(os.environ.get("REAL_ALL", "1000"))
BATCH, PAR, BASE = 400, 192, 21000
HIST_URL = "https://raw.githubusercontent.com/Bugaga31/vless-card-vpn/servers/history.json"
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
    r = subprocess.run(["curl", "-s", "-o", "/dev/null", "-m", "7", "-w", "%{http_code} %{time_total}", "--socks5-hostname", f"127.0.0.1:{port}", URL],
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
            time.sleep(1.0)
            try:
                with cf.ThreadPoolExecutor(PAR) as ex:
                    for (l, _), ms in zip(chunk, ex.map(probe, [BASE + i for i in range(len(chunk))])): res[l] = ms
            finally:
                x.kill(); x.wait()
            print(f"  batch {k // BATCH + 1}: {sum(1 for l, _ in chunk if res.get(l))}/{len(chunk)} work", flush=True)
    return res


SPEED_URL = "https://speed.cloudflare.com/__down?bytes=4000000"
# Isolated downloads: parallel speed tests divide runner bandwidth and distort the ranking.
SPEED_TOP, SPEED_PAR = int(os.environ.get("SPEED_TOP", "24")), 1
SPEED_TTL = 3600


def fetch_speed(port):
    """Real download speed through one server, kbit/s (0 = failed or under 300 KB in 8 s)."""
    r = subprocess.run(["curl", "-s", "-o", "/dev/null", "-m", "8", "-w", "%{size_download} %{speed_download}", "--socks5-hostname", f"127.0.0.1:{port}", SPEED_URL],
                       capture_output=True, text=True).stdout.split()
    try:
        return int(float(r[1]) * 8 / 1000) if len(r) == 2 and float(r[0]) >= 300_000 else 0
    except ValueError:
        return 0


def speeds(links):
    """link -> kbit/s for the given working links (few at a time, so they do not share the runner's bandwidth too much)."""
    res = {}
    items = build(links)
    if not items: return res
    with tempfile.TemporaryDirectory() as d:
        items = accepted(items, d)
        if not items: return res
        p = os.path.join(d, "s.json"); json.dump(config(items), open(p, "w"))
        x = subprocess.Popen([XRAY, "run", "-c", p], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        time.sleep(1.0)
        try:
            with cf.ThreadPoolExecutor(SPEED_PAR) as ex:
                for (l, _), kb in zip(items, ex.map(fetch_speed, [BASE + i for i in range(len(items))])): res[l] = kb
        finally:
            x.kill(); x.wait()
    return res


def load_history():
    """link -> {"ok": runs it worked, "n": runs checked, "ms": smoothed ms, "t": last seen}: the cloud's memory."""
    try:
        import urllib.request
        with urllib.request.urlopen(HIST_URL, timeout=20) as r: return json.loads(r.read())
    except Exception:
        return {}


def learn(hist, res, now):
    """Updates the memory with this run. Old evidence fades (×0.9 per run), so a server that died drops quickly."""
    for l, ms in res.items():
        h = hist.get(l) or {"ok": 0.0, "n": 0.0, "ms": 0, "t": now}
        h["ok"] = round(h["ok"] * 0.9 + (1 if ms else 0), 3); h["n"] = round(h["n"] * 0.9 + 1, 3)
        if ms: h["ms"] = ms if not h["ms"] else int(h["ms"] * 0.6 + ms * 0.4)
        samples = [s for s in h.get("samples", []) if isinstance(s, list) and len(s) == 2 and now - 86400 <= s[0] <= now]
        h["samples"] = (samples + [[now, bool(ms)]])[-288:]
        h["t"] = now
        hist[l] = h
    for l in [l for l, h in hist.items() if now - h.get("t", now) > 2 * 86400 or (h["n"] > 3 and h["ok"] / h["n"] < 0.05)]:
        del hist[l]
    return hist


def measured_kbps(h, now):
    """Only recent isolated samples; a later failed real check revokes the cloud speed hint."""
    if not isinstance(h,dict): return 0
    at = h.get("kb_t",0); samples = h.get("samples",[])
    if not isinstance(samples,list) or not samples: return 0
    last = samples[-1]
    if not isinstance(last,list) or len(last) != 2 or last[1] is not True: return 0
    if (h.get("kb_isolated") is not True or type(at) not in (int,float) or
        type(last[0]) not in (int,float) or not now-SPEED_TTL <= at <= now or
        not now-SPEED_TTL <= last[0] <= now): return 0
    try: return max(0,int(h.get("kb",0)))
    except (ValueError,TypeError,OverflowError): return 0


def learn_speed(hist, sp, now=None):
    now = int(time.time()) if now is None else now
    for link, kb in sp.items():
        if link in hist:
            hist[link].update(kb=max(0, kb), kb_t=now, kb_isolated=True)


def speed_trials(res, hist, now, limit=SPEED_TOP):
    """Exploit fast measured routes, but reserve half the slots for unmeasured/old routes."""
    if limit <= 0: return []
    working = [link for link, ms in res.items() if ms > 0]
    ranked = sorted(working, key=lambda link: (-measured_kbps(hist[link], now), score(hist[link])))
    keep = ranked[:max(1, limit // 2)]
    rest = sorted((link for link in working if link not in keep),
                  key=lambda link: (hist[link].get("kb_t", 0), res[link]))
    # Different hosts before multiple configurations on the same endpoint.
    selected = []; hosts = set()
    for link in keep + rest + ranked:
        host = key(link)
        if link not in selected and host not in hosts:
            selected.append(link); hosts.add(host)
        if len(selected) >= limit: return selected
    return (selected + [link for link in keep + rest if link not in selected])[:limit]


def key(link):
    """Same as the app's Report.serverKey: first 4 bytes of sha256("host:port"), hex."""
    p = outbound(link)
    if not p or not p[10] or not p[11]: return ""
    return hashlib.sha256(f"{str(p[10]).lower()}:{p[11]}".encode()).hexdigest()[:8]


def write_speed(hist, now=None):
    """Fresh configuration samples only; endpoint keys remain candidate hints, never local proof."""
    now = int(time.time()) if now is None else now
    fast = sorted(((measured_kbps(h,now), link) for link,h in hist.items()
                   if measured_kbps(h,now) > 0 and (h["ok"]+.5)/(h["n"]+1) > .5), reverse=True)
    out = {}; measured = {}
    for kb, link in fast:
        k = key(link)
        if k and k not in out:
            out[k] = kb; measured[k] = hist[link]["kb_t"]
        if len(out) >= 300: break
    with open("out/speed.json", "w") as target:
        json.dump({"stamp":now,"isolated":True,"kbps":out,"measured_at":measured},target,separators=(",",":"))


def score(h):
    """Lower = better: stable servers first (Bayesian success rate), then response time and real download speed
    (up to −1500 for 30+ Мбит/с, so a fast server beats a slightly nearer slow one)."""
    rate = (h["ok"] + 0.5) / (h["n"] + 1)
    return (1 - rate) * 3000 + (h["ms"] or 3000) - min(h.get("kb", 0), 30000) / 20


def main():
    lists = {}
    for name in ("alive_ru.txt", "alive.txt"):
        p = "out/" + name
        lists[name] = open(p, encoding="utf-8").read().splitlines() if os.path.exists(p) else []
    head = {n: [l for l in ls if not l.startswith("#")] for n, ls in lists.items()}
    now = int(time.time())
    hist = load_history()
    alive = set(head["alive_ru.txt"]) | set(head["alive.txt"])
    # servers that worked before are always re-checked (if they still answer the ping), then the fastest pings
    known = sorted((l for l, h in hist.items() if h["ok"] > 0 and l in alive), key=lambda l: score(hist[l]))
    todo = list(dict.fromkeys(known[:1500] + head["alive_ru.txt"][:REAL_RU] + head["alive.txt"][:REAL_ALL]))
    t = time.time()
    res = check(todo)
    hist = learn(hist, res, now)
    top = speed_trials(res,hist,now)
    ts = time.time()
    sp = speeds(top)
    learn_speed(hist, sp, int(time.time()))
    fast = sorted((kb for kb in sp.values() if kb), reverse=True)
    print(f"speed: {len(fast)}/{len(top)} downloaded in {int(time.time() - ts)} s, best {fast[:5]} kbit/s", flush=True)
    json.dump(hist, open("out/history.json", "w"), separators=(",", ":"))
    write_speed(hist)
    stamp = time.strftime("%Y-%m-%d %H:%M UTC", time.gmtime())
    for name, ls in lists.items():
        body = head[name]
        works = sorted((l for l in body if res.get(l)), key=lambda l: (-measured_kbps(hist[l],int(time.time())),score(hist[l])))
        rest = [l for l in body if not res.get(l)]
        rest = [l for l in rest if l not in res] + [l for l in rest if l in res]  # pinged but carried nothing: last
        with open("out/" + name, "w", encoding="utf-8") as f:
            f.write(f"# VLESS Card cloud {stamp}: {len(works)} carried real HTTPS through Xray from GitHub (first, most stable and fastest first), then {len(rest)} that only answered the ping\n")
            for l in works + rest: f.write(l + "\n")
        with open("out/" + name.replace("alive", "works"), "w", encoding="utf-8") as f:
            f.write(f"# VLESS Card cloud {stamp}: {len(works)} servers carried real HTTPS through Xray-core from GitHub, most stable first\n")
            for l in works: f.write(l + "\n")
    ok = sum(1 for v in res.values() if v)
    stable = sum(1 for h in hist.values() if h["n"] >= 3 and h["ok"] / h["n"] > 0.8)
    try:
        st = json.load(open("out/stats.json"))
    except Exception:
        st = {}
    st.update({"real_checked": len(res), "real_ok": ok, "real_s": int(time.time() - t), "remembered": len(hist), "stable": stable,
               "speed_checked": len(sp), "speed_ok": len(fast), "speed_best_kbps": fast[0] if fast else 0,
               "speed_median_kbps": fast[len(fast) // 2] if fast else 0})
    json.dump(st, open("out/stats.json", "w"))
    print(f"real: {ok}/{len(res)} carried HTTPS in {int(time.time() - t)} s; memory {len(hist)}, stable {stable}")


if __name__ == "__main__":
    main()
