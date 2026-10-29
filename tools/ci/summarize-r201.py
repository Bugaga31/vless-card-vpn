from pathlib import Path
import json, os, xml.etree.ElementTree as ET
report={"source_sha":os.environ.get("VALIDATED_SOURCE_SHA",os.environ["GITHUB_SHA"]),"run_id":os.environ["GITHUB_RUN_ID"],"steps":{k:os.environ.get(k.upper()+"_OUTCOME","unknown") for k in ("source","host","jvm","lint")},"suites":[],"failures":[],"lint_errors":[]}
for p in sorted(Path('.').glob('*/build/test-results/test*UnitTest/TEST-*.xml')):
 root=ET.parse(p).getroot()
 report['suites'].append({"path":str(p),**{k:root.get(k) for k in ('name','tests','failures','errors','skipped')}})
 for case in root.iter('testcase'):
  for child in list(case):
   if child.tag in ('failure','error'):
    f={"class":case.get('classname'),"test":case.get('name'),"type":child.get('type'),"message":child.get('message','')[:1500],"detail":(child.text or '')[:2500]}
    report['failures'].append(f)
    print('::error title=Executed JVM regression::'+(str(f)).replace('%','%25').replace('\r','%0D').replace('\n','%0A'))
for p in Path('app/build/reports').glob('lint-results-debug.xml'):
 for issue in ET.parse(p).getroot().findall('issue'):
  if issue.get('severity') in ('Error','Fatal'):
   report['lint_errors'].append({"id":issue.get('id'),"message":issue.get('message'),"locations":[x.attrib for x in issue.findall('location')]})
Path('docs/validation').mkdir(parents=True,exist_ok=True)
Path('docs/validation/r201-jvm.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print('Executed suites:',len(report['suites']),'failed assertions:',len(report['failures']),'lint errors:',len(report['lint_errors']))

p=Path('docs/validation/r201-jvm.json');d=json.loads(p.read_text());log=Path('/tmp/jvm-r201.log');d['compile_errors']=log.read_text().splitlines()[-100:] if log.exists() else [];p.write_text(json.dumps(d,indent=2))
