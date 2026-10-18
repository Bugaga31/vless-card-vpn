#!/usr/bin/env python3
"""Bounded, isolated learning from measured public routes. Never extrapolate a runner result to a user's ISP.
Only known data-only profiles are tested; original REALITY SNI and certificate authentication are preserved.
"""
import copy, json, pathlib, subprocess, tempfile, time, urllib.request
from cloud_real import XRAY, BASE, build, config, key, measured_kbps, probe

PROFILES = {
    'chrome.n': {},
    'chrome.h5': {'fragment': True},
    **{f'vc.sni.{sni}.fast': {'sni': sni, 'fragment': True} for sni in ('vk.com', 'ya.ru', 'yandex.ru', 'vkvideo.ru')},
}
TTL = 7 * 86400

def transform(outbound, profile):
    """None means not compatible, not a failed network measurement."""
    if profile not in PROFILES: return None
    ob = copy.deepcopy(outbound); st = ob.get('streamSettings', {})
    if st.get('network', 'raw') != 'raw' or st.get('security') not in ('tls', 'reality'): return None
    st.setdefault('tlsSettings' if st['security']=='tls' else 'realitySettings', {})['fingerprint']='chrome'
    p = PROFILES[profile]
    tls = st.get('tlsSettings', {})
    if p.get('sni'):
        if st.get('security') != 'tls' or not tls.get('pinnedPeerCertSha256'): return None
        tls['serverName'] = p['sni']
    if p.get('fragment'):
        st['finalmask'] = {'tcp': [{'type': 'fragment', 'settings': {
            'packets': 'tlshello', 'length': '100-200', 'delay': '0-1' if p.get('sni') else '10-20', 'maxSplit': '10' if not p.get('sni') else '0'}}]}
        # App omits maxSplit=0 rather than serializing a special "unlimited" value.
        if p.get('sni'): st['finalmask']['tcp'][0]['settings'].pop('maxSplit')
    return ob

def record(stats, node, mask, ok, ms, kbps, now):
    name = node + ':' + mask
    row = stats.get(name, {})
    samples = [x for x in row.get('samples', []) if isinstance(x, list) and len(x) == 4 and
               isinstance(x[0], int) and now - TTL <= x[0] <= now and isinstance(x[1], bool)]
    samples.append([now, bool(ok), max(0, int(ms)), max(0, int(kbps))])
    stats[name] = {'node': node, 'mask': mask, 'at': now, 'samples': samples[-24:]}

def trusted_stats(raw, now):
    if not isinstance(raw, dict): return {}
    out = {}
    for k, v in list(raw.items())[:2000]:
        if (isinstance(k, str) and len(k) < 180 and isinstance(v, dict) and v.get('mask') in PROFILES and
            isinstance(v.get('at'), int) and now - TTL <= v['at'] <= now and isinstance(v.get('samples'), list)):
            # Keep only actual bounded numeric samples, never arbitrary data from old branch contents.
            samples = [x for x in v['samples'][-24:] if isinstance(x, list) and len(x) == 4 and
                       isinstance(x[0], int) and now - TTL <= x[0] <= now and isinstance(x[1], bool) and
                       all(isinstance(n, int) and not isinstance(n, bool) and 0 <= n <= 10000000 for n in x[2:])]
            if samples: out[k] = dict(v, samples=samples)
    return out

def measured_hints(stats, now):
    grouped = {}
    for row in trusted_stats(stats, now).values():
        # A recent failure remains a failure; old successes do not certify a broken recipe.
        samples = row['samples']
        if not samples[-1][1] or now - samples[-1][0] > 86400: continue
        good = [x for x in samples if x[1]]
        if len(good) < 3 or len(good) / len(samples) < .8: continue
        speed = sum(x[3] for x in good[-5:]) / len(good[-5:])
        grouped.setdefault(row['mask'], []).append(speed)
    return sorted(grouped, key=lambda mask: -sum(grouped[mask])/len(grouped[mask]))[:12]

def measure(ob, root):
    path = pathlib.Path(root)/'mask.json'; path.write_text(json.dumps(config([('', ob)])))
    if subprocess.run([XRAY, 'run', '-test', '-c', str(path)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode: return None
    x = subprocess.Popen([XRAY, 'run', '-c', str(path)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        time.sleep(.35)
        if x.poll() is not None: return None
        ms = probe(BASE)
        if not ms: return False, 0, 0
        result = subprocess.run(['curl', '-s', '-m', '6', '-H', 'Accept-Encoding: identity', '-o', '/dev/null',
            '-w', '%{http_code} %{content_type} %{size_download} %{speed_download}', '--socks5-hostname', f'127.0.0.1:{BASE}',
            'https://speed.cloudflare.com/__down?bytes=262144'], capture_output=True, text=True).stdout.split()
        try:
            ok = len(result) == 4 and result[0] == '200' and result[1].split(';')[0] in ('application/octet-stream', 'application/binary') and float(result[2]) >= 262144
            return ok, ms, int(float(result[3])*8/1000) if ok else 0
        except (ValueError, IndexError): return False, ms, 0
    finally:
        if x.poll() is None: x.terminate()
        try: x.wait(timeout=3)
        except subprocess.TimeoutExpired: x.kill(); x.wait()

def main():
    now = int(time.time()); root = pathlib.Path('out'); stats = {}
    try:
        with urllib.request.urlopen('https://raw.githubusercontent.com/Bugaga31/vless-card-vpn/servers/mask-stats.json', timeout=8) as response:
            raw = response.read(1000001)
        if len(raw) <= 1000000: stats = trusted_stats(json.loads(raw), now)
    except Exception: print('Mask history unavailable; starting with no measured prior')
    hist = json.loads((root/'history.json').read_text())
    links = [l for l, h in hist.items() if h.get('samples') and h['samples'][-1][1] is True and now - h.get('t', 0) in range(1801)]
    links.sort(key=lambda l: -measured_kbps(hist[l], now))
    # Rotate through the whole working pool, not only permanent favorites.
    offset = (now//600 * 3) % max(1, len(links))
    chosen = list(dict.fromkeys(links[:3] + (links + links)[offset:offset+3]))
    deadline = time.monotonic() + 240
    checked = passed = 0
    with tempfile.TemporaryDirectory() as tmp:
        for link, original in build(chosen):
            if time.monotonic() >= deadline: break
            baseline = transform(original, 'chrome.n')
            if baseline is None: continue
            control = measure(baseline, tmp)
            if not control or not control[0]: continue  # outage ≠ proof that every mask is bad
            compatible = [m for m in PROFILES if m != 'chrome.n' and transform(original, m) is not None]
            shift = now//600 % max(1, len(compatible))
            for mask in dict.fromkeys((compatible + compatible)[shift:shift+2]):
                if time.monotonic() >= deadline: break
                result = measure(transform(original, mask), tmp)
                if result is None: continue
                ok, ms, kbps = result; checked += 1; passed += int(ok)
                record(stats, key(link), mask, ok, ms, kbps, now)
    stats = dict(sorted(trusted_stats(stats, now).items(), key=lambda kv: -kv[1]['at'])[:1000])
    (root/'mask-stats.json').write_text(json.dumps(stats, separators=(',', ':')))
    print(f'Measured mask learning: {passed}/{checked} passed; {len(stats)} retained route/profile records; GitHub vantage only')

if __name__ == '__main__': main()
