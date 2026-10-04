#!/usr/bin/env python3
"""Loopback-only VLESS/TLS + REALITY + HTTPS/DNS fixtures. Never a public relay.
No user config or key is consumed. Generated private keys are temporary.
Client test certificates are explicitly trusted, never insecure TLS.
"""
import argparse, http.server, json, pathlib, signal, socketserver, ssl, struct
import subprocess, tempfile, threading
from tls_record_observer import Observations, create_relay
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--sing-box', required=True)
a = p.parse_args()
with tempfile.TemporaryDirectory(prefix='vless-local-fixture-') as directory:
    root = pathlib.Path(directory)
    key, cert = root / 'key.pem', root / 'cert.pem'
    subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes',
        '-keyout', str(key), '-out', str(cert), '-days', '2', '-subj', '/CN=vpn.test.local',
        '-addext', 'subjectAltName=DNS:vpn.test.local,DNS:localhost,DNS:fixture.test,DNS:*.fixture.test,IP:198.18.0.1,IP:10.0.2.2'],
        check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    key.chmod(0o600)
    pairs = subprocess.check_output([a.sing_box, 'generate', 'reality-keypair'], text=True)
    keys = dict(line.split(': ', 1) for line in pairs.strip().splitlines())
    public_path = root / 'reality-public-key.txt'
    public_path.write_text(keys['PublicKey'])
    observations = Observations()
    relay = create_relay(observations)
    reality_relay = create_relay(observations, listen=('127.0.0.1', 27443), target=('127.0.0.1', 25443))
    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path == '/tls-observations':
                body = json.dumps(observations.snapshot()).encode('ascii')
                self.send_response(200); self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body)
                return
            self.send_response(204 if self.path == '/generate_204' else 302)
            self.send_header('Content-Length', '0'); self.end_headers()
        def log_message(self, fmt, *values): print(fmt % values, flush=True)
    from fixture_dns import reply as fixture_dns_reply
    class DNSHandler(socketserver.BaseRequestHandler):
        def handle(self):
            data, sock = self.request
            answer = fixture_dns_reply(data)
            if answer is not None:
                sock.sendto(answer, self.client_address)
    https = http.server.ThreadingHTTPServer(('127.0.0.1', 18443), Handler)
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); tls.load_cert_chain(str(cert), str(key))
    https.socket = tls.wrap_socket(https.socket, server_side=True)
    dns = socketserver.ThreadingUDPServer(('127.0.0.1', 15353), DNSHandler)
    config = {'log': {'level': 'info'}, 'inbounds': [
        {'type': 'vless', 'tag': 'fixture-vless', 'listen': '127.0.0.1', 'listen_port': 24443,
         'users': [{'uuid': '00000000-0000-4000-8000-000000000001'}],
         'tls': {'enabled': True, 'certificate_path': str(cert), 'key_path': str(key)}},
        {'type': 'vless', 'tag': 'fixture-reality', 'listen': '127.0.0.1', 'listen_port': 25443,
         'users': [{'uuid': '00000000-0000-4000-8000-000000000001', 'flow': 'xtls-rprx-vision'}],
         'tls': {'enabled': True, 'server_name': 'vpn.test.local', 'reality': {
             'enabled': True, 'handshake': {'server': '127.0.0.1', 'server_port': 18443},
             'private_key': keys['PrivateKey'], 'short_id': ['aabb']}}}],
        'outbounds': [{'type': 'direct', 'tag': 'direct'}],
        'route': {'rules': [
            {'port': 18443, 'action': 'route', 'outbound': 'direct', 'override_address': '127.0.0.1'},
            {'port': 15353, 'network': 'udp', 'ip_cidr': ['127.0.0.1/32'], 'action': 'route', 'outbound': 'direct'},
            {'action': 'reject'}]}}
    config_path = root / 'backend.json'; config_path.write_text(json.dumps(config)); config_path.chmod(0o600)
    subprocess.run([a.sing_box, 'check', '-c', str(config_path)], check=True)
    for server in [https, dns, relay, reality_relay]: threading.Thread(target=server.serve_forever, daemon=True).start()
    backend = subprocess.Popen([a.sing_box, 'run', '-c', str(config_path)])
    def stop(*_): raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, stop)
    try:
        print(f'Fixture CA (public certificate only): {cert}', flush=True)
        print(f'Fixture REALITY public key file: {public_path}', flush=True)
        print('Fixture listeners are loopback-only; unknown destinations rejected.', flush=True)
        backend.wait()
    except KeyboardInterrupt: pass
    finally:
        backend.terminate()
        try: backend.wait(timeout=5)
        except subprocess.TimeoutExpired: backend.kill(); backend.wait()
        for server in [https, dns, relay, reality_relay]: server.shutdown(); server.server_close()
