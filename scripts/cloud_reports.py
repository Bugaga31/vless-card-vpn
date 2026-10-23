#!/usr/bin/env python3
"""Shared cloud: merges anonymous phone reports (issues «[r] …» in the private repo) into out/cloud.json —
one mini model, one network setup (vcnet://) per operator, phone-verified public servers — and keeps one issue
«[p] …» per kind of problem with a phone counter. Processed reports are closed. Needs REPORT_TOKEN (Issues RW)."""
import base64, copy, json, math, os, re, sys, time, urllib.parse, urllib.request

REPO = os.environ.get("CLOUD_REPO", "Bugaga31/vless-card-cloud")
PREV = "https://raw.githubusercontent.com/Bugaga31/vless-card-vpn/servers/cloud_state.json"
TOKEN = os.environ.get("REPORT_TOKEN", "").strip()
N = 14
BAD = re.compile(r"не |нет |ошиб|упал|обрыв|медлен|блок|сбой|завис|чин|переключ|пропал|не работ", re.I)


def api(method, path, body=None):
    req = urllib.request.Request("https://api.github.com/repos/" + REPO + path, method=method,
                                 data=None if body is None else json.dumps(body).encode(),
                                 headers={"Authorization": "Bearer " + TOKEN, "Accept": "application/vnd.github+json",
                                          "Content-Type": "application/json", "User-Agent": "vless-card-cloud"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read() or b"null")


def parse(body):
    m = re.search(r"```json\s*(\{.*\})\s*```", body or "", re.S)
    try:
        return json.loads(m.group(1)) if m else None
    except Exception:
        return None


def key(line):
    return re.sub(r"\d+", "#", line).strip()[:120]


def vcnet(fam, d, m, x):
    o = json.dumps({"v": 1, "n": fam, "d": d, "m": m, "x": x}, ensure_ascii=False, separators=(",", ":"))
    b = base64.b64encode(o.encode()).decode().replace("+", "-").replace("/", "_").rstrip("=")
    return "vcnet://" + b + "#" + urllib.parse.quote(fam)


def unvcnet(link):
    b = link[len("vcnet://"):].split("#")[0].replace("-", "+").replace("_", "/")
    return json.loads(base64.b64decode(b + "=" * (-len(b) % 4)))


def valid_vector(a):
    return (isinstance(a, list) and len(a) == N and all(
        isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v) and -8 <= v <= 8 for v in a))


