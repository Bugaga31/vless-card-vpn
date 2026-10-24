"""WireGuard userspace loopback is not the host loopback. Route a documentation IP to one local TLS origin."""
import copy
import json
import sys
import urllib.parse

TARGET = '192.0.2.2'
PORT = 18443
TAG = 'matrix-wg-fixture'
ORIGIN_TAG = 'matrix-wg-local-origin'
BLOCK_TAG = 'matrix-wg-block'


def prepare(config):
    result = copy.deepcopy(config)
    inputs = [row for row in result['inbounds'] if row.get('protocol') == 'wireguard']
    if len(inputs) != 1 or inputs[0].get('port') != 8450 or inputs[0].get('listen') != '127.0.0.1':
        raise ValueError('Unexpected WireGuard fixture')
    inputs[0]['tag'] = TAG
    if any(row.get('tag') in (ORIGIN_TAG, BLOCK_TAG) for row in result['outbounds']):
        raise ValueError('Duplicate fixture routing')
    result['outbounds'] += [
        {'tag': ORIGIN_TAG, 'protocol': 'freedom', 'settings': {
            'redirect': f'127.0.0.1:{PORT}',
            'finalRules': [{'action': 'allow', 'network': 'tcp', 'ip': ['127.0.0.1/32'], 'port': str(PORT)}]}},
        {'tag': BLOCK_TAG, 'protocol': 'blackhole'}]
    routing = result.setdefault('routing', {})
    routing['rules'] = [
        {'type': 'field', 'inboundTag': [TAG], 'network': 'tcp', 'ip': [TARGET+'/32'],
         'port': str(PORT), 'outboundTag': ORIGIN_TAG},
        {'type': 'field', 'inboundTag': [TAG], 'outboundTag': BLOCK_TAG}] + routing.get('rules', [])
    return result


def wireguard_url(url):
    value = urllib.parse.urlsplit(url)
    if value.scheme != 'https' or value.hostname != '127.0.0.1' or value.port != PORT or value.username or value.password:
        raise ValueError('Expected local verified TLS origin')
    return urllib.parse.urlunsplit(value._replace(netloc=f'{TARGET}:{PORT}'))


if __name__ == '__main__':
    if len(sys.argv) != 2: raise SystemExit('Usage: matrix_wireguard.py FIXTURE_JSON')
    path = sys.argv[1]
    with open(path) as source: config = prepare(json.load(source))
    with open(path, 'w') as target: json.dump(config, target)
