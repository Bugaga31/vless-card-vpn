#!/usr/bin/env python3
"""Certificate-verified local test origin: removes CDN throttling from the mask mechanics matrix."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import ssl
class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == '/generate_204':
            self.send_response(204); self.end_headers(); return
        if self.path != '/bytes':
            self.send_error(404); return
        self.send_response(200)
        self.send_header('Content-Type', 'application/octet-stream')
        self.send_header('Content-Length', '262144'); self.end_headers()
        self.wfile.write(bytes(range(256)) * 1024)
    def log_message(self, *args): pass
server = ThreadingHTTPServer(('127.0.0.1', 18443), Handler)
ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
ctx.load_cert_chain('/tmp/srv.crt', '/tmp/srv.key')
server.socket = ctx.wrap_socket(server.socket, server_side=True)
server.serve_forever()
