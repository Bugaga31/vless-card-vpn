#!/usr/bin/env python3
"""Fail-closed R162 release checks; no keys or private inputs are read here."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

CERT = '3b9f1771645de07cb01b2303a868363e97cfa8d3ca4d6079dae307066f4f1e6b'
PACKAGE = 'com.vlesscardvpn.beta2'

def require(ok, message):
    if not ok:
        raise RuntimeError(message)

def test_totals(paths):
    paths = list(paths)
    require(bool(paths), 'No JVM reports')
    totals = {k: 0 for k in ('tests', 'failures', 'errors', 'skipped')}
    for path in paths:
        r = ET.parse(path).getroot()
        for k in totals:
            totals[k] += int(r.get(k, '0'))
    require(totals['tests'] >= 424, 'Missing JVM regressions')
    require(not any(totals[k] for k in ('failures', 'errors', 'skipped')), 'JVM gate failed')
    return totals

def verify_signature(text):
    require('Verifies' in text and 'DOES NOT VERIFY' not in text, 'APK signature invalid')
    for n in (1, 2, 3):
        require(re.search(rf'Verified using v{n} scheme[^\n]*: true', text), f'Signature v{n} absent')
    found = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-f0-9]{64})', text)
    require(found == [CERT], 'Certificate differs from published 1.2.50')

def verify_package(text, code=162):
    require(code >= 162, "Unexpected old release version")
    require(f"name='{PACKAGE}'" in text and f"versionCode='{code}'" in text and f"versionName='1.2.{code - 111}'" in text,
            'Unexpected package or version')

def run(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT)

def main():
    ref = os.environ['VERSION']
    require(re.fullmatch(r'(?:release|beta)-build-[0-9]+', ref), 'Invalid version ref')
    code = int(ref.rsplit('-', 1)[1])
    bt = Path(os.environ['ANDROID_HOME']) / 'build-tools/34.0.0'
    root = Path('release')
    tests = test_totals(Path('app/build/test-results/testDebugUnitTest').glob('*.xml'))
    lint = ET.parse('app/build/reports/lint-results-release.xml').getroot()
    issues = lint.findall('issue')
    require(not any(i.get('severity') in ('Error', 'Fatal') for i in issues), 'Release lint error')
    report = dict(candidate_sha=os.environ['GITHUB_SHA'], run_id=os.environ['GITHUB_RUN_ID'],
                  jvm=tests, lint={s: sum(i.get('severity') == s for i in issues) for s in ('Warning', 'Information')},
                  physical_phone_tested=False, apks=[])
    for name in ('VLESS-Card-Beta.apk', 'VLESS-Card-Beta-arm64.apk'):
        apk = root / name
        verify_signature(run(str(bt / 'apksigner'), 'verify', '--min-sdk-version', '21', '--verbose', '--print-certs', str(apk)))
        run(str(bt / 'zipalign'), '-c', '-p', '4', str(apk))
        package = run(str(bt / 'aapt2'), 'dump', 'badging', str(apk)).splitlines()[0]
        verify_package(package, code)
        manifest = run(str(bt / 'aapt2'), 'dump', 'xmltree', str(apk), '--file', 'AndroidManifest.xml')
        for attr in ('debuggable', 'testOnly'):
            require(not re.search(rf'android:{attr}[^\n]*0xffffffff', manifest), 'Debug/test-only APK')
        require('networkSecurityConfig' not in manifest, 'Unexpected release network security override')
        with zipfile.ZipFile(apk) as z:
            require(z.testzip() is None, 'Corrupt APK archive')
            for n in z.namelist():
                require(not any(x in n.lower() for x in ('debug-ca', 'test-ca', 'fixture')), 'Fixture or test CA in release APK')
                if re.fullmatch(r'classes\d*\.dex', n):
                    data = z.read(n)
                    require(not any(x in data for x in (b'e2e_connect', b'e2e_reload', b'e2e_test')), 'Debug E2E hook in release')
            if name == 'VLESS-Card-Beta.apk':
                for lib in ('libTor.so', 'libLyrebird.so', 'libgojni.so', 'libhev-socks5-tunnel.so'):
                    require(f'lib/x86_64/{lib}' in z.namelist(), 'Missing emulator native library: ' + lib)
        report['apks'].append(dict(file=name, sha256=hashlib.sha256(apk.read_bytes()).hexdigest(),
                                   certificate_sha256=CERT, package=package))
    (root / 'verification.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))

if __name__ == '__main__':
    main()
