# Changelog

## [1.0.40] - 2026-09-07

### Stability & Continuous Operation
- **Anti-Self-Exit & Background Resilience**: Устранено любое случайное или принудительное самопроизвольное закрытие сервиса VPN:
  - `VlessVpnService` переведён на `START_STICKY` с флагом манифеста `android:stopWithTask="false"`, гарантируя автоматический перезапуск и удержание активного процесса операционной системой.
  - Безопасная инициализация `Intent` в `MainActivity` с предотвращением `NullPointerException` при вызове из фона или виджета.
  - Закреплена постоянная фоновая сессия с защитой от выгрузки при очистке списка недавних приложений.

## [1.0.29] - 2026-09-07

### Fixed & Improved
- **Fix Crash on VPN Connect (Android 14 / API 34)**: Устранено падение и выход из приложения сразу после выдачи системного разрешения VPN. Добавлен явный тип `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` в вызовы `startForeground()`, включен `fixAndroidStack = true` в libbox, добавлено исключение пакета приложения `addDisallowedApplication` от зацикливания сокетов.
- **1-Click Auto Connect**: Автоматический поиск, фильтрация и запуск рабочего VLESS Reality узла в одно нажатие прямо с главного экрана.
- **Redesign UI & Assets**: Новый неоновый логотип и полный набор adaptive/round launcher иконок (`mipmap-mdpi`...`xxxhdpi`), Breathing Pulse Glow для активного туннеля и карточки телеметрии (Upload/Download, таймер сессии).
- **VK & RU Direct Routing**: Прямая маршрутизация без задержки для сервисов VK, Госуслуг, банков и маркетплейсов с маскировкой под мобильные клиенты операторов РФ (МТС, Билайн, Мегафон, Т2).
- **DPI Anti-Throttling**: Блокировка QUIC (UDP 443/80) для ускорения YouTube через Reality TCP-туннель.

## [1.0.25] - 2026-09-07

### Visual & Experience Enhancements
- **HUD Breathing Pulse**: Добавлен неоновый изумрудный пульсирующий ореол вокруг карточки подключения в активном статусе.
- **Live Traffic Telemetry**: Внедрены виджеты мгновенной скорости (Download/Upload) и суммарного объема переданных данных с адаптивным форматированием.
- **Protocol & Node Badges**: На карточке узла и в списке добавлены плашки протоколов (`VLESS`, `VMESS`, `TROJAN`, `SS`) и индикатор пинга.
- **Brand Identity & Vector Iconography**: Интегрирован визуальный стиль кибер-щита в шапке и splash-экране, обновлены иконки для всех разрешений Android (`res/mipmap-*`).

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
