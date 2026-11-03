#!/usr/bin/env python3
"""Bounded numeric Android emulator memory diagnostics; never copy arbitrary log text."""
import json
import re
import sys

PHASES = {'before-rotation', 'after-rotation-failure'}

def number(pattern, text):
    match = re.search(pattern, text, re.MULTILINE)
    if not match:
        return None
    value = int(match.group(1))
    return value if 0 <= value <= 10**12 else None

def summarize(text, phase):
    mem, separator, system = text.partition('--- SYSTEM_SERVER ---')
    # Only accept the expected process's own meminfo dump; never infer from another process.
    if not separator or not re.search(r'^\*\* MEMINFO in pid \d+ \[system\] \*\*', system, re.MULTILINE):
        system = ''
    result = {'phase': phase if phase in PHASES else 'unknown'}
    for label, key in [('MemTotal','mem_total_kb'),('MemFree','mem_free_kb'),('MemAvailable','mem_available_kb'),('SwapFree','swap_free_kb')]:
        result[key] = number(r'^'+label+r':\s*(\d+)\s+kB\s*$', mem)
    result['system_java_heap_kb'] = number(r'^\s*Java Heap:\s*(\d+)', system)
    result['system_total_pss_kb'] = number(r'^\s*TOTAL PSS:\s*(\d+)', system)
    return result

if __name__ == '__main__':
    print(json.dumps(summarize(sys.stdin.read(65536), sys.argv[1] if len(sys.argv)>1 else ''), separators=(',',':')))
