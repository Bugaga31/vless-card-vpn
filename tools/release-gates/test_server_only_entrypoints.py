"""Regression guards for legacy proxyOnly preferences during server-only migration.
Emulator retired-proxy case separately checks real VPN and remote body transfer.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2] / 'app/src/main/java/com/vlesscardvpn'


class ServerOnlyEntrypoints(unittest.TestCase):
    def test_saved_proxy_flag_cannot_skip_vpn_consent(self):
        for name in ('MainActivity.kt', 'vpn/VpnWidget.kt', 'vpn/ToggleTileService.kt', 'core/Assistant.kt'):
            text = (ROOT / name).read_text()
            self.assertIn('VpnService.prepare(', text, name)
            self.assertNotRegex(text, r'settings\.proxyOnly\s*(?:\|\||&&|\))', name)

    def test_soft_reconnect_keeps_tun_regardless_of_saved_proxy_flag(self):
        text = (ROOT / 'vpn/TunnelService.kt').read_text()
        keep = re.search(r'val keepTun = ([^\n]+)', text)
        self.assertIsNotNone(keep)
        self.assertEqual('soft && tun != null && prevSocks != null', keep.group(1).strip())
        self.assertNotIn('Store.state.value.settings.proxyOnly', text)

    def test_retired_proxy_emulator_case_requires_vpn_and_server(self):
        text = (ROOT.parents[5] / 'tools/e2e-android.sh').read_text()
        case = text.split('run_case retired-proxy proxy.json', 1)[1].split('CASE=warp;', 1)[0]
        self.assertIn('auth=true.*proxy=false', case)
        self.assertIn('required server saw no traffic', case)
        self.assertIn('10809 unexpectedly open', case)


if __name__ == '__main__':
    unittest.main()
