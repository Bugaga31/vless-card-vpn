from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]
class ContinuousAuto(unittest.TestCase):
 def test_foreground_uses_continuous_campaign(self):
  s=(ROOT/'app/src/main/java/com/vlesscardvpn/core/Actions.kt').read_text()
  p=s.split('private fun autoPrepare',1)[1].split('private val autoSourceAttempts',1)[0]
  self.assertIn('AutoSearchCampaign.run',p);self.assertIn('continuous = true',p);self.assertIn('if (selected && !stale)',p)
 def test_no_error_or_long_recovery_after_failed_continuous_wave(self):
  s=(ROOT/'app/src/main/java/com/vlesscardvpn/core/Actions.kt').read_text()
  p=s.split('private suspend fun doAutoPrepare',1)[1].split('/** Cached cloud/network',1)[0]
  self.assertIn('if (!continuous) kotlinx.coroutines.withTimeoutOrNull',p)
  self.assertLess(p.index('if (continuous) return@coroutineScope'),p.index('Tunnel.State.ERROR'))
 def test_source_cooldown_is_per_source_and_wave_rotates(self):
  s=(ROOT/'app/src/main/java/com/vlesscardvpn/core/Actions.kt').read_text()
  self.assertIn('autoSourceAttempts[net + "|" + it]',s);self.assertIn('startsWith("Моб.: "), round)',s)
 def test_expensive_ranking_window_and_probe_cancellation_remain_bounded(self):
  s=(ROOT/'app/src/main/java/com/vlesscardvpn/core/Actions.kt').read_text()
  self.assertIn('.take(AutoDiscoveryPolicy.RANKING_WINDOW) else allEligible',s)
  p=s.split('suspend fun quickTest',1)[1]
  # 1.2.66 records attempts only after realTest returns, never on cancellation.
  probe=p.index('val fastOk = realTest')
  recorded=p.index('if (firstWaveOnly) attempted.addAll(shortlist.map { it.id })')
  self.assertLess(probe,recorded)
  self.assertNotIn('attempted.addAll',p[:probe])
  self.assertIn('ConnectPolicy.requiresBodyProof(earlyHandoff)',p)
 def test_diagnostics_are_local_fixed_schema_and_explicit_share(self):
  s=(ROOT/'app/src/main/java/com/vlesscardvpn/core/AutoDiagnostics.kt').read_text()
  self.assertNotIn('OkHttpClient',s);self.assertNotIn('http://',s);self.assertNotIn('https://',s)
  u=(ROOT/'app/src/main/java/com/vlesscardvpn/ui/DeviceScreen.kt').read_text()
  self.assertIn('Intent.createChooser',u);self.assertIn('автоматической отправки'.lower(),u.lower())
