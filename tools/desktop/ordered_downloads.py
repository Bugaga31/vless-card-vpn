from pathlib import Path
import json
base='https://github.com/Bugaga31/vless-card-vpn/releases/download/beta-v1.2.85-experimental/'
body=json.loads(Path('release-body.json').read_text())['body']
if body.startswith('## Скачать'):
 body=body[body.index('# 1.2.85 Experimental'):]
links='## Скачать — в удобном порядке\n1. [Android ARM64 APK]({base}VLESS-Card-Beta-1.2.85-experimental-arm64.apk)\n2. [Android универсальный APK]({base}VLESS-Card-Beta-1.2.85-experimental.apk)\n3. [Windows 10/11 x64 — EXE, Desktop preview 0.3.0]({base}VLESS-Card-Desktop-0.3.0-windows-x64.exe)\n4. [Linux Mint 21+ / Ubuntu 22.04+ x64 — DEB, Desktop preview 0.3.0]({base}VLESS-Card-Desktop-0.3.0-linux-x64.deb)\n\n[Linux portable tar.gz]({base}VLESS-Card-Desktop-0.3.0-linux-x64.tar.gz) · [Desktop инструкция]({base}Desktop-README-0.3.0.md) · [Хеши desktop]({base}Desktop-SHA256SUMS.txt)\n\n**Desktop — локальный SOCKS5, не системный VPN.** TCP/raw TLS/Reality; без TUN/WARP/XHTTP и без Android-маскировок. Укажите выданный прокси в приложении. EXE пока без подписи издателя. Linux проверен на Ubuntu 22.04 CI, а не на физическом Mint. На Windows/Linux выполнены core-регрессии, проверка конфигурации встроенного Xray с ожиданием exit code, открытие/закрытие GUI и отдельный просмотр двух снимков. Живой операторский DPI не проверен.\n\nDesktop source: https://github.com/Bugaga31/vless-card-vpn/tree/79b250d84f0cdda84be441002f99de85655ca9da/desktop\n\n'.format(base=base)
Path('ordered-notes.md').write_text(links+body)
