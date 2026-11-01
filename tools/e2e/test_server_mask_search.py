import unittest
from verify_server_mask_search import verified


class ServerMaskSearchTest(unittest.TestCase):
    def test_complete_server_backed_search(self):
        self.assertTrue(verified('Подтверждено маскировок для 3 из 3 серверов; применено 3, более свежие результаты сохранены (перебрано 72 вариантов, новых авто-масок: 4)'))
    def test_no_evolution_still_requires_complete_search(self):
        self.assertTrue(verified('Подтверждено маскировок для 3 из 3 серверов; применено 3, более свежие результаты сохранены (перебрано 72 вариантов)'))
    def test_partial_and_failed_search_never_pass(self):
        for n in (0, 1, 2):
            self.assertFalse(verified(f'Подтверждено маскировок для {n} из 3 серверов; применено 3, более свежие результаты сохранены (перебрано 72 вариантов)'))
    def test_too_few_candidates_never_pass(self):
        self.assertFalse(verified('Подтверждено маскировок для 3 из 3 серверов; применено 3, более свежие результаты сохранены (перебрано 3 вариантов)'))
    def test_wrong_fixture_count_never_pass(self):
        self.assertFalse(verified('Подтверждено маскировок для 1 из 1 серверов; применено 3, более свежие результаты сохранены (перебрано 24 вариантов)'))
    def test_old_standalone_result_is_not_server_proof(self):
        self.assertFalse(verified('YouTube открыли 12 из 12. Выбрана: BYEDPI'))
    def test_missing_unknown_or_changed_network_never_pass(self):
        for text in ('', 'UNKNOWN', 'Сеть изменилась', 'Сеть изменилась Подтверждено маскировок для 3 из 3 серверов; применено 3, более свежие результаты сохранены (перебрано 72 вариантов)'):
            self.assertFalse(verified(text))
