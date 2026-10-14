import pathlib,tempfile,subprocess,json,shutil,unittest,struct,sys
class NativeCacheTest(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.r=pathlib.Path(self.tmp.name);(self.r/'native').mkdir();(self.r/'ndk').mkdir()
  (self.r/'ndk/source.properties').write_text('Pkg.Revision=27.2.12479018')
  shutil.copy(pathlib.Path(__file__).resolve().parents[1]/'native/cached-build.py',self.r/'native/cached-build.py')
  self.build=self.r/'native/build-hev.sh';self.build.write_text('''#!/bin/sh
python3 - "$2" "$(dirname "$0")/../counter" <<'PYTEST'
import pathlib,sys,struct
out=pathlib.Path(sys.argv[1]);count=pathlib.Path(sys.argv[2]);count.write_text(str(int(count.read_text())+1) if count.exists() else '1')
for abi,machine,bits in [('armeabi-v7a',40,1),('arm64-v8a',183,2),('x86',3,1),('x86_64',62,2)]:
 p=out/abi/'libhev-socks5-tunnel.so';p.parent.mkdir(parents=True,exist_ok=True)
 data=bytearray(32);data[:4]=b'\\x7fELF';data[4]=bits;data[5]=1;data[18:20]=struct.pack('<H',machine);p.write_bytes(data)
PYTEST
''')
 def tearDown(self):self.tmp.cleanup()
 def run_cache(self):subprocess.run([sys.executable,str(self.r/'native/cached-build.py'),'build-hev.sh',str(self.r/'ndk'),str(self.r/'out'),'all'],check=True,stdout=subprocess.DEVNULL)
 def test_verified_hit_skips_compilation(self):
  self.run_cache();self.run_cache();self.assertEqual('1',(self.r/'counter').read_text())
 def test_source_change_invalidates_cache(self):
  self.run_cache();self.build.write_text(self.build.read_text()+'\n# changed flags\n');self.run_cache();self.assertEqual('2',(self.r/'counter').read_text())
 def test_corrupted_so_is_rebuilt(self):
  self.run_cache();(self.r/'out/x86_64/libhev-socks5-tunnel.so').write_bytes(b'bad');self.run_cache();self.assertEqual('2',(self.r/'counter').read_text())
 def test_abi_mismatch_is_rebuilt_even_with_matching_digest(self):
  import hashlib
  self.run_cache();p=self.r/'out/x86_64/libhev-socks5-tunnel.so';d=bytearray(p.read_bytes());d[18:20]=struct.pack('<H',183);p.write_bytes(d)
  m=self.r/'out/native-cache.json';v=json.loads(m.read_text());v['files']['x86_64/libhev-socks5-tunnel.so']=hashlib.sha256(d).hexdigest();m.write_text(json.dumps(v))
  self.run_cache();self.assertEqual('2',(self.r/'counter').read_text())
if __name__=='__main__':unittest.main()
