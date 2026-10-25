#!/usr/bin/env python3
"""Locate the unique visible app-owned intro button; never tap arbitrary coordinates."""
import re
import sys
import xml.etree.ElementTree as E

def target(root, package):
    found=[]
    def visit(node, parents):
        if node.get('package') == package and 'Открыть приложение' in (node.get('text',''),node.get('content-desc','')):
            # Compose may expose the text as a non-clickable child of the button.
            for n in [node]+list(reversed(parents)):
                if n.get('package') != package or n.get('enabled') != 'true' or n.get('clickable') != 'true':
                    continue
                m=re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',n.get('bounds',''))
                if m:
                    x1,y1,x2,y2=map(int,m.groups())
                    if 0 <= x1 < x2 <= 10000 and 0 <= y1 < y2 <= 10000:
                        found.append(((x1+x2)//2,(y1+y2)//2))
                break
        for child in node:visit(child,parents+[node])
    visit(root,[])
    return found[0] if len(found)==1 else None

if __name__=='__main__':
    try:result=target(E.parse(sys.argv[1]).getroot(),sys.argv[2])
    except (OSError,E.ParseError,IndexError):result=None
    if result is None:raise SystemExit(1)
    print(*result)
