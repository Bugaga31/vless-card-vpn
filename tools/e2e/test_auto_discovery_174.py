from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]
class AutoDiscoveryWiring(unittest.TestCase):
    def test_rescue_is_parallel_and_not_behind_global_twelve_hour_gate(self):
        s=(ROOT/'app/src/main/java/com/vlesscardvpn/core/Actions.kt').read_text()
        p=s.split('private suspend fun doAutoPrepare',1)[1].split('/** Cached cloud/network',1)[0]
        self.assertIn('val refresh = launch(Bg.io)',p)
        self.assertLess(p.index('val refresh = launch'),p.index('quickTest(cached'))
        self.assertNotIn('lastSubRefresh',p);self.assertNotIn('doRefresh(',p)
        self.assertIn('withTimeoutOrNull(AutoDiscoveryPolicy.FAST_MS)',p)
        self.assertIn('batches.receiveCatching()',p)
        self.assertIn('refresh.cancel()',p)
        self.assertIn('firstWaveOnly = true',p)
    def test_explicit_foreground_cloud_refresh_respects_opt_out(self):
        for name in ('CloudControl','CloudSpeed'):
            s=(ROOT/f'app/src/main/java/com/vlesscardvpn/core/{name}.kt').read_text()
            self.assertIn('!Report.enabled(ctx) || (!foreground && !PhonePower.refresh(ctx).allowLight)',s)
        s=(ROOT/'app/src/main/java/com/vlesscardvpn/core/Actions.kt').read_text()
        self.assertIn('refreshPublicCloud(foreground = true)',s)
    def test_deferred_mask_search_remains_bounded(self):
        s=(ROOT/'app/src/main/java/com/vlesscardvpn/core/Actions.kt').read_text()
        p=s.split('private suspend fun doAutoPrepare',1)[1].split('/** Cached cloud/network',1)[0]
        self.assertIn('budgetMs = 8000',p);self.assertNotIn('budgetMs = 60_000',p)
        self.assertIn('AutoEligibility.servicePool',p)
        self.assertIn('sameNetwork()',p)
    def test_battery_offer_is_foreground_post_connect_and_optional(self):
        s=(ROOT/'app/src/main/java/com/vlesscardvpn/MainActivity.kt').read_text()
        self.assertIn('repeatOnLifecycle(Lifecycle.State.RESUMED)',s)
        self.assertIn('Tunnel.State.CONNECTED',s)
        self.assertIn('DeviceCoach.open(this, "vpnBattery")',s)
        self.assertIn('.setNegativeButton("Не сейчас", null)',s)
        self.assertIn('batteryOffer?.dismiss()',s)
        self.assertLess(s.index('prefs.edit().putBoolean("offered", true)'),s.index('batteryOffer = android.app.AlertDialog.Builder'))
if __name__=='__main__':unittest.main()
