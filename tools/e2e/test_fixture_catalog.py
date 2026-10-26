"""Fixture setup regressions; no network calls and no production route results mocked."""
import json
from pathlib import Path
import runpy
import sys
import tempfile
import unittest
from unittest.mock import patch

class FixtureCatalogTest(unittest.TestCase):
    def generate(self, root):
        (root/'c.pem').write_text('unit-test placeholder; DER conversion is separately mocked')
        with patch.object(sys,'argv',['make_state.py',str(root),'fixture-public-key']), patch('ssl.PEM_cert_to_DER_cert',return_value=b'fixture-DER'):
            runpy.run_path(str(Path(__file__).with_name('make_state.py')),run_name='__main__')
        return {p.name:json.loads(p.read_text()) for p in root.glob('*.json')}
    def test_every_case_has_closed_catalog(self):
        with tempfile.TemporaryDirectory() as tmp:
            cases=self.generate(Path(tmp));self.assertGreaterEqual(len(cases),12)
            for name,case in cases.items():
                with self.subTest(name=name):
                    self.assertIs(case['settings']['autoUpdateSubs'],False)
                    self.assertEqual(4102444800000,case['settings']['lastSubRefresh'])
                    self.assertTrue(all(s['address']=='10.0.2.2' or s['address'].endswith('.test') for s in case['servers']))
    def test_manual_selected_endpoints_are_not_removed(self):
        with tempfile.TemporaryDirectory() as tmp:
            cases=self.generate(Path(tmp))
            self.assertEqual(['e2e-reality'],cases['single.json']['e2e_select'])
            self.assertEqual({'e2e-reality','e2e-ws-tls','e2e-xhttp','e2e-dead'},set(cases['multi.json']['e2e_select']))
            self.assertEqual('ROUND_ROBIN',cases['multi.json']['settings']['balance'])
    def test_real_auto_chain_checks_still_enabled(self):
        with tempfile.TemporaryDirectory() as tmp:
            s=self.generate(Path(tmp))['auto-chain.json']['settings']
            self.assertEqual('AUTO',s['mode']);self.assertTrue(s['autoChainSearch']);self.assertFalse(s['torAuto'])

    def test_large_manual_check_fixture_is_deterministic(self):
        with tempfile.TemporaryDirectory() as tmp:
            mass=self.generate(Path(tmp))['mass-check.json']
            self.assertEqual(128,len(mass['servers']))
            self.assertEqual(8443,mass['servers'][0]['port'])
            self.assertEqual(list(range(18001,18128)),[s['port'] for s in mass['servers'][1:]])
            self.assertEqual([],mass['e2e_select'])

if __name__=='__main__':unittest.main()
