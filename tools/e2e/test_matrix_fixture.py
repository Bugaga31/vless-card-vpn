"""Fast guardrails for the host matrix; these do not replace live tunnel tests."""
import json
from pathlib import Path
import re
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]


class MatrixFixtureTest(unittest.TestCase):
    def setUp(self):
        self.script = (ROOT / 'tools/masks-matrix-ci.sh').read_text()
        self.server = json.loads((ROOT / 'tools/e2e/matrix-server.json').read_text())

    def test_gradle_filters_match_existing_test_classes(self):
        classes = set()
        for path in (ROOT / 'app/src/test').rglob('*.kt'):
            text = path.read_text()
            package = re.search(r'^package\s+([\w.]+)', text, re.M)
            if package:
                classes.update(package[1] + '.' + name for name in re.findall(r'\bclass\s+(\w+)', text))
        filters = re.findall(r"--tests '([^']+)'", self.script)
        self.assertIn('com.vlesscardvpn.CryptoWarpRegressionR62Test', filters)
        self.assertTrue(filters)
        for name in filters:
            self.assertIn(name, classes)
        self.assertIn('--rerun-tasks', self.script)

    def test_wireguard_redirect_is_scoped_to_fixture(self):
        wg = next(i for i in self.server['inbounds'] if i['protocol'] == 'wireguard')
        self.assertEqual('127.0.0.1', wg['listen'])
        rule = next(r for r in self.server['routing']['rules'] if r['outboundTag'] == 'matrix-wg-origin')
        self.assertEqual([wg['tag']], rule['inboundTag'])
        self.assertEqual(['10.0.0.1/32'], rule['ip'])
        self.assertEqual('tcp', rule['network'])
        self.assertEqual('18443', rule['port'])
        outbound = next(o for o in self.server['outbounds'] if o.get('tag') == 'matrix-wg-origin')
        self.assertEqual('127.0.0.1:18443', outbound['settings']['redirect'])
        self.assertEqual([{'action': 'allow', 'network': 'tcp', 'ip': ['127.0.0.1/32'], 'port': '18443'}], outbound['settings']['finalRules'])
        self.assertIn('IP:10.0.0.1', self.script)
        self.assertIn("os.environ['MATRIX_WG_CHECK_URL']", self.script)
        self.assertIn("os.environ['MATRIX_WG_DOWNLOAD_URL']", self.script)

    def test_manifest_without_final_newline_is_complete(self):
        result = subprocess.run(['bash', '-c', 'n=0; while IFS= read -r id || [ -n "$id" ]; do [ -n "$id" ] && n=$((n+1)); done; echo "$n"'], input='plain\nlast-mask', text=True, capture_output=True, check=True)
        self.assertEqual('2', result.stdout.strip())
        self.assertIn('while IFS= read -r id || [ -n "$id" ]; do', self.script)
        self.assertIn('[ "$WALL" -gt 0 ]', self.script)
        self.assertIn('(WALL * 8 + 9) / 10', self.script)

    def test_release_publication_requires_live_quality_gate(self):
        release = (ROOT / '.github/workflows/beta-release.yml').read_text()
        e2e = (ROOT / '.github/workflows/e2e.yml').read_text()
        self.assertIn('uses: ./.github/workflows/e2e.yml', release)
        self.assertIn('needs: [build, emulator-install, quality]', release)
        self.assertIn('  workflow_call:', e2e)
        self.assertIn("'!release-build-*'", e2e)

    def test_generation_preflight_precedes_live_matrix(self):
        self.assertLess(self.script.index('Missing generated matrix fixture'), self.script.index('KEEP_CLIENT=1'))
        self.assertIn("if wall == 0 or wok != wall:", self.script)
        self.assertIn('Plain WireGuard fixture failed', self.script)


if __name__ == '__main__':
    unittest.main()
