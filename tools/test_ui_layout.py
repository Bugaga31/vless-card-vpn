import importlib.util
from pathlib import Path
import unittest
spec = importlib.util.spec_from_file_location('layout', Path(__file__).with_name('ui-layout-check.py'))
layout = importlib.util.module_from_spec(spec); spec.loader.exec_module(layout)
class LayoutTest(unittest.TestCase):
    def test_only_expected_content_passes(self):
        self.assertTrue(layout.home_visible('<hierarchy><node text="Готовы к подключению" /></hierarchy>'))
        self.assertFalse(layout.home_visible('<hierarchy><node text="VLESS Card" /></hierarchy>'))
        self.assertFalse(layout.home_visible('<hierarchy><node text="Открыть приложение" /></hierarchy>'))
    def test_scroll_is_inside_actual_container_not_physical_screen(self):
        xml = '<hierarchy><node scrollable="true" bounds="[20,80][300,420]" /></hierarchy>'
        self.assertEqual((160,335,160,165), layout.scroll_points(xml))
    def test_missing_or_clipped_scroll_container_fails(self):
        for xml in ['<hierarchy/>', '<hierarchy><node scrollable="false" bounds="[0,0][100,500]"/></hierarchy>', '<hierarchy><node scrollable="true" bounds="[0,0][30,40]"/></hierarchy>']:
            with self.assertRaises(ValueError): layout.scroll_points(xml)
if __name__ == '__main__': unittest.main()
