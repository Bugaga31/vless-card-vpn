import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('snapshot_failure',Path(__file__).with_name('snapshot_failure.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
ROOT=Path(__file__).resolve().parents[2]
class FailureSnapshotTest(unittest.TestCase):
    def test_preserves_original_logs_after_next_case_clears_them(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);(p/'server.log').write_bytes(b'first failure');(p/'access.log').write_bytes(b'first route')
            m.snapshot(p,'multi',1,io.BytesIO(b'first app error'))
            (p/'server.log').write_bytes(b'next case');(p/'access.log').write_bytes(b'')
            self.assertEqual(b'first failure',(p/'failure-multi-1-server.txt').read_bytes())
            self.assertEqual(b'first route',(p/'failure-multi-1-access.txt').read_bytes())
            self.assertEqual(b'first app error',(p/'failure-multi-1-logcat.txt').read_bytes())
    def test_snapshots_are_bounded_and_explicitly_mark_truncation(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);(p/'server.log').write_bytes(b'x'*(m.LIMIT+17));(p/'access.log').write_bytes(b'')
            meta=m.snapshot(p,'multi',1,io.BytesIO(b'z'*(m.LIMIT+33)))
            for label in ('server','logcat'):
                self.assertEqual(m.LIMIT,meta['logs'][label]['saved_bytes']);self.assertTrue(meta['logs'][label]['truncated'])
    def test_only_logs_are_captured_missing_is_not_invented(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);(p/'k.pem').write_text('SECRET');(p/'server.json').write_text('SECRET')
            meta=m.snapshot(p,'single',1,io.BytesIO(b''))
            self.assertFalse(meta['logs']['server']['present']);self.assertIsNone(meta['logs']['server']['sha256'])
            self.assertEqual({'failure-single-1-logcat.txt','failure-single-1-snapshot.txt'}, {x.name for x in p.glob('failure-*')})
            self.assertNotIn('SECRET',json.dumps(meta))
    def summarize(self,data):
        text=subprocess.check_output([sys.executable,str(Path(__file__).with_name('summarize_failure.py')),'--case','multi','--probe'],input=json.dumps(data),text=True)
        return text,json.loads(text.split('::',2)[-1])
    def test_tls_phase_and_bounded_causes_survive(self):
        _,d=self.summarize({'vpn':True,'results':[{'code':-1,'error':'SSLHandshakeException','failure_phase':'connect_headers','failure_reason':'eof','causes':['SSLHandshakeException','EOFException'],'body_bytes_before_error':0}]})
        r=d['results'][0];self.assertEqual('connect_headers',r['failure_phase']);self.assertEqual('eof',r['failure_reason']);self.assertEqual(['SSLHandshakeException','EOFException'],r['causes'])
    def test_forged_probe_fields_cannot_leak_private_values(self):
        text,d=self.summarize({'results':[{'failure_phase':'SECRET','failure_reason':'PRIVATE','causes':['TOKEN']*20,'body_bytes_before_error':-1}]})
        for secret in ['SECRET','PRIVATE','TOKEN']:self.assertNotIn(secret,text)
        self.assertEqual(['Other']*5,d['results'][0]['causes']);self.assertIsNone(d['results'][0]['body_bytes_before_error'])
    def test_existing_transfer_and_security_gates_remain(self):
        script=(ROOT/'tools/e2e-android.sh').read_text()
        self.assertIn('res[1]["bytes"]>=1048576',script)
        self.assertIn('r.get("vpn") and len(res)==4',script)
        self.assertIn('/tmp/e2e/failure-*.txt',(ROOT/'.github/workflows/e2e.yml').read_text())
        self.assertIn(':vpn-test-probe:testDebugUnitTest',(ROOT/'.github/workflows/e2e.yml').read_text())
        probe=(ROOT/'vpn-test-probe/src/main/java/com/vlesscardvpn/netprobe/ProbeActivity.java').read_text()
        self.assertIn('setConnectTimeout(15000)',probe);self.assertIn('setReadTimeout(20000)',probe)
        self.assertNotIn('setSSLSocketFactory',probe);self.assertNotIn('setHostnameVerifier',probe)
        self.assertNotIn('e.getMessage()',probe);self.assertNotIn('e.toString()',probe)

    def test_wrong_field_types_and_split_utf8_do_not_break_diagnostics(self):
        _,d=self.summarize({'results':[{'failure_phase':[], 'failure_reason':{}, 'causes':None}]})
        self.assertEqual('unknown',d['results'][0]['failure_phase'])
        out=subprocess.check_output([sys.executable,str(Path(__file__).with_name('summarize_failure.py')),'--case','multi'],input=b'\xff\x80\n')
        self.assertIn(b'::notice',out)

    def test_request_times_are_numeric_only(self):
        text,d=self.summarize({'results':[{'request_index':0,'run_id':1,'started_wall_ms':1234,'started_elapsed_ms':20,'ended_wall_ms':'SECRET','ended_elapsed_ms':True}]})
        r=d['results'][0]
        self.assertEqual(1234,r['started_wall_ms']);self.assertEqual(20,r['started_elapsed_ms'])
        self.assertIsNone(r['ended_wall_ms']);self.assertIsNone(r['ended_elapsed_ms']);self.assertNotIn('SECRET',text)
    def test_safe_lifecycle_timeline_is_bounded(self):
        text=('core instance=7 role=test event=stop_begin wall_ms=100 elapsed_ms=20\n'*30 +
            'probe run=2 request=0 event=error phase=connect_headers wall_ms=105 elapsed_ms=25\n' +
            'core instance=8 role=SECRET event=PRIVATE wall_ms=100 elapsed_ms=20\n')
        out=subprocess.check_output([sys.executable,str(Path(__file__).with_name('summarize_failure.py')),'--case','multi'],input=text,text=True)
        d=json.loads(out.split('::',2)[-1])
        self.assertEqual(24,len(d['core_events']));self.assertEqual(1,len(d['probe_events']))
        self.assertNotIn('SECRET',out);self.assertNotIn('PRIVATE',out)