def merge(prev, reports, now):
    """Pure merge (tested): previous cloud.json + new reports → new cloud.json."""
    votes = copy.deepcopy(prev.get("votes", {}))
    vote_fade = 0.5 ** (max(0, now - prev.get("at", now)) / (7 * 86400))
    for f in votes.values():  # decay by elapsed time, not by the number of 10-minute runs
        for kind in f.values():
            for k in list(kind):
                kind[k] = round(kind[k] * vote_fade, 3)
                if kind[k] < 0.2: del kind[k]
    # model: average of previous (weight ≤ 50 phones) and every phone's weights (weight by its checks, ≤ 200)
    acc = {}
    pw = min(prev.get("phones", 0), 50)
    for k in ("m", "w"):
        a = (prev.get("brain") or {}).get(k)
        if valid_vector(a) and pw: acc[k] = ([v * pw * 100 for v in a], pw * 100)
    servers = {s["l"]: s for s in prev.get("servers", []) if now - s.get("t", 0) < 2 * 86400}
    ids = {k: t for k, t in prev.get("ids", {}).items() if now - t < 30 * 86400}  # phones seen in 30 days (hashed)
    # which servers each phone sits on (anonymous server keys), last 3 h: the phones spread themselves by this load
    use = {k: u for k, u in prev.get("use", {}).items() if now - u.get("t", 0) < 3 * 3600}
    # per operator: server key -> {"kb": smoothed kbit/s, "ok": checks that worked, "n": checks, "yt": YouTube ok, "t"};
    # evidence halves every day (by real time, the merge runs every 10 min), gone after 3 days without news
    net = {}
    fade = 0.5 ** (max(0, now - prev.get("at", now)) / 86400)
    for fam, d in (prev.get("net") or {}).items():
        for k, e in d.items():
            if now - e.get("t", 0) > 3 * 86400: continue
            e = dict(e, ok=round(e["ok"] * fade, 3), n=round(e["n"] * fade, 3))
            if e["n"] >= 0.3: net.setdefault(fam, {})[k] = e
    for r in reports:
        fam = str(r.get("net", ""))[:60]
        if fam and fam != "Нет сети":
            for row in (r.get("sp") or [])[:60]:
                if not (isinstance(row, list) and len(row) >= 3 and isinstance(row[0], str) and re.match(r"^[0-9a-f]{8}$", row[0])): continue
                try: kb, ms = max(0, min(int(row[1]), 1_000_000)), int(row[2])
                except (TypeError, ValueError): continue
                yt = row[3] if len(row) > 3 and row[3] in (0, 1) else -1
                e = net.setdefault(fam, {}).setdefault(row[0], {"kb": 0, "ok": 0.0, "n": 0.0, "yt": 0.0, "t": now})
                e["n"] = round(e["n"] + 1, 3); e["t"] = now
                if ms > 0:
                    e["ok"] = round(e["ok"] + 1, 3)
                    if kb: e["kb"] = kb if not e["kb"] else int(e["kb"] * 0.6 + kb * 0.4)
                if yt == 1: e["yt"] = round(e.get("yt", 0) + 1, 3)
    for r in reports:
        pid = str(r.get("id", ""))[:16]
        ids[__import__("hashlib").sha256(pid.encode()).hexdigest()[:10]] = now
        u = [k for k in (r.get("use") or [])[:8] if isinstance(k, str) and re.match(r"^[0-9a-f]{8}$", k)]
        if u: use[__import__("hashlib").sha256(pid.encode()).hexdigest()[:10]] = {"t": now, "s": u}
        b = r.get("brain") if isinstance(r.get("brain"), dict) else {}
        try: wt = min(max(int(b.get("seen", 0)), 0), 200)
        except (ValueError, TypeError, OverflowError): wt = 0
        for k in ("m", "w"):
            a = b.get(k)
            if wt and valid_vector(a):
                s, t = acc.get(k, ([0.0] * N, 0))
                acc[k] = ([s[i] + max(-8, min(8, a[i])) * wt for i in range(N)], t + wt)
        for fam, link in (r.get("profiles") or {}).items():
            try: p = unvcnet(link)
            except Exception: continue
            f = votes.setdefault(fam[:60], {"d": {}, "m": {}, "x": {}})
            for i, d in enumerate(p.get("d", [])[:5]): f["d"][d] = f["d"].get(d, 0) + (5 - i) / 5
            for i, m in enumerate(p.get("m", [])[:12]): f["m"][m] = f["m"].get(m, 0) + (12 - i) / 12
            for x in p.get("x", [])[:6]:
                xs = json.dumps(x, sort_keys=True, ensure_ascii=False)
                if len(xs) < 2000: f["x"][xs] = f["x"].get(xs, 0) + 1
        for l in (r.get("ok") or [])[:15]:
            if isinstance(l, str) and len(l) < 3000 and re.match(r"^(vless|vmess|trojan|ss|hysteria2|hy2)://", l):
                s = servers.setdefault(l, {"l": l, "n": 0, "t": now})
                s["n"] += 1; s["t"] = now
    top = lambda d, n: [k for k, _ in sorted(d.items(), key=lambda kv: -kv[1])[:n]]
    profiles = {fam: vcnet(fam, top(f["d"], 5), top(f["m"], 12), [json.loads(x) for x in top(f["x"], 6)]) for fam, f in votes.items()
                if f["d"] or f["m"] or f["x"]}
    brain = {k: [round(v / t, 4) for v in s] for k, (s, t) in acc.items() if t}
    load = {}
    for u in use.values():
        for k in u["s"]: load[k] = load.get(k, 0) + 1
    return {"stamp": time.strftime("%Y-%m-%d %H:%M", time.gmtime(now)), "at": now, "phones": len(ids), "ids": ids, "use": use,
            "load": dict(sorted(load.items(), key=lambda kv: -kv[1])[:300]),
            "net": {f: dict(sorted(d.items(), key=lambda kv: -kv[1]["n"])[:600]) for f, d in net.items()},
            "brain": brain, "profiles": profiles, "votes": votes,
            "servers": sorted(servers.values(), key=lambda s: (-s["n"], -s["t"]))[:200]}


