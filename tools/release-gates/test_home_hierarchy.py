import importlib.util
from pathlib import Path
import unittest
import xml.etree.ElementTree as E
s=importlib.util.spec_from_file_location('home',Path(__file__).with_name('check_home_hierarchy.py'))
v=importlib.util.module_from_spec(s);s.loader.exec_module(v)
class HomeTest(unittest.TestCase):
    def root(self,labels,package='com.vlesscardvpn.beta2'):
        r=E.Element('hierarchy')
        for label in labels:E.SubElement(r,'node',package=package,text=label)
        return r
    def test_real_home_controls(self):self.assertTrue(v.is_home(self.root(['Подключить','Главная','Серверы']),'com.vlesscardvpn.beta2'))
    def test_many_intro_labels_are_not_home(self):self.assertFalse(v.is_home(self.root(['VLESS Card','На одной волне','Открыть приложение','Без звука','Xray']),'com.vlesscardvpn.beta2'))
    def test_home_behind_intro_is_not_visible_home(self):self.assertFalse(v.is_home(self.root(['Подключить','Главная','Серверы','Открыть приложение']),'com.vlesscardvpn.beta2'))
    def test_other_app_controls_not_accepted(self):self.assertFalse(v.is_home(self.root(['Подключить','Главная','Серверы'],package='android'),'com.vlesscardvpn.beta2'))
