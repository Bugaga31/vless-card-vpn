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

if __name__ == '__main__':
    try:
        result = json.loads(sys.argv[1]); ok = valid(result, int(sys.argv[2]) if len(sys.argv)>2 else 0)
    except (ValueError, IndexError, TypeError):
        result = {'error':'invalid-or-missing-result'}; ok = False
    # Public test endpoints only; query tokens in redirects are unnecessary in diagnostics.
    safe = dict(result) if isinstance(result, dict) else {}
    if isinstance(safe.get('results'), list):
        safe['results'] = [{k:v for k,v in row.items() if k in ('code','bytes','ms')} for row in safe['results'] if isinstance(row,dict)]
    print(('PASS ' if ok else 'BAD ')+json.dumps(safe))
    sys.exit(0 if ok else 1)
