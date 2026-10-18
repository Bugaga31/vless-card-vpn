import copy, unittest
from cloud_masks import transform, record, measured_hints, trusted_stats
class CloudMaskTest(unittest.TestCase):
 def ob(self,sec='tls',pin='aa'):
  return {'protocol':'vless','settings':{},'streamSettings':{'network':'raw','security':sec,'tlsSettings':{'pinnedPeerCertSha256':pin,'serverName':'original'},'realitySettings':{'serverName':'required'}}}
 def test_cannot_replace_reality_or_unpinned_sni(self):
  self.assertIsNone(transform(self.ob('reality'),'vc.sni.vk.com.fast'))
  self.assertIsNone(transform(self.ob(pin=''),'vc.sni.ya.ru.fast'))
  x=transform(self.ob('reality'),'chrome.h5');self.assertEqual('required',x['streamSettings']['realitySettings']['serverName'])
 def test_preserves_pin_and_source(self):
  x=self.ob(); original=copy.deepcopy(x); y=transform(x,'vc.sni.yandex.ru.fast')
  self.assertEqual(original,x);self.assertEqual('aa',y['streamSettings']['tlsSettings']['pinnedPeerCertSha256'])
  self.assertEqual('yandex.ru',y['streamSettings']['tlsSettings']['serverName'])
 def test_not_remote_arbitrary_code_or_broken_transport(self):
  self.assertIsNone(transform(self.ob(),'shell:run'))
  x=self.ob();x['streamSettings']['network']='grpc';self.assertIsNone(transform(x,'chrome.h5'))
 def test_no_promotion_from_single_success_or_old_failure(self):
  s={};record(s,'node','chrome.h5',True,10,1000,10000);self.assertEqual([],measured_hints(s,10000))
  record(s,'node','chrome.h5',True,10,1000,10001);record(s,'node','chrome.h5',True,10,1000,10002)
  self.assertEqual(['chrome.h5'],measured_hints(s,10002))
  record(s,'node','chrome.h5',False,0,0,10003);self.assertEqual([],measured_hints(s,10003))
 def test_expired_and_malformed_evidence_not_trusted(self):
  s={};record(s,'node','chrome.h5',True,10,1000,10000)
  self.assertEqual({},trusted_stats(s,10000+8*86400));self.assertEqual({},trusted_stats({'x':{'mask':'evil','at':10000}},10000))
if __name__=='__main__':unittest.main()
