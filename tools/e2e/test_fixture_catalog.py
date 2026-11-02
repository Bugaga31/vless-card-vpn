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

    def test_background_recovery_has_separate_primary_and_spare_ports(self):
        with tempfile.TemporaryDirectory() as tmp:
            c=self.generate(Path(tmp))['background-reserve.json']
            self.assertEqual({8443,8444,8447},{s['port'] for s in c['servers']})
            self.assertEqual('AUTO',c['settings']['mode'])
            self.assertTrue(c['settings']['quietScreen'])
            self.assertTrue(c['settings']['wifiMaintenance'])
            self.assertEqual([],c['settings']['autoChecks'])
            self.assertFalse(c['settings']['autoChainSearch'])

    def test_reserve_wave_fixture_serializes_probes_without_changing_auto_eight(self):
        with tempfile.TemporaryDirectory() as tmp:
            cases=self.generate(Path(tmp))
            wave=cases['reserve-wave.json']; standard=cases['auto-eight.json']
            self.assertEqual(8,len(wave['servers']))
            self.assertEqual(standard['servers'],wave['servers'])
            self.assertEqual(1,wave['settings']['probeParallel'])
            self.assertNotIn('probeParallel',standard['settings'])

    def test_public_cloud_disabled_before_first_app_launch(self):
        script=Path(__file__).parents[1].joinpath('e2e-android.sh').read_text()
        disable=script.index('> shared_prefs/report.xml')
        launch=script.index('adb shell am start -W -n $PKG/.MainActivity')
        self.assertLess(disable,launch)
        self.assertIn('value=',script[:disable])
        self.assertIn('false',script[max(0,disable-150):disable])

    def test_background_proof_cannot_pass_on_foreign_exit_or_after_failure(self):
        script=Path(__file__).parents[1].joinpath('e2e-android.sh').read_text()
        block=script.split('BACKGROUND_FAILS=$FAILS')[1].split('# Production runner')[0]
        self.assertIn('case "$NEXT" in 8443|8444|8447)',block)
        self.assertIn('[ "$FAILS" -eq "$BACKGROUND_FAILS" ]',block)
        self.assertIn('okprobe "$R"',block)
        self.assertIn('replacement server saw no authenticated traffic',block)

if __name__=='__main__':unittest.main()
