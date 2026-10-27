#!/usr/bin/env python3
"""Real Xray, two strict loopback SOCKS fixtures and local TLS body origin.
NOT real Tor, ByeDPI packet processing, public VPN, Android or operator validation.
Configs are exported by ChainProbeConfigTest. No arbitrary forwarding allowed.
"""
import argparse
import hashlib
import http.client
import http.server
import json
from pathlib import Path
import select
import socket
import socketserver
import ssl
import struct
import subprocess
import tempfile
import threading
import time

HOST = 'chain-origin.test'
BODY = b'CHAIN-LOCAL-TLS-BODY\n' * 4096


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def exact(sock, size):
    buf = b''
    while len(buf) < size:
        data = sock.recv(size - len(buf))
        require(data, 'early socket close')
        buf += data
    return buf


class Origin(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.server.transfers += 1
        self.send_response(200)
        self.send_header('Content-Length', str(len(BODY)))
        self.end_headers()
        self.wfile.write(BODY)
    def log_message(self, *args):
        pass


class Socks(socketserver.BaseRequestHandler):
    def handle(self):
        try:
            s = self.request
            s.settimeout(4)
            version, count = exact(s, 2)
            require(version == 5 and 0 in exact(s, count), 'SOCKS noauth required')
            s.sendall(b'\x05\x00')
            ver, cmd, reserved, kind = exact(s, 4)
            require((ver, cmd, reserved) == (5, 1, 0), 'CONNECT only')
            if kind == 3:
                host = exact(s, exact(s, 1)[0]).decode('ascii')
            elif kind == 1:
                host = socket.inet_ntoa(exact(s, 4))
            else:
                raise RuntimeError('unsupported address')
            port = struct.unpack('!H', exact(s, 2))[0]
            require((host, port) == self.server.allowed, 'destination not in closed fixture allowlist')
            # Exact allowlisted name maps locally, never public DNS.
            with (connect_http(self.server.http_uplink, host, port) if hasattr(self.server, 'http_uplink') else connect_socks(self.server.uplink, host, port) if hasattr(self.server, 'uplink') else socket.create_connection(('127.0.0.1', port), timeout=4)) as dst:
                self.server.seen.append((host, port))
                s.sendall(b'\x05\x00\x00\x01\x7f\x00\x00\x01\x00\x00')
                until = time.monotonic() + 8
                while time.monotonic() < until:
                    ready, _, _ = select.select([s, dst], [], [], .2)
                    for source in ready:
                        data = source.recv(16384)
                        if not data:
                            return
                        (dst if source is s else s).sendall(data)
        except (OSError, RuntimeError) as exc:
            # Connection shutdown may race the deliberately killed negative fixture.
            self.server.errors.append(type(exc).__name__)


class Proxy(socketserver.ThreadingTCPServer):
    daemon_threads = True
    allow_reuse_address = True
    def __init__(self, allowed):
        super().__init__(('127.0.0.1', 0), Socks)
        self.allowed = allowed
        self.seen = []
        self.errors = []


def start(server):
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return thread


def connect_http(port, host, target_port):
    raw = socket.create_connection(('127.0.0.1', port), timeout=4)
    try:
        raw.settimeout(4)
        authority = f'{host}:{target_port}'
        raw.sendall(f'CONNECT {authority} HTTP/1.1\r\nHost: {authority}\r\n\r\n'.encode())
        response = b''
        while not response.endswith(b'\r\n\r\n'):
            require(len(response) < 4096, 'oversized HTTP uplink response')
            response += exact(raw, 1)
        require(response.split(b'\r\n', 1)[0].split()[1] == b'200', 'HTTP uplink CONNECT failed')
        return raw
    except BaseException:
        raw.close()
        raise


def connect_socks(port, host, target_port):
    raw = socket.create_connection(('127.0.0.1', port), timeout=4)
    try:
        raw.settimeout(4)
        raw.sendall(b'\x05\x01\x00')
        require(exact(raw, 2) == b'\x05\x00', 'uplink SOCKS negotiation failed')
        name = host.encode('ascii')
        raw.sendall(b'\x05\x01\x00\x03' + bytes([len(name)]) + name + struct.pack('!H', target_port))
        head = exact(raw, 4)
        require(head[:3] == b'\x05\x00\x00', 'uplink CONNECT failed')
        if head[3] == 1: exact(raw, 4)
        elif head[3] == 4: exact(raw, 16)
        elif head[3] == 3: exact(raw, exact(raw, 1)[0])
        else: raise RuntimeError('invalid uplink address')
        exact(raw, 2)
        return raw
    except BaseException:
        raw.close()
        raise


def transfer(port, origin_port, context):
    with socket.create_connection(('127.0.0.1', port), timeout=3) as raw:
        raw.settimeout(4)
        raw.sendall(b'\x05\x01\x00')
        require(exact(raw, 2) == b'\x05\x00', 'Xray SOCKS negotiation failed')
        name = HOST.encode('ascii')
        raw.sendall(b'\x05\x01\x00\x03' + bytes([len(name)]) + name + struct.pack('!H', origin_port))
        ver, code, reserved, kind = exact(raw, 4)
        require((ver, code, reserved) == (5, 0, 0), 'Xray CONNECT failed')
        if kind == 1:
            exact(raw, 4)
        elif kind == 4:
            exact(raw, 16)
        elif kind == 3:
            exact(raw, exact(raw, 1)[0])
        else:
            raise RuntimeError('invalid Xray response')
        exact(raw, 2)
        with context.wrap_socket(raw, server_hostname=HOST) as tls:
            tls.sendall(f'GET /body HTTP/1.1\r\nHost: {HOST}\r\nConnection: close\r\n\r\n'.encode())
            response = http.client.HTTPResponse(tls)
            response.begin()
            require(response.status == 200, 'TLS origin status mismatch')
            data = response.read(len(BODY) + 1)
            require(data == BODY, 'TLS body mismatch')


def run_case(xray, configs, layout, cert, key, tmp, http_uplink=False):
    origin = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Origin)
    origin.daemon_threads = True
    origin.transfers = 0
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.load_cert_chain(cert, key)
    origin.socket = tls.wrap_socket(origin.socket, server_side=True)
    servers = [origin]
    threads = [start(origin)]
    process = None
    stopped = set()
    try:
        server = Proxy((HOST, origin.server_port))
        servers.append(server); threads.append(start(server))
        uses_outer = layout in ('DPI_SERVER', 'TOR_SERVER')
        outer = Proxy(('127.0.0.1', server.server_address[1]) if uses_outer else (HOST, origin.server_port))
        servers.append(outer); threads.append(start(outer))
        with socket.socket() as free:
            free.bind(('127.0.0.1', 0)); listener = free.getsockname()[1]
        c = json.loads((configs / (layout + '.json')).read_text())
        c['inbounds'][0]['port'] = listener
        if layout == 'LIVE_SERVER_TOR':
            # A simulated Tor SOCKS endpoint makes its transport connection through
            # the actual generated PT uplink and remote-server outbound. NOT real Tor.
            for inbound in c['inbounds'][1:]:
                with socket.socket() as free:
                    free.bind(('127.0.0.1', 0)); inbound['port'] = free.getsockname()[1]
                if inbound['tag'] == 'tor-pt-uplink': outer.uplink = inbound['port']
                if inbound['tag'] == 'tor-uplink' and http_uplink: outer.http_uplink = inbound['port']
            require(hasattr(outer, 'uplink'), 'missing generated Tor PT uplink')
            main_address = c['outbounds'][0]['settings'].get('servers', [c['outbounds'][0]['settings']])[0]
            main_address['address'] = '127.0.0.1'

        main = c['outbounds'][0]
        endpoint = main['settings'].get('servers', [main['settings']])[0]
        endpoint['port'] = outer.server_address[1] if layout == 'TOR' else server.server_address[1]
        for out in c['outbounds'][1:]:
            if out['tag'] in ('chain-tor', 'chain-dpi', 'tor'):
                out['settings']['servers'][0]['port'] = outer.server_address[1]
        path = tmp / (layout + '.json'); path.write_text(json.dumps(c))
        check = subprocess.run([str(xray), 'run', '-test', '-c', str(path)], capture_output=True, timeout=10)
        require(check.returncode == 0, 'Xray rejected generated configuration: ' + (check.stdout + check.stderr).decode(errors='replace')[-1400:])
        with (tmp / (layout + '.log')).open('wb') as log:
            process = subprocess.Popen([str(xray), 'run', '-c', str(path)], stdout=log, stderr=log)
        until = time.monotonic() + 5
        while True:
            require(process.poll() is None, 'Xray exited before probe')
            try:
                with socket.create_connection(('127.0.0.1', listener), timeout=.2):
                    break
            except OSError:
                require(time.monotonic() < until, 'Xray startup timeout')
                time.sleep(.03)
        context = ssl.create_default_context(cafile=str(cert))
        transfer(listener, origin.server_port, context)
        require(origin.transfers == 1, 'Expected exactly one TLS transfer')
        require(bool(outer.seen) == (layout != 'SERVER'), 'Outer hop was skipped or unexpectedly used')
        require(bool(server.seen) == (layout != 'TOR'), 'Server hop was skipped or unexpectedly used')
        require(not server.errors and not outer.errors, 'Fixture handshake error on positive path')
        cut = server if layout in ('SERVER', 'LIVE_SERVER_TOR') else outer
        cut.shutdown(); cut.server_close(); stopped.add(cut)
        try:
            transfer(listener, origin.server_port, context)
        except (OSError, RuntimeError, ssl.SSLError):
            pass
        else:
            raise RuntimeError('Chain silently bypassed dead required hop')
        require(origin.transfers == 1, 'Traffic bypassed the stopped chain hop')
        return {'layout': layout, 'uplink': 'http' if http_uplink else 'socks', 'tls_body_bytes': len(BODY), 'sha256': hashlib.sha256(BODY).hexdigest(),
                'required_hop_failure_blocks_transfer': True, 'real_tor': False, 'real_byedpi': False}
    finally:
        if process is not None:
            process.terminate()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait(timeout=3)
        for s in reversed(servers):
            if s not in stopped:
                s.shutdown(); s.server_close()
        for t in threads:
            t.join(timeout=2)
        require(all(not t.is_alive() for t in threads), 'Fixture server thread leak')
        require(process is None or process.poll() is not None, 'Xray process leak')


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--xray', type=Path, required=True)
    p.add_argument('--configs', type=Path, required=True)
    a = p.parse_args()
    with tempfile.TemporaryDirectory(prefix='chain-fixtures-') as directory:
        tmp = Path(directory); cert = tmp/'ca.pem'; key = tmp/'key.pem'
        subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
                        '-keyout', str(key), '-out', str(cert), '-subj', '/CN=' + HOST,
                        '-addext', 'subjectAltName=DNS:' + HOST], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for layout in ('SERVER', 'DPI_SERVER', 'TOR', 'TOR_SERVER', 'LIVE_SERVER_TOR'):
            print(json.dumps(run_case(a.xray.resolve(), a.configs, layout, cert, key, tmp)), flush=True)
    print(json.dumps({'cleanup': 'PASS', 'public_endpoints_used': False, 'android_tested': False}))

if __name__ == '__main__':
    main()
