from pathlib import Path
import hashlib,json,subprocess,shutil,zipfile
review=json.loads(Path('docs/validation/desktop-r196-reviewed.json').read_text())
out=Path('desktop-release');out.mkdir(exist_ok=True)
reports={}
for rid,platform in [('win-x64','windows'),('linux-x64','linux')]:
 root=Path('payload')/platform
 actual=json.loads((root/(rid+'.json')).read_text());expected=review['reports'][rid]
 assert actual==expected,'Report differs from individually reviewed evidence'
 assert actual['core_tests_passed'] and actual['embedded_core_config_passed'] and actual['window_open_close_passed']
 for filename,digest in actual['assets'].items():
  assert hashlib.sha256((root/filename).read_bytes()).hexdigest()==digest,'Artifact digest mismatch'
 for filename,digest in actual['files'].items():
  assert hashlib.sha256(subprocess.check_output(['git','show',actual['source_sha']+':'+filename])).hexdigest()==digest,'Source inventory mismatch'
  assert hashlib.sha256(Path(filename).read_bytes()).hexdigest()==digest,'Publish source differs from tested code'
 for filename in actual['assets']:
  if not filename.endswith('.png'):shutil.copy2(root/filename,out/filename)
 reports[rid]=actual
assert review['passed'] is True
(out/'Desktop-v0.3.0-verification.json').write_text(json.dumps(review,indent=2)+'\n')
shutil.copy2('desktop/README-0.3.md',out/'Desktop-README-0.3.0.md')
with zipfile.ZipFile(out/'Desktop-Licenses-0.3.0.zip','w',zipfile.ZIP_DEFLATED) as archive:
 for platform in ['windows','linux']:
  for name in ['THIRD-PARTY-NOTICES.txt','Xray-LICENSE.txt']:
   archive.write(Path('payload')/platform/name,platform+'/'+name)
(out/'Desktop-SHA256SUMS.txt').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.name+'\n' for p in sorted(out.iterdir()) if p.is_file()))
print('Reviewed desktop source, screenshots and package bytes verified')
