from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]/'app/src/main/java/com/vlesscardvpn'
class ForegroundWiring(unittest.TestCase):
 def test_user_tcp_uses_explicit_foreground_limit(self):
  a=(ROOT/'core/Actions.kt').read_text();t=(ROOT/'core/Tester.kt').read_text()
  self.assertIn('Tester.fastFilter(ping, foreground = true)',a)
  self.assertIn('plan.foregroundTcpParallel',t)
 def test_actual_probe_limit_matches_foreground_message(self):
  a=(ROOT/'core/Actions.kt').read_text();t=(ROOT/'core/Tester.kt').read_text()
  self.assertIn('foreground = firstWaveOnly',a);self.assertIn('calls = calls, foreground = foreground',a)
  self.assertIn('PowerPolicy.cap(parallel, probeLimit)',t)
 def test_rescue_has_no_full_catalog_scan_or_two_minute_masks(self):
  a=(ROOT/'core/Actions.kt').read_text().split('suspend fun autoRescue',1)[1].split('\n    }',1)[0]
  self.assertNotIn('doTestAll(',a);self.assertNotIn('doRefresh()',a);self.assertNotIn('120_000',a)
  self.assertIn('firstWaveOnly = true',a)
 def test_skipped_masks_are_not_failed_servers(self):
  a=(ROOT/'core/Actions.kt').read_text().split('suspend fun doFindMasks',1)[1].split('\n    }',1)[0]
  self.assertIn('if (b == null && tried.none { it.first == s.id }) return@forEach',a)
  self.assertIn('kotlinx.coroutines.withTimeoutOrNull',a)
  self.assertNotIn('System.currentTimeMillis() > deadline',a)
 def test_background_reserves_are_wired_and_visible(self):
  a=(ROOT/'core/Actions.kt').read_text();u=(ROOT/'ui/DeviceScreen.kt').read_text()
  self.assertIn('        catalogWave()',a);self.assertIn('checkedAt >= checkedAfter',a)
  self.assertIn('Actions.reserveStatus.collectAsState()',u)
 def test_cloud_intake_is_not_misrepresented_as_connected(self):
  self.assertIn('Приёмник ошибок vless-card-cloud не подключён',(ROOT/'ui/DeviceScreen.kt').read_text())
 def test_tor_one_tap_button_uses_vpn_upstream_preset(self):
  self.assertIn('TorConnectionPolicy.automatic(it)',(ROOT/'ui/TorSettingsCard.kt').read_text())
