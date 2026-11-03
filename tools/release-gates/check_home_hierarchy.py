#!/usr/bin/env python3
"""Require actual home controls, not merely four labels on the launch intro."""
import sys
import xml.etree.ElementTree as E

def is_home(root, package):
    labels={v.strip() for n in root.iter('node') if n.get('package')==package
            for v in (n.get('text',''),n.get('content-desc','')) if v.strip()}
    return ('Подключить' in labels and 'Главная' in labels and 'Серверы' in labels
            and 'Открыть приложение' not in labels)

if __name__=='__main__':
    try:ok=is_home(E.parse(sys.argv[1]).getroot(),sys.argv[2])
    except (OSError,E.ParseError,IndexError):ok=False
    raise SystemExit(0 if ok else 1)
