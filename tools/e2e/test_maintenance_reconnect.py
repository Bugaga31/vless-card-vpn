import json
from pathlib import Path
import subprocess
import sys
import unittest

ROOT=Path(__file__).resolve().parents[2]
class MaintenanceWiringTest(unittest.TestCase):
    def test_background_actions_use_maintenance_callback(self):
        s=(ROOT/'app/src/main/java/com/vlesscardvpn/core/AppWatch.kt').read_text()
        self.assertNotIn('Actions.lighten()',s); self.assertNotIn('Actions.boostYoutube()',s)
        self.assertIn('Actions.lighten(maintenanceReconnect)',s)
        self.assertIn('Actions.boostYoutube(maintenanceReconnect)',s)
        self.assertIn('Actions.fixAll { reconnect() }',s)
    def test_service_keeps_emergency_callback_and_checks_generation(self):
        s=(ROOT/'app/src/main/java/com/vlesscardvpn/vpn/TunnelService.kt').read_text()
        self.assertIn('maintenanceReconnect = { scheduleMaintenanceReconnect() }',s)
        body=s[s.index('private fun scheduleMaintenanceReconnect()'):s.index('private fun scheduleOptimize(')]
        self.assertIn('core === observed',body); self.assertIn('Net.underlying(this)?.toString() == handle',body)
        self.assertIn('connect(soft = true)',body); self.assertNotRegex(body,r'(?<![\w])connect\(\)')
        self.assertIn('maintenanceJob?.cancel(); maintenanceJob = null',s)
    def test_new_multi_gate_does_not_replace_transfer_gate(self):
        s=(ROOT/'tools/e2e-android.sh').read_text()
        self.assertIn('multi: optional background work hard-restarted VPN',s)
        self.assertIn('probe app traffic through VPN failed',s)
    def test_start_callers_are_bounded_and_allowlisted(self):
        text='start request caller=Actions.kt:789\n'*10+'start request caller=SECRET.kt:42\n'
        out=subprocess.check_output([sys.executable,str(Path(__file__).with_name('summarize_failure.py')),'--case','multi'],input=text,text=True)
        d=json.loads(out.split('::',2)[-1]);self.assertEqual(['Actions.kt:789']*4,d['start_callers']);self.assertNotIn('SECRET',out)
