"""Fixture contract checks, not public-service availability measurements."""
import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('service_fixture', Path(__file__).with_name('service_fixture.py'))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)

class ServiceFixtureTest(unittest.TestCase):
    def test_mtproto_frame_contains_fresh_request_nonce(self):
        nonce = bytes(range(16))
        response = fixture.response(nonce)
        self.assertEqual(21, response[0])
        frame = response[1:]
        self.assertEqual(84, len(frame))
        self.assertEqual(0, struct.unpack_from('<Q', frame)[0])
        self.assertEqual(len(frame)-20, struct.unpack_from('<I', frame,16)[0])
        self.assertEqual(0x05162463, struct.unpack_from('<I', frame,20)[0])
        self.assertEqual(nonce,frame[24:40])
        with self.assertRaises(ValueError): fixture.response(b'bad')

    def test_host_redirects_are_narrow_and_keep_existing_routes(self):
        original = json.loads((ROOT / 'tools/e2e/server.json').read_text())
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / 'server.json'; path.write_text(json.dumps(original)); fixture.patch(path)
            patched = json.loads(path.read_text())
        self.assertEqual(original['inbounds'],patched['inbounds'])
        self.assertEqual(original['routing']['rules'],patched['routing']['rules'][3:])
        self.assertEqual(['full:fixture.googlevideo.com'],patched['routing']['rules'][0]['domain'])
        self.assertEqual(['full:cachefly.cachefly.net','full:proof.ovh.net'],patched['routing']['rules'][2]['domain'])
        self.assertEqual('443',patched['routing']['rules'][1]['port'])
        self.assertEqual('tcp',patched['routing']['rules'][1]['network'])
        for tag, port in [('qa-speed',18444),('qa-https-fixture',18444),('qa-youtube',18444),('qa-youtube-media',18444),('qa-mtproto-fixture',18445)]:
            settings = next(o['settings'] for o in patched['outbounds'] if o['tag'] == tag)
            self.assertEqual(f'127.0.0.1:{port}', settings['redirect'])
            self.assertEqual([{'action':'allow','network':'tcp','port':str(port),'ip':['127.0.0.1/32']}],settings['finalRules'])
        self.assertNotIn('finalRules',next(o for o in patched['outbounds'] if o['tag']=='qa-other').get('settings',{}))

    def test_live_warp_failure_retains_diagnostics_before_the_next_case(self):
        script = (ROOT / 'tools/e2e-android.sh').read_text()
        warp = script[script.index('echo "=== case warp'):script.index('echo "=== DPI strategy')]
        self.assertIn('okprobe "$R" || fail "warp: probe app traffic through WARP failed"',warp)
        self.assertIn('else fail "warp: no working WARP endpoint"; fi',warp)
        self.assertLess(warp.index('warp-registration.log'),warp.index('  adb logcat -c'))
        self.assertGreater(warp.index('save_case warp'),warp.index('else fail'))
        self.assertIn('exit $FAILS',script)
        self.assertIn('save_case auto-eight',script)
        self.assertIn('for round in 1 2 3; do',script)
        self.assertIn('ownmask: server :$p not reached through own masking',script)

    def test_trust_anchor_is_generated_only_in_debug_and_never_in_release(self):
        self.assertNotIn('networkSecurityConfig',(ROOT / 'app/src/main/AndroidManifest.xml').read_text())
        workflow = (ROOT / '.github/workflows/beta-release.yml').read_text()
        self.assertNotIn('service_fixture.py prepare',workflow)
        source = Path(__file__).with_name('service_fixture.py').read_text()
        self.assertNotIn('includeSubdomains="true"',source)
        self.assertIn("'src/debug'",source)
        self.assertIn('vpn-test-probe',source)
        self.assertNotIn('networkSecurityConfig',(ROOT / 'vpn-test-probe/src/main/AndroidManifest.xml').read_text())
        self.assertNotIn('ssl.CERT_NONE',source)
        self.assertNotIn('check_hostname = False',source)
        self.assertEqual(['www.youtube.com','fixture.googlevideo.com'] + ['speed.cloudflare.com','cachefly.cachefly.net','proof.ovh.net'],fixture.HOSTS)
        self.assertEqual(5,len(set(fixture.HOSTS)))

if __name__ == '__main__': unittest.main()
