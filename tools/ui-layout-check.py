#!/usr/bin/env python3
"""Inspect real accessibility XML; large text may put home status below the fold."""
import re
import sys
import xml.etree.ElementTree as ET

HOME = ('Готовы к подключению', 'Маршрут подключён', 'Серверы выберу сам')

def home_visible(xml):
    root = ET.fromstring(xml)
    return any(any(label in node.get('text', '') for label in HOME) for node in root.iter('node'))

def scroll_points(xml):
    candidates = []
    for node in ET.fromstring(xml).iter('node'):
        if node.get('scrollable') != 'true':
            continue
        match = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds', ''))
        if not match:
            continue
        x1, y1, x2, y2 = map(int, match.groups())
        if x2 - x1 < 48 or y2 - y1 < 96:
            continue
        candidates.append(((x2-x1)*(y2-y1), ((x1+x2)//2, y1+(y2-y1)*3//4, (x1+x2)//2, y1+(y2-y1)//4)))
    if not candidates:
        raise ValueError('No visible scroll container: cannot prove home content is reachable')
    return max(candidates)[1]

if __name__ == '__main__':
    mode, path = sys.argv[1:3]
    xml = open(path, encoding='utf-8').read()
    if mode == 'home':
        sys.exit(0 if home_visible(xml) else 1)
    elif mode == 'scroll':
        print(*scroll_points(xml))
    else:
        raise ValueError('unknown operation')
