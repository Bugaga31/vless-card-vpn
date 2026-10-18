#!/usr/bin/env python3
"""Measured cloud hints, not a claim that a GitHub server passes any ISP's DPI."""
import json,math,pathlib,time,urllib.parse
from cloud_real import key, source_key, measured_kbps

def build(history, now, operator_stats=None, recipes=None, source_map=None):
    nodes=[];quarantine=[]
    source_map = source_map or {}
    for link,h in history.items():
        if not isinstance(link,str) or not isinstance(h,dict):continue
        try:
            n=float(h.get('n',0));ok=float(h.get('ok',0));ms=int(h.get('ms',0));kb=measured_kbps(h,now);at=int(h.get('t',0))
        except (TypeError,ValueError,OverflowError):continue
        if not all(math.isfinite(v) for v in [n,ok,ms,kb]) or n<0 or ok<0 or ok>n or at>now+300 or now-at>86400:continue
        raw_samples=h.get('samples',[])
        if not isinstance(raw_samples,list):raw_samples=[]
        samples=sorted([s for s in raw_samples if isinstance(s,list) and len(s)==2 and isinstance(s[0],int) and now-86400<=s[0]<=now and isinstance(s[1],bool)],key=lambda s:s[0])
        k=key(link)
        if not k:continue
        if len(samples)>=3 and all(not s[1] for s in samples[-3:]):
            quarantine.append({'key':k,'reason':'three_recent_cloud_failures','retry_after':now+600});continue
        # The latest real check must pass before a route is distributed as working.
        if not samples or not samples[-1][1]:continue
        u=urllib.parse.urlsplit(link);q=urllib.parse.parse_qs(u.query)
        day_rate=sum(s[1] for s in samples)/len(samples)
        coverage=len(samples)>=12 and samples[-1][0]-samples[0][0]>=23*3600
        gold=coverage and day_rate>.95 and 0<ms<100 and u.scheme=='vless' and q.get('security')==['reality'] and q.get('flow')==['xtls-rprx-vision']
        prior=(ok+2)/(n+4)
        cloud_score=max(0,min(1,.6*prior+.2*(1-min(ms if ms>0 else 3000,3000)/3000)+.2*min(max(kb,0),30000)/30000))
        nodes.append({'link':link,'key':k,'tier':'gold' if gold else 'silver','cloudScore':round(cloud_score,5),
                      'sources': [u for u in source_map.get(source_key(link),[]) if public_source(u)][:16], 'checks':round(n,3),'https_ms':ms,'kbps':kb,'speed_measured_at':int(h.get('kb_t',0)) if kb>0 else 0,'measured_at':at,'daily_success_rate':round(day_rate,5) if coverage else None})
    nodes.sort(key=lambda x:(-x['kbps'],-x['cloudScore']))
    # Retain as many verified configurations as fit the bounded phone transport. Full works*.txt is untruncated.
    published=[]; payload_bytes=0
    for node in nodes:
        size=len(json.dumps(node,ensure_ascii=False,separators=(',',':')).encode('utf-8'))+1
        if len(published)>=6000 or payload_bytes+size>1500000: break
        published.append(node);payload_bytes+=size
    # Operator tags come ONLY from existing measured phone aggregates, never inferred from GitHub geography.
    return {'schema':1,'stamp':now,'expires':now+86400,'vantage':'github-not-user-isp','nodes':published,'working_total':len(nodes),'manifest_total':len(published),
            'quarantine':quarantine[:300],'isp_tags':operator_stats or {},'recipes':recipes or {'version':1,'families':{},'all':{}}}

def public_source(url):
    if not isinstance(url,str) or len(url)>2048: return False
    try:
        u=urllib.parse.urlsplit(url)
        return u.scheme=='https' and bool(u.hostname) and not u.username and not u.password and not u.fragment
    except ValueError: return False

def source_summary(nodes):
    result={}
    for node in nodes:
        for src in node.get('sources',[]):
            row=result.setdefault(src,{'working':0,'speed_samples':0,'best_kbps':0,'sum_kbps':0})
            row['working']+=1
            if node['kbps']>0:
                row['speed_samples']+=1; row['sum_kbps']+=node['kbps'];row['best_kbps']=max(row['best_kbps'],node['kbps'])
    for row in result.values(): row['mean_kbps']=row.pop('sum_kbps')//max(1,row['speed_samples'])
    return dict(sorted(result.items(),key=lambda p:(-p[1]['best_kbps'],-p[1]['working'])))

def main():
    root=pathlib.Path('out');hist=json.loads((root/'history.json').read_text()) if (root/'history.json').exists() else {}
    cloud=json.loads((root/'cloud.json').read_text()) if (root/'cloud.json').exists() else {}
    recipe_path=pathlib.Path('config/cloud-recipes.json')
    recipes=json.loads(recipe_path.read_text()) if recipe_path.exists() else None
    sources=json.loads((root/'source-map.json').read_text()) if (root/'source-map.json').exists() else {}
    result=build(hist,int(time.time()),cloud.get('net',{}),recipes,sources)
    (root/'source-stats.json').write_text(json.dumps(source_summary(result['nodes']),ensure_ascii=False,separators=(',',':')))
    (root/'cloud-manifest.json').write_text(json.dumps(result,ensure_ascii=False,separators=(',',':')))
    print('Measured manifest:',len(result['nodes']),'working,',len(result['quarantine']),'quarantined; unknown daily coverage is null')
if __name__=='__main__':main()
