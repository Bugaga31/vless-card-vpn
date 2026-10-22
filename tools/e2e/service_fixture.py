#!/usr/bin/env python3
"""Hermetic E2E protocol/media origin, NOT proof of public Telegram/YouTube availability.

Only the emulator's debug APK trusts this ephemeral certificate, for exact fixture hosts only.
Production probes, URL restrictions, nonce validation and certificate checks are unchanged.
No key or trust anchor is checked in, uploaded as diagnostics or added to a release APK.
"""
import json
import base64
import hashlib
import shutil
import socketserver
import ssl
import struct
import subprocess
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HTTPS_HOSTS = ['speed.cloudflare.com', 'cachefly.cachefly.net', 'proof.ovh.net']
HOSTS = ['www.youtube.com', 'fixture.googlevideo.com'] + HTTPS_HOSTS

# Synthetic testsrc2, 320x180 @24fps, 4s MPEG-4. Generated once with the original
# ffmpeg fixture command; pinned bytes remove apt/mirror provisioning from E2E.
VIDEO_SHA256 = '0d94f9b0789fc8fe56f83eb060fbd16f4894efd2f4b6d00926c08d3fff5ee7a4'

def media_bytes(encoded=None):
    if encoded is None:
        encoded = Path(__file__).with_name('fixture-video.mp4.b64').read_bytes()
    data = base64.b64decode(b''.join(encoded.split()), validate=True)
    if hashlib.sha256(data).hexdigest() != VIDEO_SHA256:
        raise ValueError('Synthetic media SHA-256 mismatch')
    if not 131072 <= len(data) <= 1048576 or data[4:8] != b'ftyp':
        raise ValueError('Synthetic media size/container mismatch')
    return data

