import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT=Path(__file__).resolve().parents[1]/'e2e/tor-native-smoke.sh'
FAKE_ADB=r'''#!/usr/bin/env python3
import os,sys
s=' '.join(sys.argv[1:]);stage=os.environ.get('FAIL_STAGE','')
if s.startswith('shell pm path '):print('package:/data/app/private-id/base.apk')
elif s.startswith('shell dumpsys package '):print('nativeLibraryDir=/data/app/private-id/lib/x86_64')
elif ' test -x ' in s:
 if stage=='locate':sys.exit(1)
elif '--version' in s:
 if stage=='version':print('error opening /data/user/0/private/secret at 192.0.2.1');sys.exit(7)
 print('Tor version fixture')
elif '--verify-config' in s:
 if stage=='config':print('[err] Failed /data/user/0/private/config at 192.0.2.1 AABBCCDDEEFF00112233445566778899');sys.exit(9)
 print('Configuration was valid')
'''

class TorSmokeDiagnostics(unittest.TestCase):
    def run_smoke(self, failure=''):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);bin=root/'bin';bin.mkdir()
            adb=bin/'adb';adb.write_text(FAKE_ADB);adb.chmod(0o755)
            configs=root/'app/build/tor-verify-configs';configs.mkdir(parents=True)
            for mode in ['none','obfs4','webtunnel']:(configs/(mode+'.conf')).write_text('DisableNetwork 1\n')
            env=dict(os.environ,PATH=str(bin)+os.pathsep+os.environ['PATH'],FAIL_STAGE=failure,TOR_SMOKE_OUTPUT_DIR=str(root/'logs'))
            return subprocess.run(['bash',str(SCRIPT)],cwd=root,env=env,text=True,capture_output=True,timeout=15)
    def test_success_keeps_all_three_checks(self):
        r=self.run_smoke();self.assertEqual(r.returncode,0,r.stderr);self.assertEqual(r.stdout.count('PASS (offline; no bootstrap claim)'),3)
    def test_missing_native_directory_fails_with_stage(self):
        r=self.run_smoke('locate');self.assertNotEqual(r.returncode,0);self.assertIn('stage=resolve-native-library',r.stdout)
    def test_binary_failure_not_hidden(self):
        r=self.run_smoke('version');self.assertEqual(r.returncode,7);self.assertIn('stage=execute-version; exit=7',r.stdout)
    def test_config_failure_anonymized_and_nonzero(self):
        r=self.run_smoke('config');self.assertEqual(r.returncode,9)
        annotation=next(x for x in r.stdout.splitlines() if x.startswith('::error'))
        self.assertIn('stage=verify-none-config; exit=9',annotation)
        self.assertNotIn('/data/user',annotation);self.assertNotIn('192.0.2.1',annotation)
        self.assertNotIn('AABBCCDDEEFF00112233445566778899',annotation)

if __name__=='__main__':unittest.main()
