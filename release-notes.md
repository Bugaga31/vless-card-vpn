## v1.0.32 — Anti-DPI: TLS Fragmentation + SNI Rotation + Per-App Split Tunneling

### 🛡️ Анти-ТСПУ (Anti-DPI)
- **TLS-фрагментация пакетов**: разбиение ClientHello на фрагменты для обхода DPI (настройки: `packets`, `interval`, `length`)
- **SNI-ротация**: пул из 250+ российских доменов (.ru/.by/.com) — случайный SNI при каждом подключении, чтобы избежать fingerprinting
- **Per-App Split Tunneling**: обход VPN для выбранных приложений (банки, госуслуги, почта) — трафик идёт напрямую

### 🇷🇺 Российский SNI-пул
- Правительственные домены: kremlin.ru, nalog.gov.ru, gosuslugi.ru, mos.ru и десятки других
- Операторские: mts.ru, beeline.ru, megafon.ru, tele2.ru
- Медиа: 1tv.ru, rbc.ru, ria.ru, tass.ru, kommersant.ru
- Банки: sberbank.ru, tbank.ru, vtb.ru, alfabank.ru
- CDN-фолбэк: microsoft.com, cloudflare.com, apple.com, github.com

### 📦 Технические детали
- 26 unit-тестов в CoreUnitTests (6 новых: фрагментация, SNI-ротация, per-app split)
- Всего 50 тестов (0 failures)
- APK: 215 MB (debug)