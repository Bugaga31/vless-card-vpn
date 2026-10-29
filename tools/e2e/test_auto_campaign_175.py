from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]
class ContinuousAuto(unittest.TestCase):
 def test_foreground_uses_cancellable_persistent_campaign(self):
  s=(ROOT/'app/src/main/java/com/vlesscardvpn/core/Actions.kt').read_text()
  p=s.split('private fun autoPrepare',1)[1].split('private val autoSourceAttempts',1)[0]
  self.assertIn('AutoSearchCampaign.runPersistent',p);self.assertNotIn('MAX_WAVES',p);self.assertNotIn('MAX_TOTAL_MS',p);self.assertIn('SearchPacingPolicy.rest',p);self.assertIn('continuous = true',p);self.assertIn('if (selected && !stale)',p)
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
  # r188 records only admitted probes; merely queued IDs must survive a deadline.
  probe=p.index('val fastOk = realTest')
  self.assertNotIn('attempted.addAll(shortlist.map',p)
  recorded=p.index('onAttempt = {server->if(firstWaveOnly) attempted.add(server.id)')
  self.assertGreater(recorded,probe)
  real=s.split('private suspend fun realTest',1)[1].split('/** For each server',1)[0]
  self.assertIn('onStarted = { i ->',real)
  self.assertIn('onAttempt?.invoke(variants[i].first)',real)
  tester=(ROOT/'app/src/main/java/com/vlesscardvpn/core/Tester.kt').read_text()
  self.assertIn('probe = { i -> onStarted?.invoke(idx[i]); CancellableProbe.run',tester)
  self.assertNotIn('probeMemory.record',p[:probe])
  self.assertIn('RETRY_CATALOG_MS',(ROOT/'app/src/main/java/com/vlesscardvpn/core/AutoSearchCampaign.kt').read_text())
  self.assertIn('ConnectPolicy.requiresBodyProof(earlyHandoff)',p)
 def test_diagnostics_are_local_fixed_schema_and_explicit_share(self):
  s=(ROOT/'app/src/main/java/com/vlesscardvpn/core/AutoDiagnostics.kt').read_text()
  self.assertNotIn('OkHttpClient',s);self.assertNotIn('http://',s);self.assertNotIn('https://',s)
  u=(ROOT/'app/src/main/java/com/vlesscardvpn/ui/DeviceScreen.kt').read_text()
  self.assertIn('Intent.createChooser',u);self.assertIn('автоматической отправки'.lower(),u.lower())
