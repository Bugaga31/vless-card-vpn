"""Fail-closed validation of actual Xray IDs against a synthetic multi-route fixture.

Quality ranking may legitimately retain 1–3 of the healthy routes. Count alone cannot
prove that the dead route was dropped, so validate every running identity as well.
"""
import json
import re
import sys


def java_hex(value):
    result = 0
    raw = value.encode('utf-16-be')
    for index in range(0, len(raw), 2):
        result = (31 * result + int.from_bytes(raw[index:index + 2], 'big')) & 0xffffffff
    return format(result, 'x')


def route_id(server):
    keys = ('protocol', 'address', 'port', 'secret', 'network', 'security', 'sni', 'pbk', 'sid', 'path', 'host', 'serviceName', 'flow')
    values = {key: str(server.get(key, 'tcp' if key == 'network' else '')) for key in keys}
    values['address'] = values['address'].lower()
    return java_hex('|'.join(values[key] for key in keys)) + java_hex(str(server['address']) + server.get('secret', '') + str(server['port']))


def validate(fixture, log):
    servers = fixture['servers']
    dead = {route_id(server) for server in servers if server['name'] == 'e2e-dead'}
    healthy = {route_id(server) for server in servers if server['name'] != 'e2e-dead'}
    if len(dead) != 1 or len(healthy) != 3:
        return False
    matches = re.findall(r'pool size=(\d+) ids=\[([^\]]*)\]', log)
    if not matches:
        return False
    size, content = matches[-1]
    ids = [value.strip() for value in content.split(',') if value.strip()]
    return 1 <= int(size) <= 3 and len(ids) == int(size) and len(set(ids)) == len(ids) and set(ids) <= healthy and not set(ids) & dead


if __name__ == '__main__':
    try:
        passed = validate(json.load(open(sys.argv[1])), sys.stdin.read())
    except (OSError, ValueError, KeyError, IndexError):
        passed = False
    print('PASS: actual running pool contains only known healthy fixture routes' if passed else 'FAIL: actual running pool missing, malformed, unknown, or includes dead route')
    sys.exit(0 if passed else 1)
