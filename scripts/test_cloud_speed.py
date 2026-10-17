import unittest
from unittest.mock import patch, mock_open
from cloud_real import measured_kbps, learn_speed, speed_trials, write_speed, SPEED_PAR
from cloud_manifest import build
L='vless://00000000-0000-0000-0000-000000000001@node.example:443?security=reality&pbk=public&flow=xtls-rprx-vision&sni=example.com'
class CloudSpeedTest(unittest.TestCase):
    def history(self,kb=10000,at=10000):
        return {'n':10,'ok':10,'ms':100,'t':10000,'kb':kb,'kb_t':at,'kb_isolated':True,'samples':[[10000,True]]}
    def test_no_competing_downloads(self):self.assertEqual(1,SPEED_PAR)
    def test_expired_future_legacy_and_failed_routes_have_no_certified_speed(self):
        h=self.history();self.assertEqual(10000,measured_kbps(h,10000))
        for change in ({'kb_t':0},{'kb_t':10001},{'kb_isolated':False},{'samples':[[10000,False]]}):
            self.assertEqual(0,measured_kbps(dict(h,**change),10000))
    def test_malformed_history_never_certifies_speed(self):
        for bad in (None, {}, {'samples':[[]]}, {'samples':[[10000,True]],'kb_t':True,'kb_isolated':True,'kb':999}):
            self.assertEqual(0,measured_kbps(bad,10000))
    def test_empty_trial_budget_runs_no_downloads(self):
        self.assertEqual([],speed_trials({L:100},{L:self.history()},10000,0))
    def test_failed_speed_probe_revokes_old_positive_hint(self):
        hist={L:self.history()};learn_speed(hist,{L:0},10000)
        self.assertEqual(0,measured_kbps(hist[L],10000))
    def test_fresh_isolated_sample_has_its_own_timestamp(self):
        hist={L:self.history()};learn_speed(hist,{L:20000},10001)
        self.assertEqual(20000,measured_kbps(hist[L],10001));self.assertEqual(10001,hist[L]['kb_t'])
    def test_manifest_does_not_relabel_old_bandwidth_as_fresh(self):
        h=self.history(at=1);nodes=build({L:h},10000)['nodes']
        self.assertEqual(0,nodes[0]['kbps'])
    def test_manifest_prefers_measured_throughput_not_only_ping(self):
        other=L.replace('node.example','other.example');a=self.history(1000);a['ms']=10;b=self.history(40000);b['ms']=200
        self.assertEqual(other,build({L:a,other:b},10000)['nodes'][0]['link'])
    def test_trial_queue_reserves_old_unmeasured_routes(self):
        links=[L.replace('node.example',f'n{i}.example') for i in range(8)]
        hist={link:self.history(50000-i*1000) for i,link in enumerate(links)}
        hist[links[-1]]['kb_t']=0
        chosen=speed_trials(dict.fromkeys(links,100),hist,10000,4)
        self.assertEqual(4,len(chosen));self.assertIn(links[-1],chosen);self.assertIn(links[0],chosen)
    def test_export_drops_failed_latest_checks(self):
        hist={L:self.history()};hist[L]['samples']=[[10000,False]]
        with patch('builtins.open',mock_open()) as target:
            write_speed(hist,10000)
            written=''.join(call.args[0] for call in target().write.call_args_list)
            self.assertIn('"kbps":{}',written);self.assertIn('"isolated":true',written)
if __name__=='__main__':unittest.main()
