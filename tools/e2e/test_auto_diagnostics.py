import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('auto_diagnostics', Path(__file__).with_name('auto_diagnostics.py'))
v = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v)


class AutoDiagnosticTest(unittest.TestCase):
    def test_fixed_events(self):
        s = v.summarize('I E2E: auto-verify event=admitted hosts=2\nI E2E: auto-verify event=cancelled')
        self.assertEqual(s['admitted_two_hosts'], 1)
        self.assertEqual(s['cancelled'], 1)

    def test_fixed_reason(self):
        self.assertEqual(v.summarize('I E2E: auto-verify event=discarded reason=context-changed')['context-changed'], 1)

    def test_unknown_or_injected_reason_is_not_echoed(self):
        s = v.summarize('auto-verify event=discarded reason=secret\nauto-verify event=failed https://secret.invalid/uuid')
        self.assertEqual(sum(s.values()), 0)
        self.assertNotIn('secret', json.dumps(s))

    def test_raw_probe_details_never_leave_summary(self):
        s = v.summarize('check ok=false error=private-address secret-uuid\ncheck ok=true ms=5')
        self.assertEqual(s['checks_failed'], 1)
        self.assertEqual(s['checks_passed'], 1)
        self.assertNotIn('private', json.dumps(s))

    def test_missing_or_one_host_is_not_two_host_admission(self):
        for text in ('', 'auto-verify event=admitted hosts=1', 'auto-verify event=admitted hosts=9'):
            self.assertEqual(v.summarize(text)['admitted_two_hosts'], 0)

    def test_batch_bound(self):
        s = v.summarize('auto-fast catalog=8 candidates=8\nauto-fast catalog=88 candidates=88')
        self.assertEqual(s['batch_count'], 1)


if __name__ == '__main__':
    unittest.main()
