import copy
import json
import pathlib
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import cloud_state as state
import cloud_real
import cloud_reports
import cloud_masks


class CloudContinuityTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = pathlib.Path(self.tmp.name)
        self.source = self.root / 'source'
        self.source.mkdir()
        subprocess.run(['git', 'init', '-q', str(self.source)], check=True)
        for name in state.STATE_FILES:
            (self.source / name).write_text(json.dumps({'sentinel': {'generation': 7}}))
        subprocess.run(['git', '-C', str(self.source), 'add', '.'], check=True)
        subprocess.run(['git', '-C', str(self.source), '-c', 'user.name=Fixture',
                        '-c', 'user.email=fixture@example.test', 'commit', '-qm', 'fixture'], check=True)
        self.prior = self.root / 'prior'

    def freeze(self):
        return state.freeze(self.source, self.prior)

    def test_single_revision_and_all_hashes(self):
        m = self.freeze()
        self.assertEqual(40, len(m['revision']))
        self.assertEqual(set(state.STATE_FILES), set(m['sha256']))
        for name in state.STATE_FILES:
            self.assertEqual({'sentinel': {'generation': 7}}, state.prior(name, self.prior))

    def test_missing_source_aborts_before_creating_snapshot(self):
        (self.source / 'mask-stats.json').unlink()
        with self.assertRaises(ValueError): self.freeze()
        self.assertFalse(self.prior.exists())

    def test_corrupt_source_never_becomes_empty_state(self):
        (self.source / 'history.json').write_text('{bad')
        with self.assertRaises(ValueError): self.freeze()
        self.assertFalse(self.prior.exists())

    def test_symlink_prior_rejected(self):
        (self.source / 'history.json').unlink()
        (self.source / 'history.json').symlink_to(self.source / 'cloud_state.json')
        with self.assertRaises(ValueError): self.freeze()

    def test_oversized_prior_rejected(self):
        with patch.object(state, 'LIMIT', 2):
            with self.assertRaises(ValueError): self.freeze()

    def test_non_object_prior_rejected(self):
        (self.source / 'history.json').write_text('[]')
        with self.assertRaises(ValueError): self.freeze()

    def test_tampering_detected(self):
        self.freeze()
        (self.prior / 'history.json').write_text('{}')
        with self.assertRaises(ValueError): state.prior('history.json', self.prior)

    def test_missing_snapshot_does_not_reset_history(self):
        with patch.dict('os.environ', {'CLOUD_PRIOR_DIR': str(self.prior)}):
            with self.assertRaises(FileNotFoundError): cloud_real.load_history()

    def test_history_loader_reads_frozen_prior_without_network(self):
        self.freeze()
        with patch.dict('os.environ', {'CLOUD_PRIOR_DIR': str(self.prior)}), \
                patch('urllib.request.urlopen', side_effect=AssertionError('network forbidden')):
            self.assertEqual(7, cloud_real.load_history()['sentinel']['generation'])

    def test_repeated_generations_preserve_history_not_apk_version(self):
        self.freeze()
        value = state.prior('history.json', self.prior)
        value['sentinel']['generation'] += 1
        (self.source / 'history.json').write_text(json.dumps(value))
        other = self.root / 'second'
        state.freeze(self.source, other)
        self.assertEqual(8, state.prior('history.json', other)['sentinel']['generation'])
        self.assertEqual(7, state.prior('history.json', self.prior)['sentinel']['generation'])

    def test_old_failed_measurements_not_promoted(self):
        h = {'x': {'ok': 5, 'n': 5, 'ms': 100, 't': 1000, 'samples': [[1000, True]],
                   'kb': 50000, 'kb_t': 1000, 'kb_isolated': True}}
        cloud_real.learn(h, {'x': 0}, 1100)
        self.assertEqual(0, cloud_real.measured_kbps(h['x'], 1100))
        self.assertEqual([], cloud_masks.measured_hints({}, 1100))

    def test_stage_preserves_other_branch_files_excludes_internal_queue(self):
        self.freeze()
        output, dest = self.root / 'out', self.root / 'dest'
        output.mkdir(); dest.mkdir()
        (output / 'prior').mkdir()
        (output / 'prior' / 'snapshot.json').write_bytes((self.prior / 'snapshot.json').read_bytes())
        for name in state.OUTPUTS:
            (output / name).write_text('{}' if name.endswith('.json') else 'fixture')
        (output / 'check-queue.json').write_text('not-public')
        (output / 'report-acks.json').write_text('[123]')
        (dest / 'historical-summary.txt').write_text('keep')
        state.stage_outputs(output, dest)
        self.assertEqual('keep', (dest / 'historical-summary.txt').read_text())
        self.assertFalse((dest / 'report-acks.json').exists())
        self.assertFalse((dest / 'check-queue.json').exists())
        receipt = json.loads((dest / 'continuity.json').read_text())
        self.assertEqual(state.freeze.__module__, 'cloud_state')
        self.assertEqual(40, len(receipt['previous_revision']))
        self.assertNotIn('sentinel', json.dumps(receipt))

    def test_missing_output_cannot_partially_replace_branch(self):
        output, dest = self.root / 'out', self.root / 'dest'
        output.mkdir(); dest.mkdir()
        (dest / 'history.json').write_text('old')
        with self.assertRaises(ValueError): state.stage_outputs(output, dest)
        self.assertEqual('old', (dest / 'history.json').read_text())

    def test_invalid_ack_id_cannot_call_api(self):
        with patch.object(cloud_reports, 'api') as api:
            with self.assertRaises(ValueError): cloud_reports.acknowledge(['1'])
            api.assert_not_called()

    def test_ack_failure_keeps_report_open_and_does_not_leak_error(self):
        with patch.object(cloud_reports, 'api', side_effect=RuntimeError('sensitive')), \
                patch('builtins.print') as output:
            cloud_reports.acknowledge([1])
            self.assertNotIn('sensitive', str(output.call_args))

    def test_workflow_no_force_push_or_silent_merge_failure(self):
        text = pathlib.Path('.github/workflows/cloud-servers.yml').read_text()
        self.assertNotIn('git init', text)
        self.assertNotIn('push -qf', text)
        self.assertNotIn('|| echo', text)
        self.assertIn('test "$(git rev-parse FETCH_HEAD)" = "$base"', text)
        self.assertIn("if: github.ref == 'refs/heads/main'", text)
        self.assertLess(text.index('push origin HEAD:servers'), text.index('scripts/cloud_reports.py --ack'))


if __name__ == '__main__': unittest.main()
