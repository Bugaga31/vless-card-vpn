import importlib.util
from pathlib import Path
import unittest
s=importlib.util.spec_from_file_location('http429_retry',Path(__file__).resolve().parents[1]/'e2e/http429_retry.py')
v=importlib.util.module_from_spec(s);s.loader.exec_module(v)
class Retry429(unittest.TestCase):
    def data(self,codes=(204,200,429,204),size=1048576,vpn=True):return dict(vpn=vpn,results=[dict(code=c,bytes=size if i==1 else 0) for i,c in enumerate(codes)])
    def test_only_proved_rate_limit_is_eligible(self):self.assertTrue(v.eligible(self.data()))
    def test_other_failures_are_not_hidden_by_retry(self):
        for d in [self.data(size=100),self.data(vpn=False),self.data(codes=(204,200,-1,204)),self.data(codes=(204,200,403,204)),self.data(codes=(429,200,429,204)),None,{},dict(vpn=True,results=[None]*4)]:self.assertFalse(v.eligible(d))
    def test_success_does_not_get_retried(self):self.assertFalse(v.eligible(self.data(codes=(204,200,200,204))))
