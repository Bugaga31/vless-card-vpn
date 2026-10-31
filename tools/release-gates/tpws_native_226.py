#!/usr/bin/env python3
"""Real tpws + local certificate-verified TLS fixture. Not an operator/DPI or TUN test."""
import argparse,hashlib,json,os,shlex,socket,ssl,struct,subprocess,tempfile,threading,time
from pathlib import Path
P=argparse.ArgumentParser();P.add_argument('--binary');P.add_argument('--adb',action='store_true');P.add_argument('--out',default='tpws-native-226');a=P.parse_args()
out=Path(a.out);out.mkdir(parents=True,exist_ok=True)
PKG='com.vlesscardvpn.beta2'
profiles=[('control',[]),('hello-record',['--tlsrec=sniext+1','--split-pos=1,midsld','--tamper-cutoff=n2']),('hello-multisplit',['--split-pos=1,host,midsld,endhost','--disorder=tls','--tamper-cutoff=n2']),('hello-mid-record',['--tlsrec=midsld','--split-pos=1','--tamper-cutoff=n2'])]
# Keep the fixture tied to the actual production catalog, not a parallel recipe.
import re
catalog=Path("app/src/main/java/com/vlesscardvpn/core/Dpi.kt").read_text()
actual=[]
for row in catalog.splitlines():
 if 'tpws("HELLO_' in row:
  parts=re.findall(r'"([^"\\]*(?:\\.[^"\\]*)*)"',row)
  actual.append(parts[2:])
assert actual==[args for _,args in profiles[1:]],"Native fixture and production profiles diverged"
def cmd(*args):return subprocess.check_output(args,stderr=subprocess.STDOUT).decode().strip()
def exact(s,n):
 b=b''
 while len(b)<n:
  c=s.recv(n-len(b));assert c,'Unexpected EOF';b+=c
 return b
def records(data):
 p=0;chunks=[]
 while p<len(data):
  assert len(data)-p>=5
  n=int.from_bytes(data[p+3:p+5],'big');assert p+5+n<=len(data)
  assert data[p]==22,'Expected handshake record'
  chunks.append(data[p+5:p+5+n]);p+=5+n
 return chunks
