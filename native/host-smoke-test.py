from pathlib import Path
import subprocess,ssl,socket,threading,struct,time,json
import tempfile
r=Path(__file__).resolve().parent.parent;d=r/'native/byedpi'
work=Path(tempfile.mkdtemp(prefix='byedpi-test-'));exe=str(work/'byedpi-host')
subprocess.run(['gcc','-D_DEFAULT_SOURCE','-Dmain=byedpi_main','-std=c99','-O2','-I'+str(d),*[str(d/(s+'.c')) for s in ('packets','main','conev','proxy','desync','mpool','extend')],str(r/'native/launcher.c'),'-o',exe],check=True)
subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(work/'test-key.pem'),'-out',str(work/'test-cert.pem'),'-days','1','-subj','/CN=localhost','-addext','subjectAltName=DNS:localhost'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
ctx=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);ctx.load_cert_chain(str(work/'test-cert.pem'),str(work/'test-key.pem'))
presets=[('COMBINED',['--split','1+s','--tlsrec','1+s']),('TCP_ONLY',['--split','1+s']),('TLS_RECORD_ONLY',['--tlsrec','1+s'])]
try:
 for name,extra in presets:
  srv=socket.socket();srv.bind(('127.0.0.1',0));srv.listen(1);target=srv.getsockname()[1]
  errors=[]
  def server():
   try:
    s,_=srv.accept()
    s.settimeout(5)
    header=s.recv(9,socket.MSG_PEEK|socket.MSG_WAITALL)
    assert len(header)==9 and header[0]==22 and header[5]==1, 'Missing ClientHello'
    record_length=int.from_bytes(header[3:5],'big')
    handshake_length=4+int.from_bytes(header[6:9],'big')
    is_tls_record_split=record_length < handshake_length
    assert is_tls_record_split == ('--tlsrec' in extra), 'TLS record transformation mismatch'
    with ctx.wrap_socket(s,server_side=True) as tls:
     tls.settimeout(5);data=tls.recv(4096);assert data.startswith(b'GET / ')
     tls.sendall(b'HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n')
   except Exception as e:errors.append(type(e).__name__)
  thread=threading.Thread(target=server,daemon=True);thread.start()
  with socket.socket() as reserved:reserved.bind(('127.0.0.1',0));port=reserved.getsockname()[1]
  p=subprocess.Popen([exe,'--ip','127.0.0.1','--port',str(port),'--max-conn','128','--timeout','4']+extra,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
  try:
   time.sleep(.15);assert p.poll() is None
   with socket.create_connection(('127.0.0.1',port),timeout=5) as sock:
    sock.sendall(b'\x05\x01\x00');assert sock.recv(2)==b'\x05\x00'
    sock.sendall(b'\x05\x01\x00\x01'+socket.inet_aton('127.0.0.1')+struct.pack('!H',target))
    response=sock.recv(10);assert len(response)==10 and response[1]==0,response
    client=ssl.create_default_context(cafile=str(work/'test-cert.pem'))
    with client.wrap_socket(sock,server_hostname='localhost') as tls:
     tls.sendall(b'GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n');assert tls.recv(1024).startswith(b'HTTP/1.1 204 ')
   thread.join(6);assert not errors and not thread.is_alive(),errors
   print('HOST_NATIVE_PASS:',name,'preserves verified TLS/HTTPS 204; TLS record shape verified')
  finally:
   p.terminate();p.wait(timeout=3);srv.close()

finally:
 import shutil
 shutil.rmtree(work)
