"""Require executed scoped reports and identical production inputs before signing."""
from pathlib import Path
import json,subprocess,urllib.request,time

def git(*a): return subprocess.check_output(['git',*a],text=True).strip()
def load(branch,name):
 subprocess.run(['git','fetch','--quiet','origin',branch],check=True,timeout=60)
 return json.loads(git('show','FETCH_HEAD:docs/validation/'+name+'.json'))
def same(sha,paths):
 subprocess.run(['git','fetch','--quiet','origin',sha],check=True)
 return subprocess.run(['git','diff','--quiet',sha,'HEAD','--',*paths]).returncode==0
common=['app/src/main','app/src/debug','app/src/release','native','build.gradle.kts','settings.gradle.kts','gradle.properties','gradle','vpn-test-probe','security-test-probe']
last='reports pending'
for attempt in range(120):
 try:
  j=load('integration/telegram-evidence-r208','r208-jvm')
  d=load('integration/telegram-device-r208','r208-device')
  assert all(x=='success' for x in j['steps'].values()),j['steps']
  assert j['suites'] and not j['failures'] and not j['lint_errors']
  target=[s for s in j['suites'] if s['name']=='com.vlesscardvpn.RecoveryR207Test']
  assert len(target)==2 and all(int(s['tests'])==30 and int(s['skipped'])==0 for s in target), 'Module tests missing' 
  for name,count in {"StabilityR206Test":34,"CallQualityR206Test":25,"RouteStabilityR206Test":18}.items():
   suites=[s for s in j['suites'] if s['name']=='com.vlesscardvpn.'+name]
   assert len(suites)==2 and all(int(s['tests'])==count and int(s['skipped'])==0 for s in suites),name
  for name,count in {"com.vlesscardvpn.core.ModuleEvidenceR208Test":10,"com.vlesscardvpn.tor.MandatoryBridgesR208Test":14}.items():
   suites=[s for s in j['suites'] if s['name']==name]
   assert len(suites)==2 and all(int(x['tests'])==count and int(x['skipped'])==0 for x in suites),name
  paths=[s['path'] for s in j['suites']]
  assert any('app/build/test-results/testDebugUnitTest/' in p for p in paths)
  assert any('app/build/test-results/testReleaseUnitTest/' in p for p in paths)
  assert any('vpn-test-probe/build/test-results/' in p for p in paths)
  assert all(int(s.get('failures') or 0)==0 and int(s.get('errors') or 0)==0 for s in j['suites'])
  assert d['passed'] is True and d['runtime_tests_executed'] is True and d['count']==621
  assert d.get('ui_captures_executed') is True and len(d.get('screenshots',[]))==44, 'UI captures missing'
  review=load('integration/telegram-evidence-r208','r208-ui-review')
  assert review.get('passed') is True and review.get('source_sha')==d['source_sha'], 'Visual review missing or wrong source'
  assert review['screenshots']==d['screenshots'], 'Visual review does not match captured bytes' 
  assert same(j['source_sha'],common+['app/build.gradle.kts','app/src/test']), 'JVM source mismatch'
  assert same(d['source_sha'],common), 'Native source mismatch'
  # The device harness adds instrumentation-only dependencies/runner, no release overrides.
  clean=lambda s:'\n'.join(l for l in s.splitlines() if 'androidTestImplementation(' not in l and 'testInstrumentationRunner =' not in l)
  assert clean(git('show',d['source_sha']+':app/build.gradle.kts'))==clean(Path('app/build.gradle.kts').read_text()),'Device build inputs differ'
  Path('release').mkdir(exist_ok=True)
  Path('release/validation-jvm.json').write_text(json.dumps(j,indent=2)+'\n')
  Path('release/validation-device.json').write_text(json.dumps(d,indent=2)+'\n')
  Path('release/validation-ui-review.json').write_text(json.dumps(review,indent=2)+'\n')
  print('All scoped gates passed; production sources match executed JVM and native tests')
  break
 except Exception as e:
  last=str(e);print('Waiting for matching successful reports:',last,flush=True);time.sleep(25)
else:raise SystemExit('Publication blocked: '+last)
