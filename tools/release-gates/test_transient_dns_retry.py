import copy
import importlib.util
from pathlib import Path
import unittest
s=importlib.util.spec_from_file_location('dns_retry',Path(__file__).resolve().parents[1]/'e2e/transient_dns_retry.py')
v=importlib.util.module_from_spec(s);s.loader.exec_module(v)

class DnsRetry(unittest.TestCase):
    def data(self):return dict(vpn=True,results=[dict(code=-1,error='UnknownHostException: synthetic.test'),dict(code=200,bytes=1048576),dict(code=200),dict(code=204)])
    def test_exact_observed_pattern_only(self):self.assertTrue(v.eligible(self.data()))
    def test_missing_vpn_or_transfer_not_retried(self):
        for key,val in [('vpn',False),('vpn',None)]:
            d=self.data();d[key]=val;self.assertFalse(v.eligible(d))
        for size in [0,1048575,'1048576',True]:
            d=self.data();d['results'][1]['bytes']=size;self.assertFalse(v.eligible(d))
    def test_other_network_errors_not_hidden(self):
        for error in ['SocketTimeoutException: timeout','SSLHandshakeException: cert','UnknownHostExceptionFake: nope','']:
            d=self.data();d['results'][0]['error']=error;self.assertFalse(v.eligible(d))
    def test_additional_failure_or_http_error_not_hidden(self):
        for i in range(4):
            for code in [403,429,500]:
                d=self.data();d['results'][i]['code']=code;self.assertFalse(v.eligible(d))
        d=self.data();d['results'][2]['error']='IOException: failure';self.assertFalse(v.eligible(d))
    def test_success_does_not_retry(self):
        d=self.data();d['results'][0]=dict(code=204,bytes=0);self.assertFalse(v.eligible(d))
    def test_malformed_result_rejected(self):
        for d in [None,{},dict(vpn=True,results=[None]*4),dict(vpn=True,results=[])]:self.assertFalse(v.eligible(d))
