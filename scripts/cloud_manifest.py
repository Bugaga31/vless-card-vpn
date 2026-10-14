#!/usr/bin/env python3
"""Measured cloud hints, not a claim that a GitHub server passes any ISP's DPI."""
import json,math,pathlib,time,urllib.parse
from cloud_real import key

def build(history, now, operator_stats=None, recipes=None):
    nodes=[];quarantine=[]
    for link,h in history.items():
        if not isinstance(link,str) or not isinstance(h,dict):continue
        try:
            n=float(h.get('n',0));ok=float(h.get('ok',0));ms=int(h.get('ms',0));kb=int(h.get('kb',0));at=int(h.get('t',0))
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
                      'checks':round(n,3),'https_ms':ms,'kbps':kb,'measured_at':at,'daily_success_rate':round(day_rate,5) if coverage else None})
    nodes.sort(key=lambda x:(x['tier']!='gold',-x['cloudScore']))
    # Operator tags come ONLY from existing measured phone aggregates, never inferred from GitHub geography.
    return {'schema':1,'stamp':now,'expires':now+86400,'vantage':'github-not-user-isp','nodes':nodes[:300],
            'quarantine':quarantine[:300],'isp_tags':operator_stats or {},'recipes':recipes or {'version':1,'families':{},'all':{}}}

def main():
    root=pathlib.Path('out');hist=json.loads((root/'history.json').read_text()) if (root/'history.json').exists() else {}
    cloud=json.loads((root/'cloud.json').read_text()) if (root/'cloud.json').exists() else {}
    recipe_path=pathlib.Path('config/cloud-recipes.json')
    recipes=json.loads(recipe_path.read_text()) if recipe_path.exists() else None
    result=build(hist,int(time.time()),cloud.get('net',{}),recipes)
    (root/'cloud-manifest.json').write_text(json.dumps(result,ensure_ascii=False,separators=(',',':')))
    print('Measured manifest:',len(result['nodes']),'working,',len(result['quarantine']),'quarantined; unknown daily coverage is null')
if __name__=='__main__':main()
