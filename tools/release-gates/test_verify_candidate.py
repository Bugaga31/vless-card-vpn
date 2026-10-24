import importlib.util
from pathlib import Path
import tempfile
import unittest
s = importlib.util.spec_from_file_location('verify_candidate', Path(__file__).with_name('verify_candidate.py'))
v = importlib.util.module_from_spec(s); s.loader.exec_module(v)

class ReleaseGates(unittest.TestCase):
    def totals(self, **kw):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d)/'test.xml'
            attrs = dict(tests=494, failures=0, errors=0, skipped=0); attrs.update(kw)
            p.write_text('<testsuite ' + ' '.join(f'{k}="{x}"' for k,x in attrs.items()) + '/>')
            return v.test_totals([p])
    def test_green_totals(self): self.assertEqual(self.totals()['tests'], 494)
    def test_missing_reports(self):
        with self.assertRaises(RuntimeError): v.test_totals([])
    def test_missing_tests(self):
        with self.assertRaises(RuntimeError): self.totals(tests=410)
    def test_skips_are_not_green(self):
        with self.assertRaises(RuntimeError): self.totals(skipped=1)
    def test_failures_are_not_green(self):
        with self.assertRaises(RuntimeError): self.totals(failures=1)
    def test_wrong_package(self):
        with self.assertRaises(RuntimeError): v.verify_package("name='com.vlesscardvpn' versionCode='162' versionName='1.2.51'")
    def test_wrong_version(self):
        with self.assertRaises(RuntimeError): v.verify_package("name='com.vlesscardvpn.beta2' versionCode='161' versionName='1.2.50'")
    def test_valid_package(self): v.verify_package("name='com.vlesscardvpn.beta2' versionCode='162' versionName='1.2.51'")
    def test_wrong_certificate(self):
        with self.assertRaises(RuntimeError): v.verify_signature('Verifies\nSigner #1 certificate SHA-256 digest: ' + '0'*64)
    def test_all_schemes_and_certificate(self):
        text='Verifies\n'+'\n'.join(f'Verified using v{n} scheme (test): true' for n in (1,2,3))+'\nSigner #1 certificate SHA-256 digest: '+v.CERT
        v.verify_signature(text)
        with self.assertRaises(RuntimeError): v.verify_signature(text.replace('v3 scheme (test): true','v3 scheme (test): false'))
if __name__ == '__main__': unittest.main()
