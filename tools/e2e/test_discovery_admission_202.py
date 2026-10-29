from pathlib import Path
import unittest
R=Path(__file__).resolve().parents[2]
class DiscoveryAdmission(unittest.TestCase):
 def test_preparation_is_shared_without_bypassing_tls(self):
  a=(R/'app/src/main/java/com/vlesscardvpn/core/Actions.kt').read_text()
  self.assertIn('if (!ForegroundProbePlan.needsCertificate(s)) return@withPermit s',a)
  self.assertIn('Libv2ray.fetchTlsCertSha256',a)
  self.assertIn('val fastOk = ForegroundProbePlan.run(shortlist, firstWaveOnly,',a)
  self.assertIn('realTest(cohort, "Авто: проверяю первый быстрый маршрут"',a)
  self.assertIn('onAttempt = {server->if(firstWaveOnly) attempted.add(server.id)',a)
 def test_foreground_workers_and_power_caps(self):
  t=(R/'app/src/main/java/com/vlesscardvpn/core/Tester.kt').read_text()
  self.assertIn('staggerMs = ForegroundProbePlan.staggerMs(foreground,stopAfter)',t)
  self.assertIn('PowerPolicy.cap(parallel, probeLimit)',t)
  self.assertIn('AutoLatencyPolicy.accepts(p)',t)
 def test_source_queue_and_pause_unchanged(self):
  a=(R/'app/src/main/java/com/vlesscardvpn/core/Actions.kt').read_text()
  for x in ['SubscriptionQueue.fetchOrder(due,round)','SubscriptionQueue.window(', 'ConnectPolicy.FIRST,provenLeader']:
   self.assertIn(x,a)
  self.assertIn('const val WAVE_PAUSE_MS = 5000L',(R/'app/src/main/java/com/vlesscardvpn/core/AutoSearchCampaign.kt').read_text())
