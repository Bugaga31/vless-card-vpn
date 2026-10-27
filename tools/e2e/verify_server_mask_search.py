#!/usr/bin/env python3
"""Search-summary gate only; emulator must also prove server/body transfer.
Rejects old standalone-DPI output and partial/unknown results.
"""
import re
import sys


def verified(text, expected=3):
    match = re.search(r'Маскировка найдена для (\d{1,3}) из (\d{1,3}) серверов \(перебрано (\d{1,5}) вариантов(?:,|\))', text)
    if not match or 'Сеть изменилась' in text:
        return False
    worked, total, candidates = map(int, match.groups())
    return worked == total == expected and candidates >= 10


if __name__ == '__main__':
    raise SystemExit(0 if verified(sys.stdin.read(4096)) else 1)
