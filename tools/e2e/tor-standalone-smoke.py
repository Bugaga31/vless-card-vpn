#!/usr/bin/env python3
"""Real Xray -> loopback SOCKS fixture, with NO ByeDPI process.

Verifies standalone configuration and body routing, NOT real Tor bootstrap/carrier access.
All test listeners and destinations are loopback; the reserved .test name is handled
by the fixture without DNS. No public endpoint, key, user payload or packet capture.
"""
import argparse
import http.server
import json
from pathlib import Path
import socket
import socketserver
import struct
import subprocess
import tempfile
import threading
import time

BODY=b'TOR-STANDALONE-START-FIXTURE\n'
HOST='tor-start.test'

def exact(sock,n):
    result=b''
    while len(result)<n:
        chunk=sock.recv(n-len(result))
        if not chunk:raise RuntimeError('fixture connection ended early')
        result+=chunk
    return result

class Origin(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200);self.send_header('Content-Length',str(len(BODY)));self.end_headers();self.wfile.write(BODY)
    def log_message(self,*args):pass

class Socks(socketserver.BaseRequestHandler):
    def handle(self):
        try:
            s=self.request;s.settimeout(10)
            version,count=exact(s,2)
            if version!=5 or 0 not in exact(s,count):raise RuntimeError('fixture requires SOCKS5 noauth')
            s.sendall(b'\x05\x00')
            if exact(s,4)!=b'\x05\x01\x00\x03':raise RuntimeError('fixture requires domain CONNECT')
            host=exact(s,exact(s,1)[0]).decode('ascii');port=struct.unpack('!H',exact(s,2))[0]
            if host!=HOST or port!=self.server.origin_port:raise RuntimeError('fixture rejects all other destinations')
            with socket.create_connection(('127.0.0.1',port),timeout=10) as origin:
                s.sendall(b'\x05\x00\x00\x01\x7f\x00\x00\x01\x00\x00')
                request=b''
                while b'\r\n\r\n' not in request:
                    request+=s.recv(4096)
                    if len(request)>8192 or not request:raise RuntimeError('invalid fixture request')
                origin.sendall(request)
                while True:
                    data=origin.recv(4096)
                    if not data:break
                    s.sendall(data)
            self.server.seen.append((host,port))
        except Exception as exc:self.server.errors.append(type(exc).__name__+': '+str(exc))

class Server(socketserver.ThreadingTCPServer):
    daemon_threads=True
    allow_reuse_address=True

def main():
    p=argparse.ArgumentParser();p.add_argument('--xray',type=Path,required=True);p.add_argument('--config',type=Path,required=True);a=p.parse_args()
    config=json.loads(a.config.read_text())
    tags={x['tag'] for x in config['outbounds']}
    if 'byedpi' in tags or any(x.startswith('proxy-') for x in tags):raise SystemExit('configuration is not standalone Tor')
    origin=http.server.ThreadingHTTPServer(('127.0.0.1',0),Origin)
    fixture=Server(('127.0.0.1',0),Socks);fixture.origin_port=origin.server_port;fixture.seen=[];fixture.errors=[]
    for server in (origin,fixture):threading.Thread(target=server.serve_forever,daemon=True).start()
    with socket.socket() as free:free.bind(('127.0.0.1',0));port=free.getsockname()[1]
    config['inbounds'][0]['listen']='127.0.0.1';config['inbounds'][0]['port']=port
    config['inbounds'][0]['settings']={'auth':'noauth','udp':False}
    next(x for x in config['outbounds'] if x['tag']=='tor')['settings']['servers'][0]['port']=fixture.server_address[1]
    process=None
    try:
        with tempfile.TemporaryDirectory(prefix='tor-start-smoke-') as tmp:
            path=Path(tmp)/'config.json';path.write_text(json.dumps(config))
            with (Path(tmp)/'xray.log').open('wb') as log:
                process=subprocess.Popen([str(a.xray.resolve()),'run','-c',str(path)],stdout=log,stderr=log)
                deadline=time.monotonic()+10
                while True:
                    if process.poll() is not None:raise RuntimeError('Xray exited before listen')
                    try:client=socket.create_connection(('127.0.0.1',port),timeout=1);break
                    except OSError:
                        if time.monotonic()>=deadline:raise RuntimeError('Xray listener timeout')
                        time.sleep(.05)
                with client:
                    client.settimeout(10);client.sendall(b'\x05\x01\x00')
                    if exact(client,2)!=b'\x05\x00':raise RuntimeError('Xray SOCKS negotiation failed')
                    host=HOST.encode();client.sendall(b'\x05\x01\x00\x03'+bytes([len(host)])+host+struct.pack('!H',origin.server_port))
                    if exact(client,10)[:2]!=b'\x05\x00':raise RuntimeError('Xray CONNECT failed')
                    client.sendall(('GET / HTTP/1.0\r\nHost: '+HOST+'\r\n\r\n').encode())
                    response=b''
                    while True:
                        data=client.recv(4096)
                        if not data:break
                        response+=data
                        if len(response)>16384:raise RuntimeError('fixture response too large')
                    if response.split(b'\r\n\r\n',1)[-1]!=BODY:raise RuntimeError('body mismatch')
                if fixture.errors or fixture.seen!=[(HOST,origin.server_port)]:raise RuntimeError('Tor fixture did not carry the transfer')
                print(json.dumps({'status':'PASS','real_xray':True,'byedpi_started':False,'tor_socks_fixture_transfers':len(fixture.seen),'body_bytes':len(BODY),'real_tor_bootstrap_tested':False,'public_endpoint_used':False}))
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try:process.wait(timeout=5)
            except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5)
        for server in (fixture,origin):server.shutdown();server.server_close()

if __name__=='__main__':main()
