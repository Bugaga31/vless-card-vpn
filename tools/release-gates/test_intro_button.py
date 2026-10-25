import importlib.util
from pathlib import Path
import unittest
import xml.etree.ElementTree as E
s=importlib.util.spec_from_file_location('intro_button',Path(__file__).with_name('intro_button.py'))
v=importlib.util.module_from_spec(s);s.loader.exec_module(v)
PKG='com.vlesscardvpn.beta2'
class IntroButtonTest(unittest.TestCase):
    def root(self,**attrs):
        r=E.Element('hierarchy')
        E.SubElement(r,'node',dict(package=PKG,text='Открыть приложение',enabled='true',clickable='true',bounds='[10,20][110,80]',**attrs))
        return r
    def test_exact_button(self):self.assertEqual((60,50),v.target(self.root(),PKG))
    def test_other_package(self):self.assertIsNone(v.target(self.root(),'other'))
    def test_disabled(self):
        r=self.root();r[0].set('enabled','false');self.assertIsNone(v.target(r,PKG))
    def test_malformed_or_reversed_bounds(self):
        for bounds in ['','[-1,0][10,10]','[20,20][10,30]','[0,0][50000,50000]']:
            r=self.root();r[0].set('bounds',bounds);self.assertIsNone(v.target(r,PKG))
    def test_duplicate_button_rejected(self):
        r=self.root();r.append(E.fromstring(E.tostring(r[0])));self.assertIsNone(v.target(r,PKG))
    def test_compose_text_uses_clickable_parent(self):
        r=self.root();n=r[0];n.set('text','');child=E.SubElement(n,'node',package=PKG,text='Открыть приложение',enabled='true',clickable='false')
        self.assertEqual((60,50),v.target(r,PKG))
    def test_home_is_not_a_tap_target(self):
        r=self.root();r[0].set('text','Подключить');self.assertIsNone(v.target(r,PKG))
