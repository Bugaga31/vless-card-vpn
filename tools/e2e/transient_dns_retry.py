#!/usr/bin/env python3
"""One diagnostic retry of the whole WARP probe, never a DNS failure -> PASS mapping.
Only the observed first-origin UnknownHostException with all other hard checks
passing qualifies. This does not establish the underlying DNS failure's cause.
"""
import json
import sys


def eligible(data):
    if not isinstance(data, dict) or data.get('vpn') is not True:
        return False
    rows = data.get('results')
    if not isinstance(rows, list) or len(rows) != 4 or not all(isinstance(r, dict) for r in rows):
        return False
    return (tuple(r.get('code') for r in rows) == (-1, 200, 200, 204)
            and str(rows[0].get('error', '')).startswith('UnknownHostException:')
            and type(rows[1].get('bytes')) is int and rows[1]['bytes'] >= 1048576
            and not any(r.get('error') for r in rows[1:]))


if __name__ == '__main__':
    try:
        result = eligible(json.load(sys.stdin))
    except (ValueError, TypeError):
        result = False
    sys.exit(0 if result else 1)
