"""Offline shell wiring only; does not claim an APK/emulator/signing result."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT=Path(__file__).with_name('check-upgrade.sh').resolve()
class UpgradeGateTest(unittest.TestCase):
    def run_gate(self, mode='ok'):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); (root/'release').mkdir(); bin=root/'bin';bin.mkdir()
            commands={
                'curl': '#!/usr/bin/env python3\nimport pathlib,sys\np=pathlib.Path(sys.argv[sys.argv.index("--output")+1]);p.write_text("mock")\n',
                'sha256sum': '#!/usr/bin/env python3\nimport os,sys\ns=sys.stdin.read()\nopen(os.environ["EVENTS"],"a").write("hash "+s)\nsys.exit(1 if os.environ["MODE"]=="hash" else 0)\n',
                'adb': r'''#!/usr/bin/env python3
import os,sys
args=sys.argv[1:];p=os.environ['EVENTS']
with open(p,'a') as f:f.write('adb '+' '.join(args)+'\n')
if args[:2]==['install','-r'] and os.environ['MODE']=='install':
 print('Failure [mock]');sys.exit(1)
if args[:2]==['shell','dumpsys']:
 print('versionCode='+('166' if os.environ['MODE']=='version' else '167'))
elif args and args[0]=='install':print('Success')
'''}
            for name,text in commands.items():
                p=bin/name;p.write_text(text);p.chmod(0o755)
            events=root/'events'
            env=dict(os.environ,PATH=str(bin)+os.pathsep+os.environ['PATH'],VERSION='release-build-167',MODE=mode,EVENTS=str(events))
            result=subprocess.run(['bash',str(SCRIPT)],cwd=root,env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
            return result.returncode,events.read_text() if events.exists() else '',result.stdout
    def test_both_published_versions_are_required(self):
        rc,events,out=self.run_gate();self.assertEqual(0,rc,out)
        self.assertEqual(2,events.count('adb install -r'))
        for version in ('1.2.50','1.2.55'):
            self.assertIn('baseline-'+version+'.apk',events)
            self.assertIn('Published '+version+' -> signed versionCode 167 upgrade: PASS',out)
        self.assertIn('b4a319c1753ae28761cba62e1ea3df40167116c6a946b160643a35dc1afdb7c1',events)
    def test_checksum_failure_stops_before_install(self):
        rc,events,_=self.run_gate('hash');self.assertNotEqual(0,rc);self.assertNotIn('adb install',events)
    def test_failed_upgrade_cannot_continue_or_claim_pass(self):
        rc,events,out=self.run_gate('install');self.assertNotEqual(0,rc);self.assertNotIn('upgrade: PASS',out)
        self.assertEqual(1,events.count('adb install -r'))
    def test_wrong_version_is_failure(self):
        rc,events,out=self.run_gate('version');self.assertNotEqual(0,rc);self.assertNotIn('upgrade: PASS',out)
