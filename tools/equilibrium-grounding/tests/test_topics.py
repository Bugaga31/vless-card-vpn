"""Synthetic contract regressions. No LLM generation/semantic accuracy claims."""
import json
from pathlib import Path
import sys
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from grounding import Source, retrieve, validate, Rejected, coverage_report, make_prompt

TOPICS = {'telegram': ['Telegram', 'Телеграм'], 'youtube': ['YouTube', 'Ютуб']}


class TopicTests(unittest.TestCase):
    def setUp(self):
        self.source = Source('status', 'Synthetic only',
            'Telegram HTTPS body проверен.\n\nTelegram HTTPS body повторно проверен.\n\nYouTube не проверен.')
        self.evidence = retrieve(self.source, 'Telegram HTTPS body и YouTube', required_topics=TOPICS)

    def raw(self, quotes):
        return json.dumps({'status': 'evidence' if quotes else 'insufficient', 'quotes': quotes})

    def test_diversity_preserves_second_service(self):
        self.assertEqual(coverage_report([e['text'] for e in self.evidence], TOPICS)['missing'], [])
        self.assertEqual(len(self.evidence), 2)

    def test_quote_omitting_youtube_is_rejected(self):
        raw = self.raw([{'evidence_id': 'E1', 'text': 'Telegram HTTPS body проверен.'}])
        with self.assertRaisesRegex(Rejected, 'missing requested topics: youtube'):
            validate(raw, self.evidence, self.source, 'stop', TOPICS)

    def test_both_statuses_preserved_without_semantic_claim(self):
        raw = self.raw([{'evidence_id': e['id'], 'text': e['text'].strip()} for e in self.evidence])
        result = validate(raw, self.evidence, self.source, 'stop', TOPICS)
        self.assertEqual(result['topic_coverage']['missing'], [])
        self.assertIn('YouTube не проверен.', [q['text'] for q in result['quotes']])
        self.assertFalse(result['semantic_accuracy_verified'])
        self.assertFalse(result['topic_coverage']['semantic_completeness_verified'])
        self.assertFalse(result['actions_executed'])

    def test_insufficient_is_allowed_without_fabrication(self):
        result = validate(self.raw([]), self.evidence, self.source, 'stop', TOPICS)
        self.assertEqual(result['status'], 'insufficient')
        self.assertEqual(result['topic_coverage']['missing'], list(TOPICS))

    def test_unknown_not_changed_to_pass(self):
        raw = self.raw([{'evidence_id': 'E2', 'text': 'YouTube работает.'}])
        with self.assertRaises(Rejected):
            validate(raw, self.evidence, self.source, 'stop', TOPICS)

    def test_missing_source_topic_reported(self):
        e = retrieve(Source('s', 'Synthetic', 'Telegram не проверен.'), 'Статус', required_topics=TOPICS)
        self.assertEqual(coverage_report([x['text'] for x in e], TOPICS)['missing'], ['youtube'])

    def test_alias_matching(self):
        self.assertEqual(coverage_report(['Телеграм не проверен. Ютуб недоступен.'], TOPICS)['missing'], [])

    def test_no_substring_match_for_short_token(self):
        self.assertEqual(coverage_report(['торговля'], {'tor': ['тор']})['missing'], ['tor'])

    def test_no_short_query_prefix_match(self):
        self.assertEqual(retrieve(Source('s', 'S', 'Торговля книгами.'), 'тор'), [])

    def test_topics_validated_in_prompt(self):
        prompt = make_prompt('Статус?', self.evidence, TOPICS)
        self.assertEqual(json.loads(prompt[1]['content'])['required_lexical_topics'], list(TOPICS))

    def test_invalid_topics(self):
        for topics in [[], 'x', {'a': []}, {'a': ['']}, {'a': ['the']}, {'a': 'Telegram'}, {'!connect': ['xray']}, {str(n): ['xray'] for n in range(4)}]:
            with self.subTest(topics=topics):
                with self.assertRaises(ValueError): retrieve(self.source, 'Статус', required_topics=topics)

    def test_empty_topics_backwards_compatible(self):
        self.assertEqual(retrieve(self.source, 'Telegram'), retrieve(self.source, 'Telegram', required_topics={}))

    def test_non_dictionary_evidence_rejected(self):
        with self.assertRaises(Rejected): validate(self.raw([]), ['invalid'], self.source, 'stop')

    def test_missing_evidence_field_rejected(self):
        e = [dict(self.evidence[0])]; del e[0]['sha256']
        with self.assertRaises(Rejected): validate(self.raw([]), e, self.source, 'stop')

    def test_unhashable_evidence_id_rejected(self):
        e = [dict(self.evidence[0], id=[])]
        with self.assertRaises(Rejected): validate(self.raw([]), e, self.source, 'stop')

    def test_trusted_source_instructions_rejected(self):
        e = [dict(self.evidence[0], trusted_instructions=True)]
        with self.assertRaises(Rejected): validate(self.raw([]), e, self.source, 'stop')

    def test_unknown_evidence_field_rejected(self):
        e = [dict(self.evidence[0], action='connect')]
        with self.assertRaises(Rejected): validate(self.raw([]), e, self.source, 'stop')

    def test_deterministic_order(self):
        self.assertEqual(self.evidence, retrieve(self.source, 'Telegram HTTPS body и YouTube', required_topics=TOPICS))

    def test_topic_presence_is_not_semantic_proof(self):
        report = coverage_report(['Telegram — имя героя. YouTube — название корабля.'], TOPICS)
        self.assertEqual(report['missing'], [])
        self.assertFalse(report['semantic_completeness_verified'])

    def test_two_passage_bound_is_not_silently_expanded(self):
        topics = dict(TOPICS, discord=['Discord'])
        e = retrieve(Source('s', 'S', 'Telegram проверен.\n\nYouTube проверен.\n\nDiscord проверен.'), 'Статусы', required_topics=topics)
        self.assertEqual(len(e), 2)
        self.assertEqual(len(coverage_report([x['text'] for x in e], topics)['missing']), 1)

if __name__ == '__main__': unittest.main()
