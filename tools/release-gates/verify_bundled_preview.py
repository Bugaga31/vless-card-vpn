#!/usr/bin/env python3
"""Minimal experimental APK gates; stable verify_candidate.py is unchanged."""
import hashlib,json,os,re,zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
from verify_candidate import CERT, require, run, verify_signature, verify_package
SIZE=491400032
SHA='74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db'
def verify_model(z, size=SIZE, sha=SHA):
    info=z.getinfo('assets/models/qwen05.gguf')
    require(info.file_size==size,'Missing/truncated bundled GGUF')
    require(info.compress_type==zipfile.ZIP_STORED,'Model must be stored uncompressed')
    h=hashlib.sha256(); n=0
    with z.open(info) as f:
        while chunk:=f.read(1024*1024): h.update(chunk); n+=len(chunk)
    require(n==size and h.hexdigest()==sha,'Bundled GGUF checksum mismatch')
def digest(p):
    with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def main():
    require(os.environ['VERSION']=='beta-build-170','Wrong experimental version')
    bt=Path(os.environ['ANDROID_HOME'])/'build-tools/34.0.0'
    totals={k:0 for k in ['tests','failures','errors','skipped']}
    paths=list(Path('app/build/test-results/testDebugUnitTest').glob('*.xml'))
    for p in paths:
        r=ET.parse(p).getroot()
        for k in totals:totals[k]+=int(r.get(k,0))
    require(totals['tests']>=7 and not any(totals[k] for k in ['failures','errors','skipped']),'Targeted AI regressions failed/missing')
    report=dict(candidate_sha=os.environ['GITHUB_SHA'],run_id=os.environ['GITHUB_RUN_ID'],jvm=totals,
                test_scope='Targeted AI unit tests only; full JVM/lint/E2E/emulator matrix not run for this experimental hotfix',
                physical_phone_tested=False,android_inference_tested=False,
                bundled_model=dict(bytes=SIZE,sha256=SHA,asset='assets/models/qwen05.gguf'),apks=[])
    for name in ['VLESS-Card-Beta.apk','VLESS-Card-Beta-arm64.apk']:
        p=Path('release')/name
        verify_signature(run(str(bt/'apksigner'),'verify','--min-sdk-version','21','--verbose','--print-certs',str(p)))
        run(str(bt/'zipalign'),'-c','-p','4',str(p))
        package=run(str(bt/'aapt2'),'dump','badging',str(p)).splitlines()[0];verify_package(package,170)
        manifest=run(str(bt/'aapt2'),'dump','xmltree',str(p),'--file','AndroidManifest.xml')
        require(not re.search(r'android:(debuggable|testOnly)[^\n]*0xffffffff',manifest),'Debug/test APK')
        require('networkSecurityConfig' not in manifest,'Unexpected release CA override')
        with zipfile.ZipFile(p) as z:
            verify_model(z)
            require('lib/arm64-v8a/libequilibrium_ai.so' in z.namelist(),'ARM64 inference library missing')
            for n in z.namelist():
                require(not any(x in n.lower() for x in ['debug-ca','test-ca','fixture']),'Test material in APK')
                if re.fullmatch(r'classes\d*\.dex',n):
                    data=z.read(n)
                    require(not any(x in data for x in [b'e2e_connect',b'e2e_reload',b'e2e_test',b'fixture-only']),'Debug E2E hook in APK')
        report['apks'].append(dict(file=name,sha256=digest(p),certificate_sha256=CERT,package=package))
    Path('release/verification.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))
if __name__=='__main__':main()
