import importlib.util
import json
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('matrix_wireguard', ROOT/'tools/e2e/matrix_wireguard.py')
w = importlib.util.module_from_spec(spec); spec.loader.exec_module(w)

class WireGuardFixtureTest(unittest.TestCase):
    def test_fixture_does_not_use_userspace_loopback_as_destination(self):
        before = json.loads((ROOT/'tools/e2e/matrix-server.json').read_text())
        saved = json.dumps(before, sort_keys=True)
        fixed = w.prepare(before)
        self.assertEqual(saved, json.dumps(before, sort_keys=True))
        self.assertEqual('https://192.0.2.2:18443/bytes', w.wireguard_url('https://127.0.0.1:18443/bytes'))
        route = fixed['routing']['rules'][0]
        self.assertEqual(['192.0.2.2/32'], route['ip'])
        self.assertEqual('18443', route['port'])
        self.assertEqual([w.TAG], route['inboundTag'])
        outbound = next(o for o in fixed['outbounds'] if o.get('tag') == w.ORIGIN_TAG)
        self.assertEqual('127.0.0.1:18443', outbound['settings']['redirect'])
        self.assertEqual(w.BLOCK_TAG, fixed['routing']['rules'][1]['outboundTag'])
        self.assertEqual(before['outbounds'][0], fixed['outbounds'][0])

    def test_external_or_unverified_origins_are_rejected(self):
        for url in ('http://127.0.0.1:18443/bytes','https://example.test:18443/bytes','https://127.0.0.1:443/bytes','https://u:p@127.0.0.1:18443/bytes'):
            with self.assertRaises(ValueError): w.wireguard_url(url)

    def test_unexpected_fixture_or_reapplication_fails_closed(self):
        before = json.loads((ROOT/'tools/e2e/matrix-server.json').read_text())
        with self.assertRaises(ValueError): w.prepare(w.prepare(before))
        next(i for i in before['inbounds'] if i['protocol']=='wireguard')['listen']='0.0.0.0'
        with self.assertRaises(ValueError): w.prepare(before)

if __name__ == '__main__': unittest.main()