with tempfile.TemporaryDirectory() as td:
 td=Path(td);key=td/'key.pem';cert=td/'cert.pem'
 subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(key),'-out',str(cert),'-days','1','-subj','/CN=fixture.example','-addext','subjectAltName=DNS:fixture.example'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 serverctx=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);serverctx.load_cert_chain(cert,key)
 clientctx=ssl.create_default_context(cafile=str(cert));rows=[]
 if a.adb:
  cmd('adb','wait-for-device');cmd('adb','install','-r','-g','app/build/outputs/apk/debug/app-universal-debug.apk')
  text=cmd('adb','shell','pm','path',PKG)
  apkpaths=[line.removeprefix('package:') for line in text.splitlines() if line.startswith('package:')]
  assert len(apkpaths)==1 and apkpaths[0].endswith('/base.apk'),'Unexpected installed APK paths'
  # API33 dumpsys can omit nativeLibraryDir. Inspect the actual extracted package
  # library subtree instead of inferring an ABI directory or copying a host binary.
  nativeRoot=str(Path(apkpaths[0]).parent/'lib')
  matches=cmd('adb','shell','run-as',PKG,'find',nativeRoot,'-name','libtpws.so').splitlines()
  assert len(matches)==1 and matches[0].startswith(nativeRoot+'/'),'Missing/ambiguous installed tpws: '+repr(matches)
  lib=matches[0]
  cmd('adb','shell','run-as',PKG,'test','-x',lib)
  print('Using actual installed native library:',lib,flush=True)
 for i,(name,args) in enumerate(profiles):
  listener=socket.socket();listener.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);listener.bind(('0.0.0.0' if a.adb else '127.0.0.1',0));listener.listen();listener.settimeout(15);port=listener.getsockname()[1]
  state={};err=[]
  def serve():
   try:
    with listener.accept()[0] as conn:
     conn.settimeout(12);raw=b'';payload=b'';count=0
     while len(payload)<4 or len(payload)<4+int.from_bytes(payload[1:4],'big'):
      h=exact(conn,5);assert h[0]==22;body=exact(conn,int.from_bytes(h[3:5],'big'));raw+=h+body;payload+=body;count+=1
     state.update(payload=payload,count=count)
     bi,bo=ssl.MemoryBIO(),ssl.MemoryBIO();tls=serverctx.wrap_bio(bi,bo,server_side=True);bi.write(raw)
     while True:
      try:tls.do_handshake();done=True
      except ssl.SSLWantReadError:done=False
      data=bo.read()
      if data:conn.sendall(data)
      if done:break
      bi.write(conn.recv(65536))
     req=b''
     while b'\r\n\r\n' not in req:
      try:req+=tls.read(4096)
      except ssl.SSLWantReadError:bi.write(conn.recv(65536))
      data=bo.read()
      if data:conn.sendall(data)
     assert req==b'GET /fixture HTTP/1.1\r\nHost: fixture.example\r\nConnection: close\r\n\r\n'
     tls.write(b'HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n');conn.sendall(bo.read());state['https']=True
   except BaseException as e:err.append(repr(e))
   finally:listener.close()
  thread=threading.Thread(target=serve,daemon=True);thread.start()
  proxyport=19090+i;log=open(out/(name+'.log'),'wb');env=dict(os.environ,VCVPN_TPWS_ALLOW_LOCAL='1')
  base=['--socks','--bind-addr=127.0.0.1',f'--port={proxyport}','--maxconn=8']
  if a.adb:
   cmd('adb','forward',f'tcp:{proxyport}',f'tcp:{proxyport}')
   # Android install paths contain '='. env misreads the executable as an
   # assignment; shell exec preserves the actual package executable and argv.
   remote=shlex.join(['run-as',PKG,'sh','-c','export VCVPN_TPWS_ALLOW_LOCAL=1; exec "$@"','tpws-fixture',lib,*base,*args])
   proc=subprocess.Popen(['adb','shell',remote],stdout=log,stderr=log)
  else:
   assert a.binary;proc=subprocess.Popen([a.binary,*base,*args],env=env,stdout=log,stderr=log)
  try:
   sock=None
   for k in range(50):
    if proc.poll() is not None:raise AssertionError('Native process exited: '+name)
    try:sock=socket.create_connection(('127.0.0.1',proxyport),timeout=1);sock.sendall(b'\x05\x01\x00');assert exact(sock,2)==b'\x05\x00';break
    except (OSError,AssertionError):
     if sock:sock.close()
     sock=None;time.sleep(.2)
   assert sock is not None,'SOCKS unavailable'
   sock.settimeout(12);host='10.0.2.2' if a.adb else '127.0.0.1';sock.sendall(b'\x05\x01\x00\x01'+socket.inet_aton(host)+struct.pack('!H',port));reply=exact(sock,4);assert reply[:2]==b'\x05\x00';assert reply[3]==1;exact(sock,6)
   bi,bo=ssl.MemoryBIO(),ssl.MemoryBIO();tls=clientctx.wrap_bio(bi,bo,server_side=False,server_hostname='fixture.example')
   try:tls.do_handshake()
   except ssl.SSLWantReadError:pass
   hello=bo.read();original=b''.join(records(hello));sock.sendall(hello)
   while True:
    try:tls.do_handshake();done=True
    except ssl.SSLWantReadError:done=False
    data=bo.read()
    if data:sock.sendall(data)
    if done:break
    bi.write(sock.recv(65536))
   tls.write(b'GET /fixture HTTP/1.1\r\nHost: fixture.example\r\nConnection: close\r\n\r\n');sock.sendall(bo.read());response=b''
   while b'\r\n\r\n' not in response:
    try:response+=tls.read(4096)
    except ssl.SSLWantReadError:bi.write(sock.recv(65536))
   assert response.startswith(b'HTTP/1.1 204');sock.close();thread.join(15);assert not thread.is_alive();assert not err,err
   assert state['payload']==original,'ClientHello identity/payload changed'
   expected=2 if any(x.startswith('--tlsrec=') for x in args) else 1
   assert state['count']==expected,(name,state['count'],expected)
   rows.append({'profile':name,'https_204':True,'clienthello_payload_unchanged':True,'tls_records':state['count'],'hello_sha256':hashlib.sha256(original).hexdigest()})
  finally:
   if a.adb:
    subprocess.run(['adb','shell','run-as',PKG,'pkill','-x','libtpws.so'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    cmd('adb','forward','--remove',f'tcp:{proxyport}')
   proc.terminate()
   try:proc.wait(timeout=4)
   except subprocess.TimeoutExpired:proc.kill();proc.wait()
   log.close()
 summary={'status':'PASS','platform':'android-api33-x86_64' if a.adb else 'linux-host','cases':rows,'scope':'local certificate-verified TLS through real bundled tpws SOCKS; exact ClientHello payload and TLS-record framing; not packet capture/TUN/operator/phone/DPI efficacy'}
 (out/'summary.json').write_text(json.dumps(summary,indent=2));print(json.dumps(summary))
