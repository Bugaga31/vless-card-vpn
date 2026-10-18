import json, pathlib, tempfile, unittest
from cloud_real import check_queue, merge_results
from cloud_servers import endpoint
from cloud_manifest import build

class FullCloudQueueTest(unittest.TestCase):
    def test_all_reachable_variants_are_queued_beyond_old_caps(self):
        links=[f'vless://user@node{i}.invalid:443?security=reality&pbk=p' for i in range(7000)]
        hist={links[-1]:{'n':3,'ok':3,'ms':80}}
        queue=check_queue({'alive.txt':links,'alive_ru.txt':links[:900]},hist)
        self.assertEqual(7000,len(queue));self.assertEqual(links[-1],queue[0]);self.assertEqual(set(links),set(queue))
    def test_transport_credentials_are_not_collapsed_to_ip(self):
        base='vless://user@node.invalid:443?security=reality&pbk=p'
        variants=[base,base+'&sid=a',base+'&flow=xtls-rprx-vision',base+'&host=one.invalid',base.replace('user@','other@')]
        self.assertEqual(len(variants),len({endpoint(v)[2] for v in variants}))
        self.assertEqual(endpoint(base+'#first')[2],endpoint(base+'#second')[2])
    def shards(self,folder,todo):
        for i in range(4):
            values=todo[i::4]
            pathlib.Path(folder,f'result-{i}.json').write_text(json.dumps({'shard':i,'shards':4,'assigned':len(values),'results':{v:i for v in values}}))
    def test_complete_shards_merge_every_assigned_link(self):
        todo=[f'link{i}' for i in range(41)]
        with tempfile.TemporaryDirectory() as d:
            self.shards(d,todo);self.assertEqual(set(todo),set(merge_results(d,todo)))
    def test_missing_or_foreign_shard_cannot_publish_partial_cloud(self):
        todo=[f'link{i}' for i in range(41)]
        with tempfile.TemporaryDirectory() as d:
            self.shards(d,todo);p=pathlib.Path(d,'result-3.json');p.unlink()
            with self.assertRaises(ValueError):merge_results(d,todo)
            self.shards(d,todo);row=json.loads(p.read_text());row['results']['foreign']=10;p.write_text(json.dumps(row))
            with self.assertRaises(ValueError):merge_results(d,todo)
    def test_manifest_exceeds_old_300_but_retains_size_bound(self):
        hist={f'vless://user@node{i}.invalid:443?security=reality&pbk=p#{i}':{'n':5,'ok':5,'ms':50,'t':100000,'samples':[[100000,True]]} for i in range(7000)}
        out=build(hist,100000)
        self.assertGreater(len(out['nodes']),300);self.assertLessEqual(len(out['nodes']),6000)
        self.assertEqual(7000,out['working_total']);self.assertEqual(len(out['nodes']),out['manifest_total'])
        self.assertLess(len(json.dumps(out,ensure_ascii=False,separators=(',',':')).encode()),2*1024*1024)

if __name__=='__main__':unittest.main()
