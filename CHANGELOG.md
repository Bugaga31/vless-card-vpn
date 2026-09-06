# Changelog

## [1.0.23] - 2026-09-06

### Security and reliability
- Removed the three hard-coded demo VPN profiles from first launch.
- Stopped treating generic Reality TLS errors as a successful handshake.
- Removed the invalid TCP/send-buffer MTU heuristic.
- Disabled cleartext traffic and Android backups.
- Removed the unnecessary phone-state permission.
- Closed public-feed HTTP responses and improved profile deduplication.

### Experience
- Added the obsidian-and-champagne Private Lounge visual foundation.
- Added lifecycle cleanup for the repository and autopilot engine.

## [1.0.16] - 2026-09-06

### Core & Engine
- **Native sing-box Core**: Полностью подключен нативный модуль `libbox-android` с управлением жизненным циклом через `CommandServer` и `CommandClient`.
- **Real TUN FD**: Прямая передача дескриптора TUN-интерфейса в ядро sing-box с защитой сокетов от зацикливания через `vpnService.protect(fd)`.
- **Authentic Telemetry**: Удален синтетический генератор байтов. Скорости отдачи/загрузки и объем трафика считываются напрямую из `StatusMessage` ядра.
- **Strict End-to-End Status**: Статус `CONNECTED` активируется только после успешного прохождения проверки доступности (HTTP 204), предотвращая ложные индикации соединения.

### Configuration & Routing
- **Reality & SNI Guard**: Запрещена произвольная автоматическая подмена SNI сервера. Значения SNI, flow, fingerprints и Reality public keys строго сохраняются при передаче в сервис.
- **YouTube Acceleration**: Интегрировано правило сброса QUIC (UDP 443) в `SingBoxManager.kt` для обхода троттлинга провайдеров.
- **Explicit RU Direct**: Раздельное туннелирование российских доменов и сервисов (.ru, .su, госуслуги, банки) вынесено в явную настройку.

### Storage & Autopilot
- **Room Database**: Вся база серверов, избранное и настройки переведены на персистентное хранилище Room.
- **AutoPilot Engine**: Автоматическое бесшовное переключение на резервный сервер при 3 подтвержденных сбоях соединения.

### Security & Privacy
- Удалены все отладочные вызовы `println` с конфигами, исключена утечка UUID и Reality ключей в системные логи.