def public_net(net):
    """Per operator: key -> [kbit/s, % worked, checks], the 300 most checked (blocked ones included: phones skip them)."""
    out = {}
    for f, d in net.items():
        rows = sorted(d.items(), key=lambda kv: -kv[1]["n"])[:300]
        out[f] = {k: [e["kb"], int(round(100 * e["ok"] / e["n"])) if e["n"] else 0, max(1, int(round(e["n"])))] for k, e in rows}
    return out




def public_offers(cloud):
    """Elite offers curated by hand in the private repo; the phone shows ≤3, sanitized on the client."""
    raw = cloud.get("offers") or []
    if not isinstance(raw, list): return []
    out = []
    for e in raw:
        if len(out) >= 3: break
        if not isinstance(e, dict): continue
        o = {k: str(e.get(k, ""))[: (40 if k == "id" else 60 if k == "title" else 160 if k == "text" else 300 if k == "link" else 10)]
             for k in ("id", "title", "text", "link", "need")}
        if not o["id"] or not o["title"] or not o["link"].startswith("https://") or o["need"] not in ("yt", "chat", "new", "any"): continue
        out.append(o)
    return out
def public(cloud):
    """What phones download (small, every 20 min with ETag): the stamp changes only when the content does."""
    o = {"phones": cloud["phones"], "brain": cloud["brain"], "profiles": cloud["profiles"], "servers": [x["l"] for x in cloud["servers"][:40]],
         "load": cloud.get("load", {}), "net": public_net(cloud.get("net", {})), "offers": public_offers(cloud)}
    o["stamp"] = __import__("hashlib").sha256(json.dumps(o, sort_keys=True).encode()).hexdigest()[:12]
    return o


def device_summary(cloud, now):
    def recent(t, window):
        return isinstance(t, (int, float)) and 0 <= now - t < window
    reports = sum(recent(t, 30 * 86400) for t in cloud.get("ids", {}).values())
    routes = sum(recent(u.get("t", 0), 3 * 3600) for u in cloud.get("use", {}).values() if isinstance(u, dict))
    return {"schema": 1, "generated_at": now, "reporting_devices_30d": reports,
            "route_reports_devices_3h": routes, "online_devices": None, "apk_intake_enabled": False,
            "notice": "Aggregate historical reports, not live connections. Current APK has no protected report intake. No individual device identifiers are published."}


def problems(reports):
    seen = {}
    for r in reports:
        for line in r.get("lines", []):
            if isinstance(line, str) and BAD.search(line):
                e = seen.setdefault(key(line), {"phones": set(), "v": set(), "net": set(), "text": line[:200]})
                e["phones"].add(r.get("id")); e["v"].add(str(r.get("v", ""))[:10]); e["net"].add(str(r.get("net", ""))[:40])
    return seen


