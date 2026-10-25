#!/usr/bin/env python3
"""Fail closed: restored UI must not reload fixtures/restart the already-connected VPN."""
import re
import sys

def valid(text):
    return (text.count('fixture activity restored') >= 1
        and len(re.findall(r'fixture state mode=', text)) == 1
        and len(re.findall(r'service command=start state=', text)) == 1
        and len(re.findall(r'pool size=', text)) == 1
        and 'service command=start state=CONNECTED' not in text
        and 'teardown state=CONNECTED' not in text
        and 'connect failed:' not in text)

if __name__ == '__main__':
    ok = valid(sys.stdin.read(2_000_000))
    print('Activity recreation: ' + ('PASS (no command replay)' if ok else 'FAIL'))
    sys.exit(0 if ok else 1)
