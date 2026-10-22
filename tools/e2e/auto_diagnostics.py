"""Emit fixed vocabulary and counts only. Never echo raw fixture log lines."""
import json
from pathlib import Path
import re
import sys

EVENTS = ('admitted', 'replaced', 'started', 'discarded', 'sampled', 'finished', 'cancelled', 'failed')
REASONS = ('no-socks', 'core-changed', 'context-changed')


def summarize(text):
    result = dict.fromkeys(EVENTS + REASONS + ('checks_passed', 'checks_failed', 'admitted_two_hosts', 'batch_count'), 0)
    for line in text.splitlines():
        m = re.search(r'\bauto-verify event=([a-z-]+)(?: hosts=([0-3]))?(?: reason=([a-z-]+))?$', line)
        if m and m[1] in EVENTS and (m[3] is None or m[3] in REASONS):
            result[m[1]] += 1
            if m[3]:
                result[m[3]] += 1
            if m[1] == 'admitted' and m[2] in ('2', '3'):
                result['admitted_two_hosts'] += 1
        if re.search(r'\bcheck ok=true(?: |$)', line):
            result['checks_passed'] += 1
        if re.search(r'\bcheck ok=false(?: |$)', line):
            result['checks_failed'] += 1
        if re.search(r'\bauto-fast catalog=[1-8] candidates=[1-8]$', line):
            result['batch_count'] += 1
    return result


if __name__ == '__main__':
    path = Path(sys.argv[1])
    result = summarize(path.read_text(errors='replace') if path.is_file() else '')
    result['case_log_present'] = path.is_file()
    print('::notice title=Auto fixed-vocabulary diagnostics::' + json.dumps(result, sort_keys=True))
