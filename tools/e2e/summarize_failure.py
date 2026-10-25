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
    # Producer and parser both allowlist values. No exception message/route is copied.
    safe_types={'IllegalStateException','IllegalArgumentException','IOException','FileNotFoundException','BindException','SocketException','SocketTimeoutException','UnknownHostException','CancellationException','TimeoutCancellationException','SecurityException','NullPointerException','OutOfMemoryError','RuntimeException','Exception','Other'}
    safe_phases={'init','selection','dpi','config','core','tun','notification','action','chain_check'}
    safe_codes={'bind','address_in_use','file_limit','permission','dpi_missing','chain_unconfirmed','chain_not_applied','core_not_running','unclassified'}
    safe_files={'TunnelService.kt','Actions.kt','XrayCore.kt','DpiSet.kt','DpiProxy.kt','Tester.kt','RuntimeChainE2E.kt','AutoChainRunner.kt','XrayConfigBuilder.kt','Store.kt'}
    failures=[]
    for scope,phase,kind,code,origin,causes in re.findall(r'failure scope=(\S+) phase=(\S+) type=(\S+) code=(\S+) origin=(\S+) causes=(\S+)',text)[-3:]:
        file,_,line=origin.partition(':')
        failures.append({'scope':scope if scope in {'connect','action','chain'} else 'unknown',
            'phase':phase if phase in safe_phases else 'unknown','type':kind if kind in safe_types else 'Other',
            'code':code if code in safe_codes else 'unclassified',
            'origin':origin if file in safe_files and line.isdecimal() and 1<=int(line)<=100000 else 'unknown',
            'causes':[c if c in safe_types else 'Other' for c in causes.split(',')[:5]]})
    service_events=re.findall(r'service command=(start|stop|pause|sticky|chain) state=(IDLE|CONNECTING|CONNECTED|ERROR)\b',text)[-6:]
    teardown_events=re.findall(r'teardown state=(IDLE|CONNECTING|CONNECTED|ERROR) keepTun=(true|false) reason=(restart|stop|error)\b',text)[-6:]
    result={'failures':failures,'service_events':service_events,'teardown_events':teardown_events,
            'optimize_skips':re.findall(r'optimize skipped=(power|foreground_busy|disabled)\b',text)[-3:],
            'auto_ready_blocked':re.findall(r'auto ready_blocked=(network_changed|cancelled)\b',text)[-3:],
            'start_callers':re.findall(r'start request caller=((?:MainActivity|Actions|Assistant|BootReceiver|VpnWidget|ToggleTileService)\.kt:[0-9]{1,6}|unknown)(?=\s|$)',text)[-4:],
            'maintenance_events':re.findall(r'maintenance (queued|applying=soft)\b',text)[-4:],
            'auto_terminal_states':[terminal(message) for _,message in actions[-3:]],
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
