"""Evaluator control flow with a fake model: these are NOT inference results."""
import contextlib
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import evaluate


class EvaluatorTests(unittest.TestCase):
    def run_case(self, source, topics, token_count=10):
        calls = []
        class FakeModel:
            def __init__(self, **kwargs): pass
            def tokenize(self, text): return [0] * token_count
            def create_chat_completion(self, **kwargs):
                calls.append(kwargs)
                return {'choices': [{'message': {'content': '{"status":"insufficient","quotes":[]}'}, 'finish_reason': 'stop'}]}
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root/'model').write_bytes(b'test fixture, not a model')
            (root/'source').write_text(source)
            case = {'id': 'fixture', 'source_id': 's', 'title': 'Synthetic', 'source_path': str(root/'source'),
                    'question': 'Статусы', 'required_topics': topics}
            (root/'cases').write_text(json.dumps([case]))
            argv = ['evaluate', '--model', str(root/'model'), '--cases', str(root/'cases'), '--output', str(root/'output')]
            with patch.object(sys, 'argv', argv), patch.object(evaluate, 'MODEL_SHA256', hashlib.sha256((root/'model').read_bytes()).hexdigest()), patch.dict(sys.modules, {'llama_cpp': types.SimpleNamespace(Llama=FakeModel)}), patch.object(evaluate.importlib.metadata, 'version', return_value='FAKE-NOT-INFERENCE'), contextlib.redirect_stdout(io.StringIO()):
                evaluate.main()
            return json.loads((root/'output').read_text())['results'][0], calls

    def test_missing_topic_skips_generation(self):
        result, calls = self.run_case('Telegram не проверен.', {'telegram': ['Telegram'], 'youtube': ['YouTube']})
        self.assertEqual(calls, [])
        self.assertFalse(result['generation_performed'])
        self.assertEqual(result['gate']['status'], 'insufficient')
        self.assertEqual(result['retrieval_topic_coverage']['missing'], ['youtube'])

    def test_context_trimming_cannot_hide_missing_topic(self):
        result, calls = self.run_case('Telegram не проверен.\n\nYouTube не проверен.', {'telegram': ['Telegram'], 'youtube': ['YouTube']}, token_count=3001)
        self.assertEqual(calls, [])
        self.assertFalse(result['generation_performed'])
        self.assertEqual(result['gate']['status'], 'insufficient')

    def test_mock_generation_marked_explicitly(self):
        result, calls = self.run_case('Telegram не проверен.', {'telegram': ['Telegram']})
        self.assertEqual(len(calls), 1)
        self.assertTrue(result['generation_performed'])
        self.assertEqual(result['gate']['status'], 'insufficient')
        self.assertFalse(result['gate']['semantic_accuracy_verified'])

    def test_no_evidence_uses_full_fail_closed_contract(self):
        result, calls = self.run_case('Незнакомое предложение.', {})
        self.assertEqual(calls, [])
        self.assertEqual(result['abstention_reason'], 'no evidence')
        self.assertFalse(result['gate']['weight_training_performed'])
        self.assertFalse(result['gate']['actions_executed'])

if __name__ == '__main__': unittest.main()
