import json
from pathlib import Path
import subprocess
import sys
import unittest
class BoundedDiagnosticsTest(unittest.TestCase):
    def summarize(self,text):
        s=subprocess.check_output([sys.executable,str(Path(__file__).with_name('summarize_failure.py')),'--case','multi'],input=text,text=True)
        return s,json.loads(s.split('::',2)[-1])
    def test_counts_and_state(self):
        _,d=self.summarize('fixture state mode=SERVERS servers=4 selected=4\nfixture connect permission=false\nconnect failed: java.io.IOException: secret\nFATAL EXCEPTION: main\n')
        self.assertEqual([['SERVERS','4','4']],d['reload_states']);self.assertEqual(['false'],d['vpn_permission_required'])
        self.assertEqual(1,d['connect_failed_count']);self.assertEqual(1,d['fatal_exception_count']);self.assertIn('IOException',d['exception_types'])
    def test_sensitive_values_never_echoed(self):
        s,_=self.summarize('secret=top-secret https://private.example/path 203.0.113.7\nconnect failed: token: keep-private\nfixture state mode=EVIL servers=4 selected=4\n')
        for value in ['top-secret','private.example','203.0.113.7','keep-private','EVIL']:self.assertNotIn(value,s)
    def test_empty_diagnostics_remains_valid(self):
        _,d=self.summarize('');self.assertEqual([],d['reload_states']);self.assertEqual(0,d['fatal_exception_count'])
