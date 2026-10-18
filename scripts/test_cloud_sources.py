import unittest
from cloud_manifest import build, source_summary, public_source
from cloud_real import source_key
from cloud_servers import source_url

class CloudSourceTest(unittest.TestCase):
    def test_provenance_keeps_config_credentials_distinct_and_ignores_display_name(self):
        a='vless://one@node.invalid:443?security=reality&pbk=p'
        b=a.replace('one@','two@')
        self.assertNotEqual(source_key(a),source_key(b))
        self.assertEqual(source_key(a+'#one'),source_key(a+'#two'))
        h={x:{'n':5,'ok':5,'ms':100,'t':100000,'samples':[[100000,True]],'kb':kb,'kb_t':100000,'kb_isolated':True} for x,kb in [(a,30000),(b,1000)]}
        out=build(h,100000,source_map={source_key(a):['https://public.example/fast'],source_key(b):['https://public.example/slow']})
        self.assertEqual([30000,1000],[x['kbps'] for x in out['nodes']])
        self.assertEqual(['https://public.example/fast'],out['nodes'][0]['sources'])
        stats=source_summary(out['nodes']);self.assertEqual(30000,stats['https://public.example/fast']['best_kbps'])
        self.assertEqual(1,stats['https://public.example/fast']['speed_samples'])
    def test_provenance_does_not_accept_non_https_or_credentials(self):
        for x in ['file:///private','http://example.org/sub','https://user:secret@example.org/sub','https://example.org/sub#secret',None]:self.assertFalse(public_source(x))
        self.assertTrue(public_source('https://raw.githubusercontent.com/user/repo/main/sub.txt'))
    def test_collector_paths_are_published_as_full_subscription_urls(self):
        self.assertEqual("https://raw.githubusercontent.com/user/repo/main/sub.txt",source_url("user/repo/main/sub.txt"))
        self.assertEqual("https://example.org/sub",source_url("https://example.org/sub"))
        self.assertTrue(public_source(source_url("user/repo/main/sub.txt")))
    def test_missing_provenance_stays_unknown(self):
        self.assertEqual({},source_summary([]));self.assertEqual([],build({},100000)['nodes'])
