#!/usr/bin/env python3
"""Build pinned CPU-only llama.cpp for 64-bit Android. No model or credentials."""
import os, pathlib, shutil, subprocess, sys
PIN = '9b8446b91434e0c0cdc293baf0b29c099eaaa0c7'
root = pathlib.Path(__file__).resolve().parent
sdk, dest, mode = map(str, sys.argv[1:])
sdk, dest = pathlib.Path(sdk), pathlib.Path(dest)
source = dest.parent / 'llama-source'
def run(*args): subprocess.run(list(map(str, args)), check=True)
if not (source / '.git').is_dir():
    source.mkdir(parents=True, exist_ok=True)
    run('git', '-C', source, 'init', '-q')
    run('git', '-C', source, 'remote', 'add', 'origin', 'https://github.com/ggml-org/llama.cpp.git')
actual = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True, stderr=subprocess.DEVNULL).strip() if (source / 'include/llama.h').exists() else ''
if actual != PIN:
    run('git', '-C', source, 'fetch', '--depth', '1', 'origin', PIN)
    run('git', '-C', source, 'checkout', '--detach', PIN)
if subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip() != PIN:
    raise SystemExit('llama.cpp pin mismatch')
if subprocess.check_output(['git', '-C', str(source), 'status', '--porcelain'], text=True).strip():
    raise SystemExit('Modified llama.cpp source refused')
cmake = sdk / 'cmake/3.22.1/bin/cmake'
ninja = sdk / 'cmake/3.22.1/bin/ninja'
ndk = sdk / 'ndk/27.2.12479018'
for abi in (['arm64-v8a'] if mode == 'arm' else ['arm64-v8a', 'x86_64']):
    build = dest.parent / ('llama-build-' + abi)
    run(cmake, '-S', root / 'local-ai', '-B', build, '-G', 'Ninja', '-DCMAKE_MAKE_PROGRAM='+str(ninja),
        '-DCMAKE_TOOLCHAIN_FILE='+str(ndk / 'build/cmake/android.toolchain.cmake'),
        '-DANDROID_ABI='+abi, '-DANDROID_PLATFORM=android-24', '-DANDROID_STL=c++_static',
        '-DCMAKE_BUILD_TYPE=Release', '-DLLAMA_SOURCE='+str(source))
    run(cmake, '--build', build, '--target', 'equilibrium_ai', '--parallel', '2')
    out = dest / abi; out.mkdir(parents=True, exist_ok=True)
    shutil.copy2(build / 'libequilibrium_ai.so', out / 'libequilibrium_ai.so')
