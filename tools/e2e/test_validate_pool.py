import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('validate_pool', Path(__file__).with_name('validate_pool.py'))
pool = importlib.util.module_from_spec(spec); spec.loader.exec_module(pool)

class PoolValidationTest(unittest.TestCase):
    def setUp(self):
        self.fixture = {'servers': [dict(name='e2e-dead' if index == 3 else f'live-{index}', protocol='vless', address='10.0.2.2', port=8443 + index, secret='fixture') for index in range(4)]}
        self.ids = [pool.route_id(server) for server in self.fixture['servers']]
    def line(self, ids):
        return f'pool size={len(ids)} ids=[{", ".join(ids)}]'
    def test_each_legitimate_quality_subset_is_accepted(self):
        for size in (1, 2, 3): self.assertTrue(pool.validate(self.fixture, self.line(self.ids[:size])))
    def test_dead_unknown_empty_and_duplicate_pools_fail(self):
        for ids in ([], [self.ids[3]], [self.ids[0], self.ids[3]], ['unknown'], [self.ids[0], self.ids[0]], self.ids):
            self.assertFalse(pool.validate(self.fixture, self.line(ids)))
    def test_count_mismatch_and_missing_core_evidence_fail(self):
        self.assertFalse(pool.validate(self.fixture, self.line(self.ids[:2]).replace('size=2', 'size=3')))
        self.assertFalse(pool.validate(self.fixture, 'optimize result=true'))
    def test_latest_core_wins_over_historical_good_pool(self):
        self.assertFalse(pool.validate(self.fixture, self.line(self.ids[:2])+'\n'+self.line(self.ids)))
    def test_java_hash_vectors_and_identity_defaults(self):
        self.assertEqual('17862', pool.java_hex('abc'))
        self.assertEqual(pool.route_id(self.fixture['servers'][0]), pool.route_id(dict(self.fixture['servers'][0], network='tcp', security='', flow='')))

if __name__ == '__main__': unittest.main()
