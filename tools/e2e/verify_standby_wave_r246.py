#!/usr/bin/env python3
"""Require two consecutive increases in a single bounded debug standby wave."""
import argparse, re, sys
PATTERN = re.compile(r'\bstandby wave=(\d+) pass=(\d+) before=(\d+) after=(\d+) start=(\d+) end=(\d+)\b')

def verify(lines):
    waves={}
    for line in lines:
        m=PATTERN.search(line)
        if not m: continue
        wave,number,before,after,start,end=map(int,m.groups())
        waves.setdefault(wave,[]).append((number,before,after,start,end))
    for wave, events in waves.items():
        for a,b in zip(events,events[1:]):
            n,before,after,start,end=a
            n2,before2,after2,start2,end2=b
            if (n2==n+1 and 1<=n<3 and 0<=before<after<3 and
                before2==after and before2<after2<=3 and
                wave<=start<=end<=start2<=end2<=wave+45000 and start2-end<30000):
                return True
    return False

def no_restart_during_fixture(lines):
    seen=False
    for line in lines:
        if 'fixture state mode=AUTO servers=8 selected=0' in line:
            seen=True
        if seen and 'teardown state=CONNECTED' in line:
            return False
    return seen

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--no-connected-teardown', action='store_true')
    args=parser.parse_args()
    ok=no_restart_during_fixture(sys.stdin) if args.no_connected_teardown else verify(sys.stdin)
    print(('fixture no connected teardown: ' if args.no_connected_teardown else 'standby multi-pass: ')+('PASS' if ok else 'FAIL'))
    sys.exit(0 if ok else 1)
