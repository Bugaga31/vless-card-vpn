#!/usr/bin/env python3
"""Capture only failure-time diagnostic logs, never fixture config/certificates/keys or payloads.
Raw logs go to bounded artifacts, not console. The console gets size/truncation metadata only.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import sys

LIMIT = 2_000_000
CASES = ['setup','single','byedpi','multi','tpws','ownmask','xraydpi','services','auto','auto-eight','auto-chain','proxy','warp']

def tail_file(path, limit=LIMIT):
    try:
        with path.open('rb') as f:
            f.seek(0, 2); size=f.tell(); f.seek(max(0,size-limit)); return f.read(limit),size
    except FileNotFoundError:
        return None,0

def snapshot(directory, case, failure, stream):
    prefix=directory/f'failure-{case}-{failure}'
    started=datetime.now(timezone.utc).isoformat()
    data=bytearray(); total=0
    while True:
        chunk=stream.read(65536)
        if not chunk: break
        total+=len(chunk);data.extend(chunk)
        if len(data)>LIMIT: del data[:-LIMIT]
    records={}
    sources=[('logcat',bytes(data),total)]
    for label in ('server','access'):
        b,size=tail_file(directory/f'{label}.log'); sources.append((label,b,size))
    for label,b,size in sources:
        if b is not None:
            Path(str(prefix)+f'-{label}.txt').write_bytes(b)
        records[label]={'present':b is not None,'source_bytes':size,'saved_bytes':len(b) if b is not None else 0,
            'truncated':size>LIMIT,'sha256':hashlib.sha256(b).hexdigest() if b is not None else None}
    meta={'case':case,'failure':failure,'started_utc':started,'finished_utc':datetime.now(timezone.utc).isoformat(),'logs':records}
    Path(str(prefix)+'-snapshot.txt').write_text(json.dumps(meta,separators=(',',':')))
    return meta

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--directory',type=Path,required=True);p.add_argument('--case',choices=CASES,required=True);p.add_argument('--failure',type=int,required=True);a=p.parse_args()
    if not 1<=a.failure<=1000: p.error('failure must be 1..1000')
    meta=snapshot(a.directory,a.case,a.failure,sys.stdin.buffer)
    print('::notice title=E2E failure snapshot::'+json.dumps(meta,separators=(',',':')))
