import copy
import importlib.util
from pathlib import Path
import unittest
spec=importlib.util.spec_from_file_location('validate_probe',Path(__file__).with_name('validate_probe.py'))
v=importlib.util.module_from_spec(spec);spec.loader.exec_module(v)
class ProbeResultTest(unittest.TestCase):
    def good(self):
        return {'vpn':True,'results':[{'code':204,'bytes':0},{'code':200,'bytes':1048576},{'code':200,'bytes':1048576},{'code':204,'bytes':0}]}
    def test_two_complete_independent_transfers_pass(self): self.assertTrue(v.valid(self.good(),1048576))
    def test_rate_limited_resource_is_not_success(self):
        r=self.good();r['results'][2]={'code':429,'bytes':3154};self.assertFalse(v.valid(r,1048576))
    def test_short_second_transfer_fails(self):
        r=self.good();r['results'][2]['bytes']=16384;self.assertFalse(v.valid(r,1048576))
    def test_short_first_transfer_fails(self):
        r=self.good();r['results'][1]['bytes']=16384;self.assertFalse(v.valid(r,1048576))
    def test_missing_result_does_not_pass(self):
        for r in [None,{},[],{'vpn':True,'results':[]},self.good()|{'results':[{},{}]}]:self.assertFalse(v.valid(r,1048576))
    def test_no_vpn_does_not_pass(self):
        r=self.good();r['vpn']=False;self.assertFalse(v.valid(r,1048576))
    def test_error_statuses_never_pass(self):
        for i in range(4):
            r=self.good();r['results'][i]['code']=503;self.assertFalse(v.valid(r,1048576))
    def test_standard_google_check_is_kept_outside_warp(self):
        r=self.good();r['results'][2]['bytes']=10000;self.assertTrue(v.valid(r));self.assertFalse(v.valid(r,1048576))
    def test_single_dns_miss_can_request_full_retry_but_is_never_pass(self):
        r=self.good();r['results'][0]={'code':-1,'error':'UnknownHostException: fixture'}
        self.assertTrue(v.retryable_dns_handoff(r));self.assertFalse(v.valid(r,1048576))
    def test_certificate_or_http_failure_cannot_use_dns_retry(self):
        r=self.good();r['results'][0]={'code':-1,'error':'SSLHandshakeException: fixture'}
        self.assertFalse(v.retryable_dns_handoff(r))
        r['results'][0]={'code':429,'bytes':100};self.assertFalse(v.retryable_dns_handoff(r))
    def test_two_dns_misses_or_short_transfer_cannot_use_retry(self):
        r=self.good();r['results'][0]={'code':-1,'error':'UnknownHostException: fixture'}
        r['results'][2]['bytes']=16384;self.assertFalse(v.retryable_dns_handoff(r))
        r['results'][2]={'code':-1,'error':'UnknownHostException: fixture'};self.assertFalse(v.retryable_dns_handoff(r))
    def test_absent_vpn_cannot_use_dns_retry(self):
        r=self.good();r['vpn']=False;r['results'][0]={'code':-1,'error':'UnknownHostException: fixture'}
        self.assertFalse(v.retryable_dns_handoff(r))
if __name__=='__main__':unittest.main()
