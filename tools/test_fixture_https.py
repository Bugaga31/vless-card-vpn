import http.client, http.server, pathlib, socket, ssl, subprocess, tempfile, threading, unittest
from fixture_https import ThreadedTLSHTTPServer

class FixtureTLSIsolation(unittest.TestCase):
    def test_idle_cancelled_peer_cannot_block_next_handshake(self):
        with tempfile.TemporaryDirectory() as d:
            cert=pathlib.Path(d)/'cert.pem';key=pathlib.Path(d)/'key.pem'
            subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(key),'-out',str(cert),'-days','1','-subj','/CN=localhost','-addext','subjectAltName=DNS:localhost'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            class Handler(http.server.BaseHTTPRequestHandler):
                def do_GET(self): self.send_response(204);self.end_headers()
                def log_message(self,*args): pass
            ctx=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);ctx.load_cert_chain(cert,key)
            server=ThreadedTLSHTTPServer(('127.0.0.1',0),Handler,ctx)
            worker=threading.Thread(target=server.serve_forever,daemon=True);worker.start()
            abandoned=socket.create_connection(server.server_address,timeout=1)
            try:
                # Keep the first accepted TCP connection open without any TLS bytes.
                client=http.client.HTTPSConnection('localhost',server.server_port,timeout=1,context=ssl.create_default_context(cafile=str(cert)))
                try: client.request('GET','/generate_204');self.assertEqual(204,client.getresponse().status)
                finally: client.close()
            finally: abandoned.close();server.shutdown();server.server_close();worker.join(2)
