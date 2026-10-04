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
    edge_options=['--split','1+s','--split','-1+se','--tlsrec','1+s','--tlsrec','-1+se']
    cases=[('TCP_ONLY',['--split','1+s'],1,'fixture.test'),
           ('SNI_MIDDLE',['--split','0+sm','--tlsrec','0+sm'],2,'fixture.test'),
           ('SNI_EDGES',edge_options,3,'fixture.test'),
           ('SNI_EDGES_WRONG_HOST',edge_options,3,'wrong.test'),
           ('DISORDER',['--disorder','1'],1,'fixture.test'),
           ('SPLIT_DISORDER',['--split','1+s','--disorder','3+s'],1,'fixture.test'),
           ('DISORDER_THEN_FAKE',['--disorder','1','--auto=torst','--fake','-1','--ttl','8'],1,'fixture.test'),
           ('DISORDER_WRONG_HOST',['--disorder','1'],1,'wrong.test'),
           ('OOB',['--oob','1'],1,'fixture.test'),
           ('OOB_THEN_DISORDER',['--oob','1','--auto=torst','--disorder','1'],1,'fixture.test'),
           ('DISOOB',['--disoob','1'],1,'fixture.test'),
           ('MULTI_DISORDER',['--disorder','1','--split','1+s','--disorder','3+s','--split','6+s','--disorder','9+s','--split','12+s'],1,'fixture.test'),
           ('OOB_WRONG_HOST',['--oob','1'],1,'wrong.test'),
           # Fake-SNI masking: loopback ignores TTL, so only the TCP MD5 option keeps the fake from the server.
           ('MASK_FAKE',['--disorder','1','--fake','-1','--ttl','8','--md5sig','--fake-sni','ya.ru'],1,'fixture.test'),
           ('MASK_SPLIT_FAKE',['--split','1+s','--fake','-1','--ttl','8','--md5sig','--fake-sni','ya.ru','--fake-tls-mod','rand'],1,'fixture.test'),
           ('MASK_AUTO_FAKE',['--disorder','1','--auto=torst','--fake','-1','--ttl','8','--fake-sni','ya.ru','--fake-tls-mod','rand'],1,'fixture.test'),
           ('MASK_FAKE_WRONG_HOST',['--disorder','1','--fake','-1','--ttl','8','--md5sig','--fake-sni','ya.ru'],1,'wrong.test'),
           ('CUSTOM_LINE',['--disorder','1','--fake','-1','--ttl','8','--md5sig','--fake-sni','gosuslugi.ru','--fake-tls-mod','r'],1,'fixture.test')]
    try:
        for label,options,expected,hostname in cases:
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
                    rejected = False
                    try:
                        with client_tls.wrap_socket(raw,server_hostname=hostname) as tls:
                            # Do not send an HTTP request in the negative hostname case.
                            assert hostname == 'fixture.test', 'Wrong hostname was accepted'
                            tls.sendall(b'GET /generate_204 HTTP/1.1\r\nHost: fixture.test\r\nConnection: close\r\n\r\n')
                            response=tls.recv(512); assert b' 204 ' in response
                    except ssl.SSLCertVerificationError as error:
                        if hostname == 'fixture.test': raise
                        # OpenSSL X509_V_ERR_HOSTNAME_MISMATCH; not a generic timeout.
                        assert error.verify_code == 62, error.verify_code
                        rejected = True
                    assert rejected == (hostname != 'fixture.test')
                    rows=[r for r in obs.snapshot()['observations'] if r['sequence']>marker]
                    assert rows and all(r['records']==expected for r in rows),rows
                    print(label,'hostname rejected' if rejected else 'HTTPS 204', '; actual outer ClientHello TLS records:',[r['records'] for r in rows])
            finally:
                child.terminate()
                try: child.wait(timeout=2)
                except subprocess.TimeoutExpired:child.kill();child.wait()
    finally:
        for server in [backend,relay]: server.shutdown(); server.server_close()
