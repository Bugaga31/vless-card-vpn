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
    def test_six_recreations_required_when_requested(self):
        six = self.good + 'fixture activity restored\n' * 5
        self.assertTrue(m.valid(six, min_restores=6))
        self.assertFalse(m.valid(self.good, min_restores=6))
        self.assertFalse(m.valid(six + 'teardown state=CONNECTED keepTun=true reason=restart\n', min_restores=6))

    def test_repeated_rotation_checked_without_clearing_startup_history(self):
        root=Path(__file__).resolve().parents[2]
        script=(root/'tools/e2e-android.sh').read_text()
        single=script.split('if [ "$1" = single ]; then',2)[2].split('  fi\n  R=$(probe',1)[0]
        self.assertIn('for rotation in 1 0 1 0 1 0',single)
        self.assertIn('waitlog_after "fixture activity restored"',single)
        self.assertIn('--min-restores 6',single)
        self.assertNotIn('adb logcat -c',single)
        self.assertIn('recreation-summary.txt',script)
        self.assertIn('>> "$W/logcat.txt"',script)

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

    def test_command_literals_are_not_retained_by_kept_shared_helper(self):
        root=Path(__file__).resolve().parents[2]
        helper=(root/'app/src/main/java/com/vlesscardvpn/core/E2ECommands.kt').read_text()
        self.assertNotIn('"e2e_',helper)
        activity=(root/'app/src/main/java/com/vlesscardvpn/MainActivity.kt').read_text()
        self.assertLess(activity.index('if (!BuildConfig.DEBUG || i == null) return'),activity.index('listOf("e2e_chain"'))
        self.assertIn('fun consume(keys: List<String>, restored: Boolean',helper)
