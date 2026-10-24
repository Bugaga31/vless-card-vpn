#!/usr/bin/env python3
"""One backoff is eligible only for Google 429 after the other three hard checks pass.
This never accepts a 429 as success; the unchanged full probe must pass afterwards.
"""
import json
import sys

def eligible(data):
    if not isinstance(data,dict) or data.get('vpn') is not True: return False
    rows=data.get('results')
    if not isinstance(rows,list) or len(rows)!=4 or not all(isinstance(r,dict) for r in rows):return False
    return (tuple(r.get('code') for r in rows)==(204,200,429,204)
            and type(rows[1].get('bytes')) is int and rows[1]['bytes']>=1048576)

if __name__=='__main__':
    try: result=eligible(json.load(sys.stdin))
    except (ValueError,TypeError):result=False
    sys.exit(0 if result else 1)
