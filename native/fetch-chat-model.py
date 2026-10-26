#!/usr/bin/env python3
"""Prepare the immutable compact GGUF as an APK asset; never commit weights."""
import hashlib, os, pathlib, sys, time, urllib.request
SIZE = 491400032
SHA = '74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db'
URL = 'https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/9217f5db79a29953eb74d5343926648285ec7e67/qwen2.5-0.5b-instruct-q4_k_m.gguf'
def valid(p):
    if not p.is_file() or p.stat().st_size != SIZE: return False
    with p.open('rb') as f: return hashlib.file_digest(f, 'sha256').hexdigest() == SHA
p = pathlib.Path(sys.argv[1]) / 'models/qwen05.gguf'
p.parent.mkdir(parents=True, exist_ok=True)
if valid(p): print('Bundled GGUF verified (cached)'); sys.exit(0)
tmp = p.with_suffix('.part')
try:
    with urllib.request.urlopen(URL + '?download=true&build=' + str(time.time_ns()), timeout=60) as response, tmp.open('wb') as out:
        n = 0
        while chunk := response.read(1024*1024):
            n += len(chunk)
            if n > SIZE: raise RuntimeError('Model exceeds pinned size')
            out.write(chunk)
        out.flush(); os.fsync(out.fileno())
    if not valid(tmp): raise RuntimeError('Bundled GGUF checksum/size mismatch')
    tmp.replace(p)
    print('Bundled GGUF verified:', SIZE, SHA)
finally:
    tmp.unlink(missing_ok=True)
