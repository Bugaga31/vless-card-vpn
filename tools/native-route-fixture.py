#!/usr/bin/env python3
"""Controlled local VLESS/TLS + HTTPS fixture; no live subscription or public node.
Usage: python3 tools/native-route-fixture.py --sing-box /path/to/sing-box
Then adb push /printed/path/cert.pem /data/local/tmp/vless-fixture-ca.pem
Run NativeOutboundFixtureTest with -e fixture_ca_path /data/local/tmp/vless-fixture-ca.pem.
This tests native outbound/ByeDPI, NOT VpnService TUN or ISP blocking.
"""
import argparse, http.server, json, pathlib, signal, ssl, subprocess, tempfile, threading

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--sing-box', required=True, help='Locally installed sing-box executable')
a = p.parse_args()
with tempfile.TemporaryDirectory(prefix='vless-local-fixture-') as directory:
    root = pathlib.Path(directory)
    key, cert = root / 'key.pem', root / 'cert.pem'
    subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes',
                    '-keyout', str(key), '-out', str(cert), '-days', '2',
                    '-subj', '/CN=vpn.test.local', '-addext',
                    'subjectAltName=DNS:vpn.test.local,DNS:localhost'],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    key.chmod(0o600)
    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(204 if self.path == '/generate_204' else 302)
            self.send_header('Content-Length', '0'); self.end_headers()
        def log_message(self, fmt, *values):
            print(fmt % values, flush=True)
    https = http.server.ThreadingHTTPServer(('127.0.0.1', 18443), Handler)
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.load_cert_chain(str(cert), str(key))
    https.socket = tls.wrap_socket(https.socket, server_side=True)
    config = {'log': {'level': 'info'}, 'inbounds': [{
        'type': 'vless', 'tag': 'fixture-vless', 'listen': '127.0.0.1', 'listen_port': 24443,
        'users': [{'uuid': '00000000-0000-4000-8000-000000000001'}],
        'tls': {'enabled': True, 'certificate_path': str(cert), 'key_path': str(key)}}],
        'outbounds': [{'type': 'direct', 'tag': 'direct'}]}
    config_path = root / 'backend.json'
    config_path.write_text(json.dumps(config))
    subprocess.run([a.sing_box, 'check', '-c', str(config_path)], check=True)
    thread = threading.Thread(target=https.serve_forever, daemon=True); thread.start()
    backend = subprocess.Popen([a.sing_box, 'run', '-c', str(config_path)])
    def stop(*_):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, stop)
    try:
        print(f'Fixture CA (public certificate only): {cert}', flush=True)
        print('Both fixture listeners are loopback-only. Press Ctrl+C to stop.', flush=True)
        backend.wait()
    except KeyboardInterrupt:
        pass
    finally:
        backend.terminate()
        try: backend.wait(timeout=5)
        except subprocess.TimeoutExpired: backend.kill(); backend.wait()
        https.shutdown(); https.server_close()
