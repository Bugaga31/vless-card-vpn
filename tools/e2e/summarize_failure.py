#!/usr/bin/env python3
"""Publish only allowlisted counters/booleans, never URLs, addresses, payloads or credentials."""
import argparse, json, re, sys
p=argparse.ArgumentParser();p.add_argument('--case',required=True,choices=['setup','single','byedpi','multi','tpws','ownmask','xraydpi','services','auto','auto-eight','auto-chain','proxy','warp']);p.add_argument('--probe',action='store_true');a=p.parse_args()
text=sys.stdin.read(2_000_000)
if a.probe:
    try: data=json.loads(text)
    except (ValueError,TypeError): data={}
    result={'vpn':data.get('vpn') is True,'results':[]}
    for r in data.get('results',[])[:4]:
        err=str(r.get('error','')).split(':',1)[0]
        result['results'].append({k:r.get(k) if type(r.get(k)) is int else None for k in ('code','bytes','ms')})
        result['results'][-1]['error_type']=err if re.fullmatch(r'[A-Za-z]{1,60}(Exception|Error)',err) else ('present' if err else 'none')
else:
    pools=re.findall(r'pool size=(\d{1,2}) ',text)
    checks=re.findall(r'check ok=(true|false) ms=(-?\d{1,8}) big=(true|false|null) yt=(true|false|null) tg=(true|false|null)',text)
    # Terminal Auto messages are classified, never printed: they may contain route names.
    actions=re.findall(r'action\[(Авто-настройка)\]: ([^\r\n]{0,2000})',text)
    def terminal(message):
        if message == 'Остановлено': return 'cancelled'
        if message.startswith('Сеть изменилась:'): return 'network_changed'
        if message.startswith('Сравнение прервано:'): return 'comparison_interrupted'
        if message.startswith('Авто: рабочих серверов нет'): return 'no_verified_server'
        if message.startswith(('Первый HTTPS-маршрут найден', 'Запасной маршрут найден', 'VPN подключается;', 'Авто: выбрано')): return 'connection_requested'
        return 'other'
    result={'auto_terminal_states':[terminal(message) for _,message in actions[-3:]],
            'last_pool_sizes' :pools[-4:],'last_checks':checks[-2:],
            'connect_failed_count':text.count('connect failed:'),
            'fatal_exception_count':text.count('FATAL EXCEPTION'),
            'background_failure_count':text.count('background job failed'),
            'auto_chain_failure_count':text.count('auto-chain-e2e FAIL'),
            'auto_chain_applied_count':text.count('auto-chain-e2e applied='),
            'reload_states':re.findall(r'fixture state mode=(AUTO|SERVERS|BYEDPI|HYBRID) servers=(\d{1,4}) selected=(\d{1,4})',text)[-3:],
            'vpn_permission_required':re.findall(r'fixture connect permission=(true|false)',text)[-3:],
            'exception_types':sorted(set(re.findall(r'\b([A-Za-z]{1,50}(?:Exception|Error))(?=[:\s])',text)) & {'IOException','IllegalStateException','IllegalArgumentException','SSLHandshakeException','SSLPeerUnverifiedException','SocketTimeoutException','ConnectException','UnknownHostException','CancellationException','TimeoutCancellationException','SecurityException','NullPointerException','OutOfMemoryError','RuntimeException'})}
print('::notice title=E2E bounded '+a.case+'::'+json.dumps(result,separators=(',',':')))
