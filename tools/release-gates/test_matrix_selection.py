import pathlib
import re
import subprocess
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'tools/masks-matrix-ci.sh'

class MatrixSelectionRegressionTest(unittest.TestCase):
    def test_selected_generator_class_exists(self):
        source = (ROOT / 'app/src/test/java/com/vlesscardvpn/security/CryptoWarpRegressionR62Test.kt').read_text()
        class_name = re.search(r'^class (\w+)', source, re.M).group(1)
        script = SCRIPT.read_text()
        self.assertIn("--tests 'com.vlesscardvpn." + class_name + "'", script)
        self.assertNotIn("--tests 'com.vlesscardvpn.R62Test'", script)
        self.assertIn('warp-masks.txt', source)
    def test_last_unterminated_mask_is_processed(self):
        script = SCRIPT.read_text()
        condition = next(x for x in script.splitlines() if x.startswith('while IFS= read -r id'))
        result = subprocess.run(['bash', '-c', condition + '\n printf "%s\\n" "$id"; done'], input='first\nlast', text=True, capture_output=True, check=True)
        self.assertEqual(['first', 'last'], result.stdout.splitlines())
    def test_missing_manifest_fails_before_registration(self):
        script = SCRIPT.read_text()
        self.assertLess(script.index('STAGE=validate-warp-fixtures'), script.index('STAGE=warp-register'))
        line = next(x for x in script.splitlines() if x.startswith('[ -s /tmp/xcfg/warp-masks.txt ]'))
        result = subprocess.run(['bash', '-c', line.replace('/tmp/xcfg/warp-masks.txt', '/definitely-missing-equalizer-fixture')], capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('missing-warp-fixture-manifest', result.stdout)
    def test_wireguard_gate_not_relaxed(self):
        script = SCRIPT.read_text()
        self.assertIn('if wall == 0 or wok != wall:', script)
        self.assertIn('::error title=Matrix bounded WireGuard failure::', script)

if __name__ == '__main__': unittest.main()
