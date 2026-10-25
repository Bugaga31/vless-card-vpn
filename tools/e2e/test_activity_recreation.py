import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('recreation', Path(__file__).with_name('verify_activity_recreation.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

class ActivityRecreationTest(unittest.TestCase):
    good = 'fixture state mode=SERVERS servers=1 selected=1\nservice command=start state=IDLE\npool size=1 ids=[]\nfixture activity restored\n'
    def test_normal_recreation(self): self.assertTrue(m.valid(self.good))
    def test_missing_evidence_fails(self):
        self.assertFalse(m.valid('')); self.assertFalse(m.valid(self.good.replace('fixture activity restored','')))
    def test_replayed_reload_fails(self): self.assertFalse(m.valid(self.good+'fixture state mode=SERVERS\n'))
    def test_restarted_vpn_fails(self): self.assertFalse(m.valid(self.good+'service command=start state=CONNECTED\n'))
    def test_replaced_core_or_tun_fails(self):
        self.assertFalse(m.valid(self.good+'pool size=1 ids=[]\n'))
        self.assertFalse(m.valid(self.good+'teardown state=CONNECTED keepTun=false reason=restart\n'))
    def test_activity_wiring_is_debug_only_and_restoration_aware(self):
        text=(Path(__file__).resolve().parents[2]/'app/src/main/java/com/vlesscardvpn/MainActivity.kt').read_text()
        self.assertIn('e2e(intent, restored = savedInstanceState != null)',text)
        self.assertLess(text.index('if (!BuildConfig.DEBUG || i == null) return'),text.index('E2ECommands.consume'))
        self.assertNotIn('if (i.getBooleanExtra("e2e_',text)
