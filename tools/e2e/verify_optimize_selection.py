#!/usr/bin/env python3
"""Check actual post-optimization selection, not whether this call changed it."""
import re
import sys

def verified(text):
    return re.search(r'optimize result=(?:true|false) dead_selected=false good_selected=([1-9]\d{0,2})(?:\s|$)',text) is not None

if __name__=='__main__':
    raise SystemExit(0 if verified(sys.stdin.read(4096)) else 1)
