#!/usr/bin/env python3
"""Prepare an explicitly unqualified prerelease. Never turn a failed E2E green."""
import hashlib
import json
import os
from pathlib import Path
from verify_candidate import CERT, verify_package, require

TAG='beta-v1.2.63-experimental'
PRIOR_SHA='940397aec9dd125d1c72c12a8d2d8b7f080a44b9'
PRIOR_RUN='https://github.com/Bugaga31/vless-card-vpn/actions/runs/40541665690'

def prepare(root, sha, run_id, docs):
    report=json.loads((root/'verification.json').read_text())
    require(report['candidate_sha']==sha and str(report['run_id'])==str(run_id),'Wrong candidate verification')
    require(report['jvm']['tests']>=787,'Missing current JVM regressions')
    require(all(report['jvm'].get(k)==0 for k in ('failures','errors','skipped')),'JVM regressions failed')
    require(report['physical_phone_tested'] is False,'Unexpected physical test claim')
    expected={'VLESS-Card-Beta.apk','VLESS-Card-Beta-arm64.apk'}
    require(len(report['apks'])==2 and {a['file'] for a in report['apks']}==expected,'Unexpected APK files')
    sums=[]
    for a in report['apks']:
        require(a['certificate_sha256']==CERT,'Certificate mismatch')
        verify_package(a['package'],174)
        old=root/a['file']
        digest=hashlib.sha256(old.read_bytes()).hexdigest()
        require(digest==a['sha256'],'APK artifact checksum mismatch')
        new=a['file'].replace('VLESS-Card-Beta','VLESS-Card-Beta-1.2.63-experimental')
        old.rename(root/new);a['file']=new;sums.append(f'{digest}  {new}')
    report['channel']='experimental'
    report['emulator_install_tested']=False
    report['e2e']={'status':'not_run_for_this_sha','prior_sha':PRIOR_SHA,'prior_run':PRIOR_RUN,
        'api29':'passed_on_prior_sha','api33':'failed_on_prior_sha: WARP Google HTTP 429 after bounded retry',
        'not_a_pass':True}
    (root/'verification.json').write_text(json.dumps(report,indent=2)+'\n')
    (root/'SHA256SUMS-Beta.txt').write_text('\n'.join(sums)+'\n')
    j=report['jvm']
    notice=f'''# Экспериментальный пререлиз — без полного E2E допуска

Это НЕ стабильный релиз и НЕ сборка со всеми зелёными проверками.
Полный E2E и установка/обновление на эмуляторе для этого SHA не запускались. Физический телефон не проверен.
Предыдущий SHA `{PRIOR_SHA}`: API 29 и masks matrix прошли; API 33 не прошёл из-за ответа Google HTTP 429 в WARP даже после одного повтора. VPN был активен, файл 1 МиБ передан; успешная доступность Google не подтверждена.
Предыдущий запуск: {PRIOR_RUN}

SHA этой сборки: `{sha}`
Сборка: https://github.com/Bugaga31/vless-card-vpn/actions/runs/{run_id}
JVM Debug: {j['tests']} тестов, 0 failures/errors/skipped. Release JVM и lint также обязательны в build job.
Подписи v1/v2/v3 проверены, сертификат постоянного Beta-ключа: `{CERT}`.
APK для `com.vlesscardvpn.beta2`, версия 1.2.63, код 174. SHA-256 в приложенном файле.
Стабильный v1.2.61 не заменяется. Нейромодель не добавлена.

---

'''
    (root/'notes-experimental.md').write_text(notice+docs.read_text())

if __name__=='__main__':
    require(os.environ.get('GITHUB_REPOSITORY')=='Bugaga31/vless-card-vpn','Wrong repository')
    require(os.environ.get('GITHUB_REF')=='refs/heads/experiment/prerelease-174','Wrong experimental branch')
    prepare(Path('release'),os.environ['GITHUB_SHA'],os.environ['GITHUB_RUN_ID'],Path('docs/release-1.2.63.md'))
