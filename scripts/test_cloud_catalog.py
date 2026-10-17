import unittest
from unittest.mock import mock_open, patch
from cloud_servers import catalog

class CloudCatalogTest(unittest.TestCase):
    def test_requested_gist_constant_is_loaded_once_without_false_ru_tag(self):
        rows = catalog()
        self.assertEqual(1, sum('gist.githubusercontent.com/Diman331/' in url for url, _ in rows))
        self.assertFalse(next(ru for url, ru in rows if 'gist.githubusercontent.com/Diman331/' in url))
        self.assertTrue(any('/servers/alive_ru.txt' in url and ru for url, ru in rows))

    def test_gh_constant_and_literal_sources_keep_their_group(self):
        src = '''private const val IG = "org/repo/main"
const val CUSTOM = "https://example.invalid/a"
SubSource(gh("$IG/sub.txt"), "gh", RU),
SubSource(CUSTOM, "custom", WL),
SubSource("https://example.invalid/b", "literal", WORLD),
SubSource(CUSTOM, "duplicate", WL),'''
        with patch('builtins.open',mock_open(read_data=src)):
            self.assertEqual([('org/repo/main/sub.txt', True),('https://example.invalid/a',True),('https://example.invalid/b',False)],catalog())

if __name__ == '__main__':
    unittest.main()