def main():
    os.makedirs("out", exist_ok=True)
    try:
        prev = json.load(urllib.request.urlopen(PREV, timeout=20))
    except Exception:
        prev = {}
    if not TOKEN:
        print("REPORT_TOKEN is empty: no reports read")
        reports, issues = [], []
    else:
        issues = []
        for page in range(1, 6):
            got = api("GET", f"/issues?state=open&per_page=100&page={page}")
            issues += got
            if len(got) < 100: break
        reports = [r for r in (parse(i.get("body")) for i in issues if i.get("title", "").startswith("[r]")) if isinstance(r, dict)]
    now = int(time.time())
    cloud = merge(prev, reports, now)
    with open("out/cloud_state.json", "w") as f: json.dump(cloud, f, ensure_ascii=False, separators=(",", ":"))
    with open("out/cloud.json", "w") as f: json.dump(public(cloud), f, ensure_ascii=False, separators=(",", ":"))
    summary = device_summary(cloud, now)
    with open("out/device-summary.json", "w") as f: json.dump(summary, f, ensure_ascii=False, separators=(",", ":"))
    with open("out/devices.md", "w") as f:
        f.write("# Сводка устройств\n\nОбновлено: " + time.strftime("%Y-%m-%d %H:%M UTC", time.gmtime(now)) + "\n\n")
        f.write("| Показатель | Количество |\n|---|---:|\n")
        f.write(f"| Устройства с отчётами за 30 дней | {summary['reporting_devices_30d']} |\n")
        f.write(f"| Устройства с отчётами маршрутов за 3 часа | {summary['route_reports_devices_3h']} |\n\n")
        f.write("Это исторические отчёты, **не список подключённых сейчас устройств**. В текущем APK отправка выключена: требуется защищённый приёмник и отдельное согласие пользователя. IP, аккаунты и идентификаторы устройств здесь не публикуются. При наличии допустимых отчётов агрегаты помогают ранжированию по сети, скорости и успешности.\n")
    # phone-verified servers go first in the cloud lists
    links = [s["l"] for s in cloud["servers"]]
    for name in ("alive_ru.txt", "alive.txt"):
        p = "out/" + name
        old = open(p).read().splitlines() if os.path.exists(p) else []
        with open(p, "w") as f: f.write("\n".join(dict.fromkeys(links + old)) + "\n")
    print(f"reports={len(reports)} phones={cloud['phones']} profiles={len(cloud['profiles'])} servers={len(links)}")
    if not TOKEN: return
    open_p = {i["title"]: i for i in issues if i.get("title", "").startswith("[p] ")}
    probs = sorted(problems(reports).items(), key=lambda kv: -len(kv[1]["phones"]))[:15]
    for k, e in probs:
        title = "[p] " + k
        old = open_p.get(title)
        # phones are counted once: hashed ids are kept in a hidden comment of the issue (the old counter grew with
        # every report of the same phone)
        ob = (old or {}).get("body") or ""
        m = re.search(r"<!-- ids: ([0-9a-f,]*) -->", ob)
        ids = set(filter(None, m.group(1).split(","))) if m else set()
        ids |= {__import__("hashlib").sha256(str(p).encode()).hexdigest()[:10] for p in e["phones"]}
        vs = set(e["v"]) | set(filter(None, (re.search(r"Версии: (.*)", ob) or [None, ""])[1].split(", ")))
        ns = set(e["net"]) | set(filter(None, (re.search(r"Сети: (.*)", ob) or [None, ""])[1].split(", ")))
        cnt = len(ids) if m or not old else max(len(ids), 1)
        body = (f"**{e['text']}**\n\nТелефонов всего: {cnt}\nПоследний раз: {cloud['stamp']} UTC\n"
                f"Версии: {', '.join(sorted(vs))}\nСети: {', '.join(sorted(ns))}\n\n<!-- ids: {','.join(sorted(ids))} -->\n")
        try:
            if old: api("PATCH", f"/issues/{old['number']}", {"body": body})
            else: api("POST", "/issues", {"title": title[:200], "body": body})
        except Exception as ex:
            print("problem issue failed:", ex)
    for i in issues:
        if i.get("title", "").startswith("[r]"):
            try: api("PATCH", f"/issues/{i['number']}", {"state": "closed", "state_reason": "completed"})
            except Exception as ex: print("close failed:", ex)


if __name__ == "__main__":
    main()
