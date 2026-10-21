#!/usr/bin/env python3
"""Validate actual cross-UID VPN transfers. Never interpret a blocked control URL as a PASS."""
import json
import sys

def valid(result, third_min=0):
    if not isinstance(result, dict) or result.get('vpn') is not True:
        return False
    rows = result.get('results')
    if not isinstance(rows, list) or len(rows) != 4 or not all(isinstance(r, dict) for r in rows):
        return False
    return (rows[0].get('code') == 204 and rows[1].get('code') == 200 and
            isinstance(rows[1].get('bytes'), int) and rows[1]['bytes'] >= 1048576 and
            rows[2].get('code') == 200 and isinstance(rows[2].get('bytes'), int) and
            rows[2]['bytes'] >= third_min and rows[3].get('code') == 204)

def retryable_dns_handoff(result):
    """Only a single transient DNS miss amid otherwise complete transfers; never HTTP/TLS failures.
    Caller must also prove a physical-network change DURING that attempt and retry at most once.
    """
    if not isinstance(result, dict) or result.get('vpn') is not True:
        return False
    rows = result.get('results')
    if not isinstance(rows, list) or len(rows) != 4 or not all(isinstance(r,dict) for r in rows):
        return False
    failed = [i for i,r in enumerate(rows) if r.get('code') == -1 and str(r.get('error','')).startswith('UnknownHostException:')]
    if len(failed) != 1:
        return False
    # A DNS miss may request a new FULL probe, but cannot itself satisfy valid().
    candidate = dict(result);candidate['results'] = [dict(r) for r in rows]
    i = failed[0]
    candidate['results'][i] = {'code':204,'bytes':0} if i in (0,3) else {'code':200,'bytes':1048576}
    return valid(candidate,1048576)

if __name__ == '__main__':
    handoff_mode = False
    try:
        handoff_mode = sys.argv[1] == '--dns-handoff'
        result = json.loads(sys.argv[2] if handoff_mode else sys.argv[1])
        ok = retryable_dns_handoff(result) if handoff_mode else valid(result, int(sys.argv[2]) if len(sys.argv)>2 else 0)
    except (ValueError, IndexError, TypeError):
        result = {'error':'invalid-or-missing-result'}; ok = False
    safe = dict(result) if isinstance(result, dict) else {}
    if isinstance(safe.get('results'), list):
        safe['results'] = [{k:v for k,v in row.items() if k in ('code','bytes','ms')} for row in safe['results'] if isinstance(row,dict)]
    label = ('RETRYABLE-DNS ' if ok else 'NOT-RETRYABLE ') if handoff_mode else ('PASS ' if ok else 'BAD ')
    print(label+json.dumps(safe))
    sys.exit(0 if ok else 1)
