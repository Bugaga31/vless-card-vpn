import unittest
from verify_standby_wave_r246 import verify

class StandbyWaveTest(unittest.TestCase):
    def test_two_consecutive_gains_in_same_bounded_wave(self):
        self.assertTrue(verify(['standby wave=100 pass=1 before=0 after=1 start=110 end=1000',
                                'standby wave=100 pass=2 before=1 after=2 start=1001 end=2000']))
    def test_unrelated_waves_cannot_be_combined(self):
        self.assertFalse(verify(['standby wave=100 pass=1 before=0 after=1 start=110 end=1000',
                                 'standby wave=200 pass=1 before=1 after=2 start=210 end=800']))
    def test_no_progress_or_missing_first_sample_cannot_pass(self):
        self.assertFalse(verify(['standby wave=100 pass=1 before=0 after=0 start=110 end=1000',
                                 'standby wave=100 pass=2 before=0 after=1 start=1001 end=2000']))
        self.assertFalse(verify(['standby wave=100 pass=2 before=1 after=2 start=1001 end=2000']))
    def test_gaps_and_invalid_bounds_cannot_pass(self):
        for second in ['standby wave=100 pass=2 before=1 after=2 start=31001 end=32000',
                       'standby wave=100 pass=2 before=1 after=2 start=999 end=2000',
                       'standby wave=100 pass=2 before=1 after=2 start=1001 end=45101']:
            self.assertFalse(verify(['standby wave=100 pass=1 before=0 after=1 start=110 end=1000', second]))
    def test_target_reached_is_not_continued(self):
        self.assertFalse(verify(['standby wave=100 pass=1 before=2 after=3 start=110 end=1000',
                                 'standby wave=100 pass=2 before=3 after=4 start=1001 end=2000']))
if __name__ == '__main__': unittest.main()

class WiringTest(unittest.TestCase):
    def test_test_uses_real_observations_and_other_uid_probe(self):
        from pathlib import Path
        service=Path('app/src/main/java/com/vlesscardvpn/vpn/TunnelService.kt').read_text()
        runner=Path('tools/e2e-android.sh').read_text()
        self.assertIn('if(BuildConfig.DEBUG) Log.i("E2E","standby wave=$now',service)
        self.assertLess(service.index('Actions.prepareStandby(servers)'),service.index('standby wave=$now'))
        self.assertIn('verify_standby_wave_r246.py',runner)
        self.assertIn('probe reserve-wave-after',runner)
