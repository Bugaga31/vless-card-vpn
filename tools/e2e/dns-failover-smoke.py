#!/usr/bin/env python3
"""Closed loopback test of the pinned core's real DNS race; no public queries/endpoints."""
import argparse
import contextlib
import json
import socket
import struct
import subprocess
import tempfile
import threading
import time
from pathlib import Path


def query(name, ident):
    return struct.pack('!6H', ident, 0x100, 1, 0, 0, 0) + b''.join(bytes([len(s)]) + s.encode('ascii') for s in name.split('.')) + b'\0\0\1\0\1'


class Resolver:
    def __init__(self, reply):
        self.reply = reply
        self.count = 0
        self.closed = threading.Event()
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.bind(('127.0.0.1', 0))
        self.sock.settimeout(.1)
        self.port = self.sock.getsockname()[1]
        self.thread = threading.Thread(target=self.run, daemon=True)
        self.thread.start()

    def run(self):
        while not self.closed.is_set():
            try:
                data, peer = self.sock.recvfrom(4096)
                self.count += 1
                if self.reply and len(data) >= 12:
                    response = data[:2] + struct.pack('!5H', 0x8180, 1, 1, 0, 0) + data[12:]
                    response += b'\xc0\x0c' + struct.pack('!2HIH', 1, 1, 30, 4) + bytes([198, 18, 0, 1])
                    self.sock.sendto(response, peer)
            except socket.timeout:
                continue
            except OSError:
                break

    def close(self):
        self.closed.set()
        self.sock.close()
        self.thread.join(timeout=1)
        if self.thread.is_alive():
            raise RuntimeError('DNS fixture thread failed cleanup')


def check(condition, message):
    if not condition:
        raise RuntimeError(message)


def smoke(xray, template):
    # Use the emitted production resolver policy, replacing addresses with loopback
    # fixtures only. Keep timeoutMs, parallel flag and equivalent-policy grouping.
    source = json.loads(template.read_text())['dns']
    check(source.get('enableParallelQuery') is True, 'production Auto DNS race is disabled')
    upstreams = source['servers']
    check(len(upstreams) == 3, 'expected exactly three encrypted upstreams')
    check(all(u['address'].startswith('https://') and u['timeoutMs'] == 2500 for u in upstreams), 'unexpected production DNS policy')
    fixtures = []
    process = None
    try:
        fixtures = [Resolver(False), Resolver(True), Resolver(True)]
        with contextlib.closing(socket.socket(socket.AF_INET, socket.SOCK_DGRAM)) as reserved:
            reserved.bind(('127.0.0.1', 0)); port = reserved.getsockname()[1]
        dns = {k: v for k, v in source.items() if k in ('enableParallelQuery', 'queryStrategy', 'tag')}
        dns['disableCache'] = True
        dns['servers'] = [dict(u, address='127.0.0.1', port=f.port) for u, f in zip(upstreams, fixtures)]
        config = {
            'log': {'loglevel': 'none'}, 'dns': dns,
            'inbounds': [{'tag': 'query', 'listen': '127.0.0.1', 'port': port, 'protocol': 'dokodemo-door', 'settings': {'address': '1.1.1.1', 'port': 53, 'network': 'udp'}}],
            'outbounds': [{'tag': 'block', 'protocol': 'blackhole'}, {'tag': 'dns-out', 'protocol': 'dns'}, {'tag': 'fixture', 'protocol': 'freedom'}],
            'routing': {'rules': [
                {'inboundTag': ['query'], 'outboundTag': 'dns-out'},
                {'inboundTag': ['dns-module'], 'ip': ['127.0.0.1'], 'port': ','.join(str(f.port) for f in fixtures), 'outboundTag': 'fixture'},
                {'network': 'tcp,udp', 'outboundTag': 'block'},
            ]},
        }
        with tempfile.TemporaryDirectory(prefix='dns-race-') as folder:
            path = Path(folder) / 'config.json'; path.write_text(json.dumps(config))
            process = subprocess.Popen([xray, 'run', '-c', str(path)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            time.sleep(.4)
            check(process.poll() is None, 'pinned core rejected DNS config')
            with contextlib.closing(socket.socket(socket.AF_INET, socket.SOCK_DGRAM)) as client:
                client.settimeout(2)
                packet = query('healthy.fixture.test', 0x1234)
                started = time.monotonic(); client.sendto(packet, ('127.0.0.1', port))
                data = client.recv(4096); elapsed = time.monotonic() - started
                check(data[:2] == packet[:2] and len(data) >= 12 and struct.unpack('!H', data[6:8])[0] > 0 and data[-4:] == bytes([198, 18, 0, 1]), 'no valid DNS address response')
                check(elapsed < 2, 'silent primary serialized DNS fallback')
                check(fixtures[0].count > 0 and sum(f.count for f in fixtures[1:]) > 0, 'both failed primary and healthy fallback were not queried')
                print('PASS DNS: silent first upstream, valid loopback answer before primary timeout')
                for f in fixtures:
                    f.reply = False
                packet = query('all-timeout.fixture.test', 0x5678)
                client.settimeout(3.5); client.sendto(packet, ('127.0.0.1', port))
                try:
                    data = client.recv(4096)
                except socket.timeout:
                    data = b''
                check(not data or (len(data) >= 12 and data[:2] == packet[:2] and struct.unpack('!H', data[6:8])[0] == 0), 'all-timeout manufactured a positive answer')
                check(process.poll() is None, 'pinned core exited during resolver failures')
                print('PASS DNS: all upstreams unavailable never becomes PASS')
    finally:
        if process is not None:
            process.terminate()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait(timeout=3)
        for f in fixtures:
            f.close()
    print('PASS cleanup: DNS fixtures and pinned core stopped')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--xray', required=True)
    parser.add_argument('--config', type=Path, required=True)
    args = parser.parse_args()
    smoke(args.xray, args.config)
