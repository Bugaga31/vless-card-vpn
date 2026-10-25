import json
from pathlib import Path
import subprocess
import sys
import unittest
class BoundedDiagnosticsTest(unittest.TestCase):
    def summarize(self,text):
        s=subprocess.check_output([sys.executable,str(Path(__file__).with_name('summarize_failure.py')),'--case','multi'],input=text,text=True)
        return s,json.loads(s.split('::',2)[-1])
    def test_counts_and_state(self):
        _,d=self.summarize('fixture state mode=SERVERS servers=4 selected=4\nfixture connect permission=false\nconnect failed: java.io.IOException: secret\nFATAL EXCEPTION: main\n')
        self.assertEqual([['SERVERS','4','4']],d['reload_states']);self.assertEqual(['false'],d['vpn_permission_required'])
        self.assertEqual(1,d['connect_failed_count']);self.assertEqual(1,d['fatal_exception_count']);self.assertIn('IOException',d['exception_types'])
    def test_sensitive_values_never_echoed(self):
        s,_=self.summarize('secret=top-secret https://private.example/path 203.0.113.7\nconnect failed: token: keep-private\nfixture state mode=EVIL servers=4 selected=4\n')
        for value in ['top-secret','private.example','203.0.113.7','keep-private','EVIL']:self.assertNotIn(value,s)
    def test_empty_diagnostics_remains_valid(self):
        _,d=self.summarize('');self.assertEqual([],d['reload_states']);self.assertEqual(0,d['fatal_exception_count'])

    def test_auto_network_change_and_cancellation_are_classified(self):
        _,d=self.summarize('action[Авто-настройка]: Сеть изменилась: повторите Авто\naction[Авто-настройка]: Остановлено\n')
        self.assertEqual(['network_changed','cancelled'],d['auto_terminal_states'])
    def test_auto_connection_request_is_not_reported_as_pass(self):
        _,d=self.summarize('action[Авто-настройка]: Первый HTTPS-маршрут найден — VPN подключается; подробные проверки в фоне\n')
        self.assertEqual(['connection_requested'],d['auto_terminal_states'])
        self.assertEqual([],d['last_checks'])
    def test_auto_messages_do_not_expose_sensitive_route_names(self):
        text='action[Авто-настройка]: Авто: выбрано secret https://private.example 203.0.113.7\n'
        text+='action[Авто-настройка]: unexpected private-token\n'
        s,d=self.summarize(text)
        self.assertEqual(['connection_requested','other'],d['auto_terminal_states'])
        for value in ['secret','private.example','203.0.113.7','private-token']:self.assertNotIn(value,s)
    def test_auto_missing_terminal_is_not_invented(self):
        _,d=self.summarize('fixture state mode=AUTO servers=8 selected=0\n')
        self.assertEqual([],d['auto_terminal_states'])
    def test_auto_terminal_history_is_bounded(self):
        _,d=self.summarize('action[Авто-настройка]: Остановлено\n'*20)
        self.assertEqual(['cancelled']*3,d['auto_terminal_states'])

    def test_safe_connect_failure_reason_and_source_survive(self):
        _,d=self.summarize('failure scope=connect phase=core type=IOException code=file_limit origin=TunnelService.kt:350 causes=RuntimeException,IOException\n')
        self.assertEqual('file_limit',d['failures'][0]['code'])
        self.assertEqual('TunnelService.kt:350',d['failures'][0]['origin'])
        self.assertEqual(['RuntimeException','IOException'],d['failures'][0]['causes'])
    def test_forged_failure_fields_cannot_leak_secrets(self):
        s,d=self.summarize('failure scope=SECRET phase=PRIVATE type=TOKEN code=PASSWORD origin=secret.kt:23 causes=SECRET,TOKEN\n')
        for secret in ['SECRET','PRIVATE','TOKEN','PASSWORD','secret.kt']:self.assertNotIn(secret,s)
        self.assertEqual('unknown',d['failures'][0]['origin'])
    def test_failure_history_and_causes_remain_bounded(self):
        line='failure scope=connect phase=tun type=IOException code=unclassified origin=unknown causes='+','.join(['IOException']*50)+'\n'
        _,d=self.summarize(line*20)
        self.assertEqual(3,len(d['failures']));self.assertTrue(all(len(x['causes'])==5 for x in d['failures']))
    def test_service_transitions_and_early_exit_are_not_success(self):
        _,d=self.summarize('service command=start state=IDLE\nteardown state=CONNECTED keepTun=false reason=error\noptimize skipped=foreground_busy\nauto ready_blocked=network_changed\n')
        self.assertEqual([['start','IDLE']],d['service_events'])
        self.assertEqual([['CONNECTED','false','error']],d['teardown_events'])
        self.assertEqual(['foreground_busy'],d['optimize_skips']);self.assertEqual(['network_changed'],d['auto_ready_blocked'])
        self.assertEqual([],d['last_checks'])
    def test_failure_summary_is_saved_before_logcat_is_cleared(self):
        root=Path(__file__).resolve().parents[1]
        script=(root/'e2e-android.sh').read_text()
        self.assertIn('tee "$W/failure-${CASE}-${FAILS}.txt"',script)
        workflow=(root.parent/'.github/workflows/e2e.yml').read_text()
        self.assertIn('/tmp/e2e/failure-*.txt',workflow)
