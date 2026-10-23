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

    def test_public_device_summary_has_no_identifiers_or_fake_online(self):
        now = 10_000_000
        cloud = {"ids": {"secret-phone": now, "future": now+1, "old": now-31*86400},
                 "use": {"secret-phone": {"t": now, "s": ["private-server"]}, "old": {"t": now-4*3600}}}
        out = c.device_summary(cloud, now)
        self.assertEqual(1, out["reporting_devices_30d"])
        self.assertEqual(1, out["route_reports_devices_3h"])
        self.assertIsNone(out["online_devices"])
        self.assertFalse(out["apk_intake_enabled"])
        self.assertNotIn("secret-phone", str(out))
        self.assertNotIn("private-server", str(out))

    def test_empty_device_summary_is_not_claimed_as_online(self):
        out = c.device_summary({}, 1000)
        self.assertEqual(0, out["reporting_devices_30d"])
        self.assertEqual(0, out["route_reports_devices_3h"])
        self.assertIsNone(out["online_devices"])

    def test_public_offers_are_sanitized_and_capped(self):
        cloud = {"offers": [{"id": "ok", "title": "t", "text": "x", "link": "https://e", "need": "any"},
                           {"id": "b", "link": "http://no", "need": "any"},
                           {"id": "n", "link": "https://e", "need": "nope"}]}
        pub = c.public(dict(cloud, phones=1, brain={}, profiles={}, load={}, net={}, servers=[]))
        self.assertEqual(1, len(pub["offers"]))
        self.assertEqual("ok", pub["offers"][0]["id"])
        self.assertEqual([], c.public_offers({"offers": "junk"}))


    def test_offers_are_sanitized_and_capped(self):
        ok = {"id": "a", "title": "t", "text": "x", "link": "https://e/x", "need": "yt"}
        bad = [{"id": "", "title": "t", "text": "x", "link": "https://e/x", "need": "any"},
               {"id": "b", "title": "t", "text": "x", "link": "http://e/x", "need": "any"},
               {"id": "c", "title": "t", "text": "x", "link": "https://e/x", "need": "weird"},
               {"id": "d", "title": "t", "text": "x", "link": "https://e/x", "need": "any"},
               ok]
        out = c.public_offers({"offers": bad})
        self.assertEqual(["d", "a"], [o["id"] for o in out])
        self.assertEqual(3, len(c.public_offers({"offers": [ok] * 5})))
        self.assertEqual([], c.public_offers({"offers": "junk"}))


if __name__ == "__main__":
    unittest.main()
