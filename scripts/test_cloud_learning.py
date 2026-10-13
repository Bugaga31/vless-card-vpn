import copy
import math
import unittest
import cloud_reports as c

class CloudLearningTest(unittest.TestCase):
    def test_vectors_reject_non_finite_and_wrong_types(self):
        self.assertTrue(c.valid_vector([0.0] * c.N))
        for bad in (math.nan, math.inf, -math.inf, 9, True, "0"):
            self.assertFalse(c.valid_vector([bad] * c.N))
        self.assertFalse(c.valid_vector([0.0]))

    def test_bad_phone_model_does_not_poison_cloud(self):
        report = {"id": "phone", "brain": {"seen": "not-a-number", "m": [math.nan] * c.N}}
        out = c.merge({}, [report], 1000)
        self.assertEqual({}, out["brain"])
        report["brain"] = {"seen": 10, "m": [0.5] * c.N}
        self.assertEqual([0.5] * c.N, c.merge({}, [report], 1000)["brain"]["m"])

    def test_votes_decay_by_time_and_previous_snapshot_is_not_mutated(self):
        prev = {"at": 1000, "votes": {"Wi-Fi": {"d": {"strategy": 8}, "m": {}, "x": {}}}}
        saved = copy.deepcopy(prev)
        self.assertEqual(8, c.merge(prev, [], 1000)["votes"]["Wi-Fi"]["d"]["strategy"])
        self.assertEqual(4, c.merge(prev, [], 1000 + 7 * 86400)["votes"]["Wi-Fi"]["d"]["strategy"])
        self.assertEqual(saved, prev)

if __name__ == "__main__":
    unittest.main()