def prepare(work):
    media = media_bytes()
    work.mkdir(parents=True, exist_ok=True)
    subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '2',
        '-keyout', str(work / 'key.pem'), '-out', str(work / 'cert.pem'), '-subj', '/CN=e2e-only.invalid',
        '-addext', 'subjectAltName=' + ','.join('DNS:' + h for h in HOSTS)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    (work / 'video.mp4').write_bytes(media)
    for module in ("app", "vpn-test-probe"):
        debug = ROOT / module / 'src/debug'
        (debug / 'res/raw').mkdir(parents=True, exist_ok=True)
        (debug / 'res/xml').mkdir(parents=True, exist_ok=True)
        shutil.copyfile(work / 'cert.pem', debug / 'res/raw/e2e_service_ca.pem')
        (debug / 'res/xml/e2e_service_security.xml').write_text('''<?xml version="1.0" encoding="utf-8"?>
    <network-security-config><base-config cleartextTrafficPermitted="false"><trust-anchors><certificates src="system" /></trust-anchors></base-config>
    <domain-config cleartextTrafficPermitted="false"><domain includeSubdomains="false">www.youtube.com</domain><domain includeSubdomains="false">fixture.googlevideo.com</domain><domain includeSubdomains="false">speed.cloudflare.com</domain><domain includeSubdomains="false">cachefly.cachefly.net</domain><domain includeSubdomains="false">proof.ovh.net</domain>
    <trust-anchors><certificates src="@raw/e2e_service_ca" /><certificates src="system" /></trust-anchors></domain-config></network-security-config>''')
        (debug / 'AndroidManifest.xml').write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:networkSecurityConfig="@xml/e2e_service_security" /></manifest>''')

def fixture_destination(port):
    # Xray 26.9.30 blocks private destinations after VLESS by default, including redirects.
    # Permit only this fixture's exact TCP loopback port; keep all other private-IP guards.
    return {'redirect': f'127.0.0.1:{port}', 'finalRules': [
        {'action': 'allow', 'network': 'tcp', 'port': str(port), 'ip': ['127.0.0.1/32']}]}

def patch(path):
    config = json.loads(path.read_text())
    outbound = next(o for o in config['outbounds'] if o['tag'] == 'qa-youtube')
    outbound['settings'] = fixture_destination(18444)
    next(o for o in config['outbounds'] if o['tag'] == 'qa-speed')['settings'] = fixture_destination(18444)
    config['outbounds'].extend([
        {'tag': 'qa-https-fixture', 'protocol': 'freedom', 'settings': fixture_destination(18444)},
        {'tag': 'qa-youtube-media', 'protocol': 'freedom', 'settings': fixture_destination(18444)},
        {'tag': 'qa-mtproto-fixture', 'protocol': 'freedom', 'settings': fixture_destination(18445)}])
    config['routing']['rules'] = [
        {'type': 'field', 'network': 'tcp', 'port': '443', 'domain': ['full:fixture.googlevideo.com'], 'outboundTag': 'qa-youtube-media'},
        {'type': 'field', 'network': 'tcp', 'port': '443', 'ip': ['149.154.167.51/32', '149.154.175.50/32'], 'outboundTag': 'qa-mtproto-fixture'},
        {'type': 'field', 'network': 'tcp', 'port': '443', 'domain': ['full:' + h for h in HTTPS_HOSTS[1:]], 'outboundTag': 'qa-https-fixture'}
    ] + config['routing']['rules']
    path.write_text(json.dumps(config))

def response(nonce):
    if len(nonce) != 16:
        raise ValueError('nonce')
    # Unauthenticated resPQ, the same bounded structural contract as TelegramProbe.validResponse.
    body = struct.pack('<I', 0x05162463) + nonce + bytes(range(16))
    body += b'\x08' + b'\x01' * 8 + b'\0' * 3 + struct.pack('<IIQ', 0x1cb5c415, 1, 123)
    frame = struct.pack('<QQI', 0, 1, len(body)) + body
    return bytes([len(frame) // 4]) + frame

def serve(work):
    class Media(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path.startswith('/watch?'):
                player = {'playabilityStatus': {'status': 'OK'}, 'streamingData': {'formats': [
                    {'mimeType': 'video/mp4', 'url': 'https://fixture.googlevideo.com/videoplayback?e2e=1'}]}}
                data = ('<script>var ytInitialPlayerResponse=' + json.dumps(player) + ';</script>').encode()
                code, mime = 200, 'text/html'
            elif self.path.startswith('/videoplayback'):
                data = (work / 'video.mp4').read_bytes()[:131072]
                code, mime = 206, 'video/mp4'
            elif self.path.startswith(('/__down?', '/1mb.test', '/files/1Mb.dat')):
                count = 65536 if self.headers.get('Range') == 'bytes=0-65535' else 1048576
                data, code, mime = bytes(range(256)) * (count // 256), 200, 'application/octet-stream'
            elif self.path.startswith('/generate_204'):
                data, code, mime = b'', 204, 'text/plain'
            else:
                data, code, mime = b'e2e media fixture', 200, 'text/html'
            self.send_response(code); self.send_header('Content-Type', mime)
            self.send_header('Content-Length', str(len(data)))
            if code == 206: self.send_header('Content-Range', f'bytes 0-{len(data)-1}/{(work / "video.mp4").stat().st_size}')
            self.end_headers(); self.wfile.write(data)
        def log_message(self, *args):
            pass
    class Mtproto(socketserver.StreamRequestHandler):
        def handle(self):
            self.connection.settimeout(3)
            wire = self.rfile.read(42)
            if len(wire) != 42 or wire[:2] != b'\xef\x0a' or wire[22:26] != struct.pack('<I', 0xbe7e8ef1):
                return
            self.wfile.write(response(wire[26:42])); self.wfile.flush()
    class Tcp(socketserver.ThreadingTCPServer):
        allow_reuse_address = True
        daemon_threads = True
    http = ThreadingHTTPServer(('127.0.0.1', 18444), Media)
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.load_cert_chain(work / 'cert.pem', work / 'key.pem')
    http.socket = tls.wrap_socket(http.socket, server_side=True)
    tcp = Tcp(('127.0.0.1', 18445), Mtproto)
    threading.Thread(target=tcp.serve_forever, daemon=True).start()
    http.serve_forever()

if __name__ == '__main__':
    mode, destination = sys.argv[1:3]
    {'prepare': prepare, 'patch': patch, 'serve': serve}[mode](Path(destination))
