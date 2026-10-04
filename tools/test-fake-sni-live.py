"""Loopback check that ByeDPI fake ClientHellos carry the chosen masking SNI (what a DPI would see).
Loopback ignores TTL, so a raw server sees either the fake (must contain the mask) or the intact real
ClientHello. Not ISP validation: on a real path the TTL-8 fake expires before a distant server."""
import argparse, socket, ssl, subprocess, threading, time
parser = argparse.ArgumentParser(); parser.add_argument('--byedpi', required=True); byedpi = parser.parse_args().byedpi

def hello():
    obj = ssl.create_default_context().wrap_bio(ssl.MemoryBIO(), out := ssl.MemoryBIO(), server_hostname='fixture.test')
    try: obj.do_handshake()
    except ssl.SSLWantReadError: pass
    return out.read()

def rx(s, n):
    d = b''
    try:
        while len(d) < n:
            c = s.recv(n - len(d))
            if not c: break
            d += c
    except socket.timeout: pass
    return d

def run(opts, mask):
    h = hello(); srv = socket.socket(); srv.bind(('127.0.0.1', 0)); srv.listen(1); got = {}
    def accept():
        c, _ = srv.accept(); c.settimeout(3); got['d'] = rx(c, len(h)); c.close()
    t = threading.Thread(target=accept, daemon=True); t.start()
    s = socket.socket(); s.bind(('127.0.0.1', 0)); port = s.getsockname()[1]; s.close()
    child = subprocess.Popen([byedpi, '--ip', '127.0.0.1', '--port', str(port), '--timeout', '4'] + opts,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        for _ in range(50):
            if child.poll() is not None: raise RuntimeError('ByeDPI exited')
            try: c = socket.create_connection(('127.0.0.1', port), timeout=2); break
            except OSError: time.sleep(.04)
        else: raise RuntimeError('ByeDPI not ready')
        with c:
            c.sendall(b'\x05\x01\x00'); assert rx(c, 2) == b'\x05\x00'
            c.sendall(b'\x05\x01\x00\x01' + socket.inet_aton('127.0.0.1') + srv.getsockname()[1].to_bytes(2, 'big'))
            assert rx(c, 10)[1] == 0
            c.sendall(h); t.join(6)
        d = got.get('d', b'')
        if d == h: return 'real'
        assert mask.encode() in d and b'fixture.test' not in d[:len(d)], ('fake without mask', len(d))
        return 'mask'
    finally:
        child.terminate(); child.wait(); srv.close()

def short_hello():
    # Minimal 128-byte ClientHello (Go/Conscrypt-like sizes): the built-in fake template cannot shrink to it.
    import os, struct
    sni = b'fixture.test'
    exts = struct.pack('>HHHBH', 0, len(sni) + 5, len(sni) + 3, 0, len(sni)) + sni + bytes.fromhex('002b0003020304000a00040002001d000d000400020403')
    body = b'\x03\x03' + os.urandom(32) + b'\x20' + os.urandom(32) + b'\x00\x02\x13\x01\x01\x00' + struct.pack('>H', len(exts)) + exts
    hs = b'\x01' + len(body).to_bytes(3, 'big') + body
    return b'\x16\x03\x01' + struct.pack('>H', len(hs)) + hs

cases = [('MASK_FAKE', ['--disorder', '1', '--fake', '-1', '--ttl', '8', '--fake-sni', 'ya.ru', '--fake-tls-mod', 'orig'], 'ya.ru'),
         ('MASK_FAKE_RAND', ['--fake', '-1', '--ttl', '8', '--fake-sni', 'vk.com', '--fake-tls-mod', 'rand,orig'], 'vk.com'),
         ('CUSTOM_LINE', ['--disorder', '1', '--fake', '-1', '--ttl', '8', '--fake-sni', 'gosuslugi.ru', '--fake-tls-mod', 'o'], 'gosuslugi.ru')]
openssl_hello = hello
for label, opts, mask in cases:
    for name, generator in (('openssl', openssl_hello), ('short', short_hello)):
        hello = generator
        results = [run(opts, mask) for _ in range(4)]
        print(label, name, 'outcomes:', results)
print('Fake SNI check: every leaked fake carried the masking domain')
