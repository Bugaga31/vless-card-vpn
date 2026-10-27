#!/usr/bin/env python3
"""Server-backed Tor routing fixture, replacing retired serverless behavior.
Real Xray and local TLS body transfer; simulated Tor, not bootstrap/operator proof.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--xray', type=Path, required=True)
    parser.add_argument('--config', type=Path, required=True)
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location('chain_smoke', Path(__file__).parents[1] / 'auto-chain-lab/smoke.py')
    smoke = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(smoke)
    with tempfile.TemporaryDirectory(prefix='tor-uplink-smoke-') as directory:
        tmp = Path(directory)
        cert, key = tmp / 'ca.pem', tmp / 'key.pem'
        subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
                        '-keyout', str(key), '-out', str(cert), '-subj', '/CN=' + smoke.HOST,
                        '-addext', 'subjectAltName=DNS:' + smoke.HOST], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        smoke.require(args.config.name == 'LIVE_SERVER_TOR.json', 'server-backed config required')
        print(json.dumps(smoke.run_case(args.xray.resolve(), args.config.parent, 'LIVE_SERVER_TOR', cert, key, tmp, http_uplink=True)))


if __name__ == '__main__':
    main()
