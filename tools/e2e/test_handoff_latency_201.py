from pathlib import Path
import unittest
R=Path(__file__).resolve().parents[2]
class HandoffLatencyWiring(unittest.TestCase):
 def test_no_second_fixed_sleep(self):
  s=(R/'app/src/main/java/com/vlesscardvpn/vpn/TunnelService.kt').read_text().split('private fun queueNetworkRefresh()',1)[1].split('private fun teardown',1)[0]
  self.assertIn('networkRefreshWindow.delayMs',s);self.assertIn('delay(waitMs)',s);self.assertNotIn('delay(700)',s);self.assertIn('handoff.valid(change.revision)',s);self.assertIn('setUnderlyingNetworks',s)
 def test_recheck_scoped_to_physical_path(self):
  s=(R/'app/src/main/java/com/vlesscardvpn/core/Actions.kt').read_text().split('private suspend fun quickRecheckWithinDeadline',1)[1].split('private fun selectionNetworkChanged',1)[0]
  self.assertIn('Net.observation(app)',s);self.assertIn('Net.matches(app,observed)',s);self.assertIn('AutoHandoffPolicy.prioritize',s);self.assertIn('AutoHandoffPolicy.PROBE_WINDOW_MS',s);self.assertIn('AutoEligibility.servicePool',s);self.assertIn('if (valid()) progress.value',s)
 def test_subscription_queue_and_pause_preserved(self):
  s=(R/'app/src/main/java/com/vlesscardvpn/core/Actions.kt').read_text();self.assertIn('SubscriptionQueue.fetchOrder(due,round)',s);self.assertIn('SubscriptionQueue.window(rankedCandidates',s)
  self.assertIn('WAVE_PAUSE_MS = 5000L',(R/'app/src/main/java/com/vlesscardvpn/core/AutoSearchCampaign.kt').read_text())
