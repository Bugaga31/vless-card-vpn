#!/usr/bin/env python3
"""Validate source/NDK/ABI fingerprint and every cached ELF before skipping native compilation."""
import hashlib,json,pathlib,struct,subprocess,sys
root=pathlib.Path(__file__).resolve().parent.parent
script,ndk,out,mode=sys.argv[1:];ndk=pathlib.Path(ndk);out=pathlib.Path(out)
h=hashlib.sha256();h.update((script+'|'+mode+'|android-24|linux').encode())
for p in sorted((root/'native').rglob('*')):
 if p.is_file() and '.git' not in p.parts:
  h.update(str(p.relative_to(root)).encode());h.update(p.read_bytes())
h.update((ndk/'source.properties').read_bytes());key=h.hexdigest()
abis=['armeabi-v7a','arm64-v8a']+([] if mode=='arm' else ['x86','x86_64'])
primary='libbyedpi.so' if 'byedpi' in script else 'libhev-socks5-tunnel.so'
machines={'armeabi-v7a':40,'arm64-v8a':183,'x86':3,'x86_64':62}
manifest=out/'native-cache.json'
def valid():
 try:
  m=json.loads(manifest.read_text())
  if m['key']!=key:return False
  for abi in abis:
   if abi+'/'+primary not in m['files']:return False
  for name,digest in m['files'].items():
   p=out/name
   if p.resolve().parent.parent!=out.resolve():return False
   data=p.read_bytes()
   if data[:4]!=b'\x7fELF' or len(data)<20 or data[5]!=1:return False
   if data[4]!=(2 if p.parent.name in ('arm64-v8a','x86_64') else 1):return False
   if struct.unpack('<H',data[18:20])[0]!=machines[p.parent.name]:return False
   if hashlib.sha256(data).hexdigest()!=digest:return False
  return True
 except (OSError,KeyError,ValueError,TypeError):return False
if valid():print('Validated native cache:',script,mode);sys.exit(0)
subprocess.run(['bash',str(root/'native'/script),str(ndk),str(out),mode],check=True)
files={str(p.relative_to(out)):hashlib.sha256(p.read_bytes()).hexdigest() for p in out.glob('*/*.so')}
manifest.write_text(json.dumps({'key':key,'files':files},sort_keys=True))
if not valid():raise SystemExit('Native output validation failed')
