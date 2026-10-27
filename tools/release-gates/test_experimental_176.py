import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from prepare_experimental_176 import prepare
from verify_candidate import CERT

class ExperimentalPublication(unittest.TestCase):
    def setUp(self):
        t=tempfile.TemporaryDirectory();self.addCleanup(t.cleanup);self.root=Path(t.name)
        self.docs=self.root/'scope.md';self.docs.write_text('Experimental scope')
        self.report={'candidate_sha':'a'*40,'run_id':'17','jvm':{'tests':812,'failures':0,'errors':0,'skipped':0},'physical_phone_tested':False,'apks':[]}
        for name in ('VLESS-Card-Beta.apk','VLESS-Card-Beta-arm64.apk'):
            data=name.encode();(self.root/name).write_bytes(data)
            self.report['apks'].append({'file':name,'sha256':hashlib.sha256(data).hexdigest(),'certificate_sha256':CERT,'package':"package: name='com.vlesscardvpn.beta2' versionCode='176' versionName='1.2.65'"})
    def run_prepare(self):
        (self.root/'verification.json').write_text(json.dumps(self.report))
        prepare(self.root,'a'*40,'17',self.docs)
    def test_valid_artifacts_are_renamed_and_never_claim_e2e_success(self):
        self.run_prepare();r=json.loads((self.root/'verification.json').read_text())
        self.assertEqual('experimental',r['channel'])
        self.assertEqual('not_run_for_this_sha',r['e2e']['status'])
        self.assertIs(False,r['emulator_install_tested'])
        self.assertIs(True,r['e2e']['not_a_pass'])
        for a in r['apks']:
            self.assertIn('experimental',a['file']);self.assertTrue((self.root/a['file']).is_file())
            self.assertIn(a['sha256'],(self.root/'SHA256SUMS-Beta.txt').read_text())
        notes=(self.root/'notes-experimental.md').read_text()
        self.assertIn('HTTP 429',notes);self.assertIn('НЕ сборка со всеми зелёными',notes)
    def test_wrong_sha_stops(self):
        self.report['candidate_sha']='b'*40
        with self.assertRaises(RuntimeError):self.run_prepare()
    def test_wrong_run_stops(self):
        self.report['run_id']='18'
        with self.assertRaises(RuntimeError):self.run_prepare()
    def test_changed_certificate_stops(self):
        self.report['apks'][0]['certificate_sha256']='0'*64
        with self.assertRaises(RuntimeError):self.run_prepare()
    def test_changed_apk_stops(self):
        (self.root/'VLESS-Card-Beta.apk').write_bytes(b'changed')
        with self.assertRaises(RuntimeError):self.run_prepare()
    def test_failed_or_missing_jvm_report_stops(self):
        for key,value in [('failures',1),('errors',1),('skipped',1),('tests',811)]:
            old=self.report['jvm'][key];self.report['jvm'][key]=value
            with self.assertRaises(RuntimeError):self.run_prepare()
            self.report['jvm'][key]=old
    def test_package_mismatch_stops(self):
        self.report['apks'][0]['package']="package: name='other' versionCode='176' versionName='1.2.65'"
        with self.assertRaises(RuntimeError):self.run_prepare()
    def test_workflow_is_exact_branch_prerelease_only(self):
        w=(Path(__file__).resolve().parents[2]/'.github/workflows/experimental-prerelease-176.yml').read_text()
        self.assertIn("branches: ['experiment/prerelease-176']",w)
        self.assertNotIn('workflow_dispatch:',w);self.assertNotIn('schedule:',w);self.assertNotIn('pull_request',w)
        self.assertNotIn('emulator-install:',w);self.assertNotIn('needs: quality',w)
        self.assertIn('--prerelease --latest=false',w)
        self.assertIn('verify_candidate.py',w)
        self.assertNotIn('gh release edit',w)
        self.assertIn('test "$CURRENT" = "$GITHUB_SHA"',w)
if __name__=='__main__':unittest.main()
