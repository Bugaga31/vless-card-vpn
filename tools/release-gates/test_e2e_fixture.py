import hashlib
import json
from pathlib import Path
import ssl
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]
class FixturePin(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name)
        subprocess.run(['openssl','req','-x509','-newkey','ec','-pkeyopt','ec_paramgen_curve:prime256v1','-nodes','-keyout',str(self.root/'key.pem'),'-out',str(self.root/'c.pem'),'-days','1','-subj','/CN=test.local'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        subprocess.run(['python3',str(ROOT/'tools/e2e/make_state.py'),str(self.root),'fixture-public-key'],check=True)
    def test_fixture_pins_exact_generated_certificate_before_dns_or_network(self):
        expected=hashlib.sha256(ssl.PEM_cert_to_DER_cert((self.root/'c.pem').read_text())).hexdigest()
        for name in ['single','multi','ownmask','auto-eight']:
            for server in json.loads((self.root/(name+'.json')).read_text())['servers']:
                if server.get('security')=='tls':self.assertEqual(expected,server.get('pcs'),name)
    def test_eight_routes_keep_distinct_aliases_sni_and_authenticated_users(self):
        servers=json.loads((self.root/'auto-eight.json').read_text())['servers']
        self.assertEqual(8,len(servers))
        for field in ['address','sni','secret']:
            self.assertEqual(8,len({s[field] for s in servers}))
        self.assertEqual({f'r163-pool-{i}.test' for i in range(8)},{s['address'] for s in servers})
        clients=json.loads((ROOT/'tools/e2e/server.json').read_text())['inbounds'][1]['settings']['clients']
        self.assertTrue({s['secret'] for s in servers} <= {c['id'] for c in clients})
    def test_engine_fixtures_reference_registered_current_dpi_masks(self):
        source=(ROOT/'app/src/main/java/com/vlesscardvpn/xray/Masks.kt').read_text()
        self.assertIn('listOf("n", "без дробления"',source)
        self.assertIn('if (via) ".b" else ""',source)
        for name, engine in [('tpws','TPWS#SPLIT_DISORDER'),('xraydpi','XRAY#LADDER')]:
            state=json.loads((self.root/(name+'.json')).read_text())
            self.assertEqual({'e2e-reality':'chrome.n.b'},state['e2e_masks'])
            self.assertEqual(engine,state['settings']['dpiStrategy'])
            self.assertEqual(['e2e-reality'],state['e2e_select'])
    def test_optional_optimization_fixture_explicitly_allows_work_during_probe_traffic(self):
        for name in ('multi','auto-eight'):
            state=json.loads((self.root/(name+'.json')).read_text())
            self.assertIs(state['settings']['protectActiveTraffic'],False)
        auto=json.loads((self.root/'auto-eight.json').read_text())
        self.assertIs(auto['settings']['autoChainSearch'],False)
        separate=json.loads((self.root/'auto-chain.json').read_text())
        self.assertIs(separate['settings']['autoChainSearch'],True)
if __name__=='__main__':unittest.main()
