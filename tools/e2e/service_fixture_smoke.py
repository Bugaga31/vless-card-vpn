#!/usr/bin/env python3
"""Fail-closed host preflight: real Reality/VLESS chain -> exact local MTProto/video origin.

Does not certify availability of the public services. No release trust anchors or keys are changed.
"""
import importlib.util
import json
from pathlib import Path
import re
import secrets
import socket
import struct
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('fixture', Path(__file__).with_name('service_fixture.py'))
fixture = importlib.util.module_from_spec(spec); spec.loader.exec_module(fixture)

def recv(sock, size):
    result = b''
    while len(result) < size:
        block = sock.recv(size-len(result))
        if not block: raise RuntimeError('early EOF')
        result += block
    return result

def main(xray, origin):
    processes = []
    handles = []
    with tempfile.TemporaryDirectory(prefix='e2e-origin-') as folder:
        work = Path(folder)
        try:
            keys = subprocess.check_output([xray,'x25519'],text=True)
            private = re.search(r'PrivateKey: (.*)',keys)[1]
            public = re.search(r'Password \(PublicKey\): (.*)',keys)[1]
            server = json.loads((ROOT/'tools/e2e/server.json').read_text().replace('@PRIV@',private)
                .replace('@U@','a00975c5-597e-4c22-a7a7-a84ce5e0691e').replace('@W@',str(work)))
            # Only Reality inbound is needed; no unrelated TLS fixture material is created.
            server['inbounds'] = server['inbounds'][:1]
            server['log'] = {'loglevel':'warning'}
            config = work/'server.json'; config.write_text(json.dumps(server)); fixture.patch(config)
            client = {'log':{'loglevel':'warning'},'inbounds':[{'listen':'127.0.0.1','port':18443,
                'protocol':'socks','settings':{'auth':'noauth'}}], 'outbounds':[{'protocol':'vless',
                'settings':{'vnext':[{'address':'127.0.0.1','port':8443,'users':[{
                    'id':'a00975c5-597e-4c22-a7a7-a84ce5e0691e','encryption':'none','flow':'xtls-rprx-vision'}]}]},
                'streamSettings':{'network':'tcp','security':'reality','realitySettings':{
                    'fingerprint':'chrome','serverName':'www.microsoft.com','publicKey':public,'shortId':'6ba85179e30d4fc2'}}}]}
            (work/'client.json').write_text(json.dumps(client))
            commands = [[sys.executable,str(ROOT/'tools/e2e/service_fixture.py'),'serve',str(origin)],
                        [xray,'run','-c',str(config)], [xray,'run','-c',str(work/'client.json')]]
            for index, cmd in enumerate(commands):
                log = open(work/f'process-{index}.log','w'); handles.append(log)
                processes.append(subprocess.Popen(cmd,stdout=log,stderr=subprocess.STDOUT))
            time.sleep(2)
            if any(p.poll() is not None for p in processes): raise RuntimeError('fixture process exited during startup')
            curl = ['curl','--noproxy','','--fail','--silent','--show-error','--max-time','20',
                '--socks5-hostname','127.0.0.1:18443','--cacert',str(origin/'cert.pem')]
            player = subprocess.check_output(curl+['https://www.youtube.com/watch?v=jNQXAC9IVRw'])
            assert b'ytInitialPlayerResponse' in player, 'player response missing'
            video = subprocess.check_output(curl+['-H','Range: bytes=0-131071',
                'https://fixture.googlevideo.com/videoplayback?e2e=1'])
            assert len(video) == 131072, 'video transfer incomplete'
            for url in ('https://speed.cloudflare.com/__down?bytes=65536', 'https://cachefly.cachefly.net/1mb.test', 'https://proof.ovh.net/files/1Mb.dat'):
                body = subprocess.check_output(curl+['-H','Range: bytes=0-65535',url])
                assert len(body) == 65536, 'HTTPS control incomplete'
            with socket.create_connection(('127.0.0.1',18443),timeout=20) as sock:
                sock.settimeout(20); sock.sendall(b'\5\1\0'); assert recv(sock,2) == b'\5\0'
                sock.sendall(b'\5\1\0\1'+socket.inet_aton('149.154.167.51')+struct.pack('!H',443))
                header=recv(sock,4); assert header[:2] == b'\5\0'
                if header[3] == 1: recv(sock,6)
                elif header[3] == 4: recv(sock,18)
                else: raise RuntimeError('unexpected SOCKS reply')
                nonce=secrets.token_bytes(16)
                sock.sendall(b'\xef\x0a'+struct.pack('<QQII',0,int(time.time())<<32,20,0xbe7e8ef1)+nonce)
                words=recv(sock,1)[0]; assert words == 21
                assert recv(sock,words*4) == fixture.response(nonce)[1:], 'MTProto nonce/frame mismatch'
            print('PASS: TLS-verified player, 131072 video bytes, MTProto nonce through real Reality/VLESS; local fixture only')
        finally:
            for p in processes: p.terminate()
            for p in processes:
                try: p.wait(timeout=5)
                except subprocess.TimeoutExpired: p.kill(); p.wait()
            for f in handles: f.close()

if __name__ == '__main__': main(sys.argv[1],Path(sys.argv[2]))
