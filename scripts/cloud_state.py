#!/usr/bin/env python3
"""One version-independent, integrity-checked prior for every cloud stage. No network fallback."""
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys

STATE_FILES = ('history.json', 'cloud_state.json', 'mask-stats.json')
LIMIT = 128 * 1024 * 1024
OUTPUTS = STATE_FILES + ('alive.txt', 'alive_ru.txt', 'works.txt', 'works_ru.txt', 'speed.json',
    'stats.json', 'cloud.json', 'device-summary.json', 'devices.md', 'source-map.json',
    'source-stats.json', 'cloud-manifest.json')


def object_bytes(path):
    if path.is_symlink() or not path.is_file():
        raise ValueError('Cloud prior missing or unsafe: ' + path.name)
    with path.open('rb') as f:
        raw = f.read(LIMIT + 1)
    if len(raw) > LIMIT:
        raise ValueError('Cloud prior too large: ' + path.name)
    value = json.loads(raw)
    if not isinstance(value, dict):
        raise ValueError('Cloud prior must be an object: ' + path.name)
    return raw, value


def freeze(source, target):
    source, target = pathlib.Path(source), pathlib.Path(target)
    revision = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
    if not re.fullmatch(r'[0-9a-f]{40}', revision):
        raise ValueError('Invalid cloud revision')
    # Validate the entire snapshot before creating anything. Never infer empty state from a failure.
    data = {name: object_bytes(source / name)[0] for name in STATE_FILES}
    target.mkdir(parents=True, exist_ok=False)
    for name, raw in data.items():
        (target / name).write_bytes(raw)
    manifest = {'schema': 1, 'revision': revision, 'sha256': {
        name: hashlib.sha256(raw).hexdigest() for name, raw in data.items()}}
    (target / 'snapshot.json').write_text(json.dumps(manifest, sort_keys=True))
    return manifest


def prior(name, root=None):
    if name not in STATE_FILES:
        raise ValueError('Unknown cloud state file')
    root = pathlib.Path(root or os.environ.get('CLOUD_PRIOR_DIR', 'out/prior'))
    meta = json.loads((root / 'snapshot.json').read_text())
    if meta.get('schema') != 1 or not re.fullmatch(r'[0-9a-f]{40}', str(meta.get('revision', ''))):
        raise ValueError('Invalid cloud snapshot')
    raw, value = object_bytes(root / name)
    if hashlib.sha256(raw).hexdigest() != meta.get('sha256', {}).get(name):
        raise ValueError('Cloud snapshot checksum mismatch: ' + name)
    return value


def stage_outputs(output, destination):
    output, destination = pathlib.Path(output), pathlib.Path(destination)
    # Validate ALL expected outputs before copying. Unrelated branch files remain untouched.
    for name in OUTPUTS:
        p = output / name
        if p.is_symlink() or not p.is_file() or p.stat().st_size > LIMIT:
            raise ValueError('Missing or unsafe cloud output: ' + name)
        if name.endswith('.json'):
            object_bytes(p)
    for name in OUTPUTS:
        shutil.copyfile(output / name, destination / name)
    # This public receipt contains only revision/hash data, never report contents or device IDs.
    meta = json.loads((output / 'prior' / 'snapshot.json').read_text())
    receipt = {'schema': 1, 'previous_revision': meta['revision'], 'sha256': {
        name: hashlib.sha256((output / name).read_bytes()).hexdigest() for name in OUTPUTS}}
    (destination / 'continuity.json').write_text(json.dumps(receipt, sort_keys=True))


if __name__ == '__main__':
    if len(sys.argv) != 4 or sys.argv[1] not in ('freeze', 'stage'):
        raise SystemExit('Usage: cloud_state.py freeze|stage SOURCE DESTINATION')
    (freeze if sys.argv[1] == 'freeze' else stage_outputs)(sys.argv[2], sys.argv[3])
