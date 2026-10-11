#!/usr/bin/env python3
"""«Облачная проверка серверов»: runs on GitHub Actions every 6 h.

Downloads all public subscriptions from the app's catalog (+ a few huge world collections), removes duplicates,
TCP-pings every server from GitHub's machines and publishes the ones that answer (fastest first) as plain
subscriptions the app reads: alive.txt (everything) and alive_ru.txt (from the Russia-oriented sources).
GitHub's servers are abroad, so this removes dead servers — whether a server passes Russian DPI is still decided
on the phone (masks, real checks, the mini model)."""
import asyncio, base64, json, re, ssl, sys, time, urllib.parse, urllib.request

EXTRA = [  # big world collections: too heavy for a phone, fine for a CI runner
    "10ium/V2ray-Config/main/Splitted-By-Protocol/vless.txt",
    "mheidari98/.proxy/main/all",
    "barry-far/V2ray-Config/main/All_Configs_Sub.txt",
    "10ium/V2ray-Config/main/Splitted-By-Protocol/trojan.txt",
    "10ium/V2ray-Config/main/Splitted-By-Protocol/ss.txt",
    "SoliSpirit/v2ray-configs/main/Protocols/trojan.txt",
]
SCHEMES = ("vless://", "vmess://", "trojan://", "ss://")
LIMIT_ALL, LIMIT_RU = 6000, 3000


def catalog():
    src = open("app/src/main/java/com/vlesscardvpn/model/Subs.kt", encoding="utf-8").read()
    ig = re.search(r'IG = "([^"]+)"', src).group(1)
    out = []
    for m in re.finditer(r'SubSource\(gh\("([^"]+)"\),\s*"[^"]*",\s*(\w+)', src):
        out.append((m.group(1).replace("$IG", ig), m.group(2) in ("RU", "WL")))
    return out


def fetch(path):
    url = path if path.startswith("http") else "https://raw.githubusercontent.com/" + path
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "vless-card-cloud"}), timeout=60) as r:
            text = r.read(60_000_000).decode("utf-8", "ignore")
    except Exception as e:
        print("skip", path, e, file=sys.stderr); return []
    if not any(s in text for s in SCHEMES):
        try: text = base64.b64decode(text.strip() + "===").decode("utf-8", "ignore")
        except Exception: pass
    return [l.strip() for l in text.splitlines() if l.strip().startswith(SCHEMES)]


def b64(s):
    s = s.strip().replace("-", "+").replace("_", "/")
    return base64.b64decode(s + "=" * (-len(s) % 4)).decode("utf-8", "ignore")


def endpoint(link):
    """(host, port, identity, sni-or-None) or None. Identity = what makes two links the same server.
    sni is set for TLS / REALITY servers: those also get a TLS handshake, not just a TCP ping."""
    try:
        if link.startswith("vmess://"):
            o = json.loads(b64(link[8:].split("#")[0]))
            tls = (o.get("sni") or o.get("host") or o["add"]) if o.get("tls") == "tls" else None
            return o["add"].strip("[]"), int(o["port"]), "vmess|%s|%s|%s|%s" % (o["add"], o["port"], o.get("id"), o.get("net")), tls
        body = link.split("://", 1)[1].split("#")[0]
        if link.startswith("ss://") and "@" not in body:
            body = b64(body.split("?")[0])
        u = urllib.parse.urlsplit("x://" + body)
        if not u.hostname or not u.port: return None
        q = urllib.parse.parse_qs(u.query)
        ident = "|".join([link.split("://")[0], u.hostname.lower(), str(u.port), u.username or "", q.get("type", [""])[0],
                          q.get("security", [""])[0], q.get("sni", [""])[0], q.get("pbk", [""])[0], q.get("path", [""])[0]])
        sec = q.get("security", [""])[0]
        sni = q.get("sni", [""])[0] or q.get("host", [""])[0] or (u.hostname if not re.match(r"^[0-9.:]+$", u.hostname) else "")
        tls = (sni or None) if sec in ("tls", "reality") or link.startswith("trojan://") else None
        return u.hostname, u.port, ident, tls
    except Exception:
        return None


TLS = ssl.create_default_context(); TLS.check_hostname = False; TLS.verify_mode = ssl.CERT_NONE


async def ping(host, port, sni, sem):
    """ms of TCP connect (+ TLS handshake with the server's SNI for TLS/REALITY), 0 = dead.
    A REALITY server answers a plain handshake with its cover site's certificate — so it passes when it's alive."""
    async with sem:
        t = time.monotonic()
        try:
            if sni:
                _, w = await asyncio.wait_for(asyncio.open_connection(host, port, ssl=TLS, server_hostname=sni), 5)
            else:
                _, w = await asyncio.wait_for(asyncio.open_connection(host, port), 3)
            w.close()
            return int((time.monotonic() - t) * 1000) or 1
        except Exception:
            return 0


async def main():
    links = {}  # ident -> (link, host, port, ru)
    for path, ru in catalog() + [(p, False) for p in EXTRA]:
        got = fetch(path)
        print(f"{len(got):6d}  {path}")
        for l in got:
            e = endpoint(l)
            if not e or e[1] <= 0 or e[1] > 65535: continue
            old = links.get(e[2])
            if old is None or (ru and not old[3]): links[e[2]] = (l, e[0], e[1], ru or (old[3] if old else False), e[3])
    print("unique:", len(links))
    sem = asyncio.Semaphore(800)
    items = list(links.values())
    ms = await asyncio.gather(*(ping(h, p, sni, sem) for _, h, p, _, sni in items))
    alive = sorted((m, it) for m, it in zip(ms, items) if m > 0)
    print("alive:", len(alive))
    stamp = time.strftime("%Y-%m-%d %H:%M UTC", time.gmtime())
    def write(name, rows, limit):
        with open(name, "w", encoding="utf-8") as f:
            f.write(f"# VLESS Card cloud check {stamp}: {len(rows)} answered (TCP, TLS handshake for TLS/REALITY) from GitHub, fastest first\n")
            for _, it in rows[:limit]: f.write(it[0] + "\n")
    write("out/alive.txt", alive, LIMIT_ALL)
    write("out/alive_ru.txt", [a for a in alive if a[1][3]], LIMIT_RU)
    json.dump({"at": stamp, "unique": len(links), "alive": len(alive)}, open("out/stats.json", "w"))

asyncio.run(main())
