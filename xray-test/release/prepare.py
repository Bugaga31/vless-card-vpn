"""Prepare native license assets and a source/relinking kit; never publishes anything."""
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import sys
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parent
WORK = REPO / '.xray-release-work'
ASSETS = ROOT / 'src/main/assets/licenses'
WRAPPER_REV = 'c634d1baea97e94320c0bf6a9cf637369c4f11d4'
SPDX_REV = '3ac5a9c241d97f95b22a5e366c9c841404a35639'


def fetch(url, max_bytes=100_000_000):
    request = urllib.request.Request(url, headers={'User-Agent': 'VLESS-Card-Test-release'})
    with urllib.request.urlopen(request, timeout=90) as response:
        data = response.read(max_bytes + 1)
    if len(data) > max_bytes:
        raise RuntimeError('Download exceeds expected size limit')
    return data


def extract_source(data, dest):
    dest.mkdir(parents=True, exist_ok=True)
    with tarfile.open(fileobj=io.BytesIO(data), mode='r:gz') as archive:
        for member in archive:
            parts = PurePosixPath(member.name).parts
            if not parts or '..' in parts or PurePosixPath(member.name).is_absolute():
                raise RuntimeError('Unsafe archive path')
            relative = Path(*parts[1:])
            if str(relative) == '.':
                continue
            target = dest / relative
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
            elif member.isfile():
                target.parent.mkdir(parents=True, exist_ok=True)
                source = archive.extractfile(member)
                if source is None:
                    raise RuntimeError('Missing archive member')
                with source, target.open('wb') as output:
                    shutil.copyfileobj(source, output)
            else:
                raise RuntimeError('Links and special archive entries are not allowed')


def prepare():
    WORK.mkdir(exist_ok=True)
    ASSETS.mkdir(parents=True, exist_ok=True)
    for name in ('GPL-3.0-only', 'LGPL-3.0-only', 'MPL-2.0'):
        text = fetch('https://' + 'raw.githubusercontent.com' + '/spdx/license-list-data/' + SPDX_REV + '/text/' + name + '.txt', 200_000)
        if len(text) < 1000:
            raise RuntimeError('Incomplete license text')
        (ASSETS / f'{name}.txt').write_bytes(text)
    wrapper = WORK / 'AndroidLibXrayLite'
    if wrapper.exists():
        shutil.rmtree(wrapper)
    extract_source(fetch('https://' + 'api.github.com' + '/repos/2dust/AndroidLibXrayLite/tarball/' + WRAPPER_REV), wrapper)
    if '5ca6f4b7d4dc' not in (wrapper / 'go.mod').read_text():
        raise RuntimeError('Unexpected Xray source revision')
    # Include the pinned native dependency source and its own notices, not just links.
    subprocess.run(['go', 'mod', 'vendor'], cwd=wrapper, check=True, timeout=900)
    if not (wrapper / 'vendor/github.com/xtls/xray-core/core').is_dir():
        raise RuntimeError('Xray source missing from source kit')
    for path in wrapper.rglob('*'):
        if path.is_file() and path.name.lower().startswith(('license', 'licence', 'copying', 'notice', 'copyright', 'patents')):
            target = ASSETS / 'native' / path.relative_to(wrapper)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, target)
    java_sources = fetch('https://github.com/2dust/AndroidLibXrayLite/releases/download/v26.8.20/libv2ray-sources.jar')
    if hashlib.sha256(java_sources).hexdigest() != '171436d727b377f9067613b3ca929f21a534d66c1320f7872d96d3e247e61125':
        raise RuntimeError('Generated Java source checksum mismatch')
    (WORK / 'libv2ray-sources.jar').write_bytes(java_sources)
    shutil.copy2(ROOT / 'THIRD_PARTY.md', ASSETS / 'NOTICE.md')
    print('Prepared license assets and vendored native source kit')


def bundle():
    if not (WORK / 'AndroidLibXrayLite/vendor/modules.txt').exists():
        raise RuntimeError('Run prepare before bundling')
    destination = REPO / 'release'
    destination.mkdir(exist_ok=True)
    kit = WORK / 'Application'
    if kit.exists():
        shutil.rmtree(kit)
    kit.mkdir()
    shutil.copytree(ROOT, kit / 'xray-test', ignore=shutil.ignore_patterns('build', '__pycache__', '*.pyc'))
    for name in ('build.gradle.kts', 'gradle.properties', 'gradlew', 'gradlew.bat'):
        shutil.copy2(REPO / name, kit / name)
    shutil.copytree(REPO / 'gradle', kit / 'gradle')
    # Standalone settings omit only the unrelated legacy sing-box app.
    settings = (REPO / 'settings.gradle.kts').read_text().replace('include(":app")', '')
    (kit / 'settings.gradle.kts').write_text(settings)
    revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=REPO, text=True).strip()
    (WORK / 'BUILD_INFO.json').write_text(json.dumps({'application_commit': revision, 'native_wrapper_commit': WRAPPER_REV, 'spdx_commit': SPDX_REV, 'core_aar_sha256': '670cf11d9d10a6bb6548ac4f593acfa4339155732f6f8de4d45923f30a74deed'}, indent=2))
    (WORK / 'REBUILD.txt').write_text('Application source is in Application/. Native wrapper and vendored dependencies are in AndroidLibXrayLite/. Generated Java binding sources are in libv2ray-sources.jar.\nUse JDK 17, Android SDK 34 and Gradle 8.7. From Application/: chmod +x gradlew; ./gradlew :xray-test:assembleDebug. Internet is required for Android/Gradle dependencies and the pinned official core AAR.\nTo relink with a modified core, build the native wrapper using its README (Go 1.26, Android NDK, gomobile); replace the prepareXray download URL/checksum in the app build script with your rebuilt AAR and its SHA-256, or replace that task with a local file dependency. No special signing key is needed: install your own debug-signed build, uninstalling a conflicting test installation if necessary.\nNo restriction is imposed on modifying the library or reverse engineering for debugging those modifications. The standalone settings file omits only the unrelated legacy app.\n')
    archive_path = destination / 'VLESS-Card-Test-Xray-sources.zip'
    with zipfile.ZipFile(archive_path, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in sorted(WORK.rglob('*')):
            if path.is_file():
                archive.write(path, path.relative_to(WORK))
    print(f'Wrote source kit: {archive_path.name}')


if __name__ == '__main__':
    if sys.argv[1:] == ['prepare']:
        prepare()
    elif sys.argv[1:] == ['bundle']:
        bundle()
    else:
        raise SystemExit('Usage: prepare.py prepare|bundle')
