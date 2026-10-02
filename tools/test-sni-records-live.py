import sys, pathlib, tempfile, subprocess, ssl, socket, threading, http.server, time, argparse
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
parser = argparse.ArgumentParser(description='Loopback-only host ByeDPI TLS record/HTTPS check; not Android or ISP validation.')
parser.add_argument('--byedpi', required=True)
args = parser.parse_args()
byedpi = str(pathlib.Path(args.byedpi).resolve())
from tls_record_observer import Observations, create_relay

def receive(sock, n):
    data=b''
    while len(data)<n:
        chunk=sock.recv(n-len(data))
        if not chunk: raise RuntimeError('SOCKS EOF')
        data+=chunk
    return data

def spare_port():
    with socket.socket() as s: s.bind(('127.0.0.1',0)); return s.getsockname()[1]

with tempfile.TemporaryDirectory(prefix='tls-record-live-') as d:
    root=pathlib.Path(d); cert=root/'cert.pem'; key=root/'key.pem'
    subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(key),'-out',str(cert),'-days','1','-subj','/CN=fixture.test','-addext','subjectAltName=DNS:fixture.test'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    key.chmod(0o600)
    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self): self.send_response(204); self.send_header('Content-Length','0'); self.end_headers()
        def log_message(self,*args): pass
    backend=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler)
    server_tls=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); server_tls.load_cert_chain(cert,key)
    backend.socket=server_tls.wrap_socket(backend.socket,server_side=True)
    obs=Observations(); relay=create_relay(obs,listen=('127.0.0.1',0),target=backend.server_address)
    for server in [backend,relay]: threading.Thread(target=server.serve_forever,daemon=True).start()
    cases=[('TCP_ONLY',['--split','1+s'],1),('SNI_MIDDLE',['--split','0+sm','--tlsrec','0+sm'],2),('SNI_EDGES',['--split','1+s','--split','-1+se','--tlsrec','1+s','--tlsrec','-1+se'],3)]
    try:
        for label,options,expected in cases:
            port=spare_port()
            child=subprocess.Popen([byedpi,'--ip','127.0.0.1','--port',str(port),'--timeout','4']+options,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            try:
                for _ in range(50):
                    if child.poll() is not None: raise RuntimeError('ByeDPI exited')
                    try: raw=socket.create_connection(('127.0.0.1',port),timeout=2); break
                    except OSError: time.sleep(.04)
                else: raise RuntimeError('ByeDPI not ready')
                with raw:
                    raw.sendall(b'\x05\x01\x00'); assert receive(raw,2)==b'\x05\x00'
                    raw.sendall(b'\x05\x01\x00\x01'+socket.inet_aton('127.0.0.1')+relay.server_address[1].to_bytes(2,'big'))
                    reply=receive(raw,4); assert reply[:2]==b'\x05\x00'
                    receive(raw,{1:6,4:18}[reply[3]])
                    marker=obs.snapshot()['latest']
                    client_tls=ssl.create_default_context(cafile=str(cert))
                    with client_tls.wrap_socket(raw,server_hostname='fixture.test') as tls:
                        tls.sendall(b'GET /generate_204 HTTP/1.1\r\nHost: fixture.test\r\nConnection: close\r\n\r\n')
                        response=tls.recv(512); assert b' 204 ' in response
                    rows=[r for r in obs.snapshot()['observations'] if r['sequence']>marker]
                    assert rows and all(r['records']==expected for r in rows),rows
                    print(label,'HTTPS 204; actual outer ClientHello TLS records:',[r['records'] for r in rows])
            finally:
                child.terminate()
                try: child.wait(timeout=2)
                except subprocess.TimeoutExpired:child.kill();child.wait()
    finally:
        for server in [backend,relay]: server.shutdown(); server.server_close()
