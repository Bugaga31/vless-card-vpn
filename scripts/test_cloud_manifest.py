import unittest
from cloud_manifest import build
L='vless://00000000-0000-0000-0000-000000000001@node.example:443?security=reality&pbk=public&flow=xtls-rprx-vision&sni=example.com'
class CloudManifestTest(unittest.TestCase):
 def history(self,samples):return {L:{'n':20,'ok':20,'ms':50,'kb':20000,'t':100000,'samples':samples}}
 def test_gold_requires_observed_day_not_guessed_ema(self):
  out=build(self.history([[100000,True]]),100000);self.assertEqual('silver',out['nodes'][0]['tier']);self.assertIsNone(out['nodes'][0]['daily_success_rate'])
  out=build(self.history([[16000+i*7000,True] for i in range(13)]),100000);self.assertEqual('gold',out['nodes'][0]['tier'])
 def test_quarantined_links_are_not_distributed(self):
  out=build(self.history([[99000,False],[99500,False],[100000,False]]),100000)
  self.assertEqual([],out['nodes']);self.assertEqual(1,len(out['quarantine']));self.assertNotIn('link',out['quarantine'][0])
 def test_unknown_operator_scores_remain_absent(self):
  out=build(self.history([[100000,True]]),100000);self.assertEqual({},out['isp_tags']);self.assertIn('not-user-isp',out['vantage'])
 def test_latest_failure_cannot_be_called_working(self):
  self.assertEqual([],build(self.history([[99000,True],[100000,False]]),100000)['nodes'])
 def test_operator_data_only_copied_from_measured_aggregate(self):
  data={'Моб.: test':{'a':[10,90,5]}};self.assertEqual(data,build({},100000,data)['isp_tags'])
if __name__=='__main__':unittest.main()
