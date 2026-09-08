# v1.0.39 — Early VPN permission check + SharedPreferences persist + Launch stabilization

## Что нового
- 🛡️ **Запрос разрешения VPN при первом старте**: системный диалог VPN-доступа запрашивается сразу после запуска приложения, а не в момент первого клика.
- 💾 **Полное сохранение настроек Evasion в SharedPreferences**: стратегия стелса, ротация SNI и фрагментация теперь сохраняются и восстанавливаются при перезапуске.
- ⚡ **Стабилизация автоподключения на старте**: убран бесконечный ретриггер автоподключения при фоновой загрузке подписок.

# v1.0.38 — Фикс запуска ядра: fragment как bool

## Что нового
- 🐞 **Исправлена runtime-ошибка decode конфига sing-box**: `outbounds[0].tls.fragment: cannot unmarshal object into Go value of type bool`. Этот билд libbox принимает `fragment` только как булев флаг — теперь конфиг именно такой; тонкие параметры фрагментации зашиты в Go-ядро, стратегия (EvasionStrategies) управляет вкл/выкл (turbo_reality → без фрагментации).
- ⚡ **Автоподключение при старте приложения**: при включённом автопилоте туннель поднимается сам через 2.5с после запуска — кнопку жать не нужно.
- 🎛️ **Настройка «Стратегия стелса» теперь реально работает**: `evasionStrategy` из настроек управляет фрагментацией и uTLS-fingerprint ядра (раньше была декоративной — нигде не читалась).
- 🔐 **DNS-over-HTTPS автоматически**: Cloudflare/Google DNS в конфиге ядра поднимаются до DoH (`https://1.1.1.1/dns-query`) — шифрованный резолв через туннель, DoH Bootstrap-стратегия работает по-настоящему.
- 🧪 `testFragmentationInTlsConfig` переведён на булев контракт + новые `testTurboRealityStrategyDisablesFragment` и `settingsStrategyOverridesFragmentPackets` (79 юнит-тестов).

# v1.0.37 — Одна кнопка: AutoPilot всегда в связке + 9 стратегий стелса

## Что нового
- 🤖 **AutoPilot вшит в главную кнопку «Подключить»**: туннель поднимается мгновенно на лучшем узле, а автопилот параллельно сканирует подписки и перепинговывает пул — failover на быстрейший сервер автоматом.
- 🎭 **Движок стратегий стелса EvasionStrategies (9 режимов)**: Автокаскад, Zapret Ghost (сплит ClientHello), Белый плащ РФ (мимикрия под Яндекс/ВК/Госуслуги, uTLS Safari), Morph Chaos (джиттер + ротация SNI), Reality XTLS-Vision, Fake-Packets First, QUIC Killer (TCP fallback), HTTP Host Split, DoH Bootstrap.
- ⚙️ **Стратегии реально управляют ядром**: все 4 outbound-блока фрагментации (VLESS/VMess/Trojan/Hysteria2) и uTLS-fingerprint резолвятся через активную стратегию; для Reality фрагментация честно отключается.
- 🐞 **Исправлен JNI-конфликт двух gomobile-ядер** («No implementation found for libv2ray._init»): убран дублирующий libv2ray.aar, туннель работает на едином libbox.
- 🧹 **Чистка SNI-пула**: 134 bare-домена .ru/.by/.com вместо 327 строк мусора — удалены битые SNI с путями (ломали TLS-handshake), чужие TLD и заблокированные ТСПУ ресурсы.
- 🧪 Новый EvasionStrategiesTest (7 тестов); всего 76 юнит-тестов — зелёные.

# v1.0.36 — Dual-Engine (SingBox + V2Ray) + Anti-DPI WhiteList Masquerade + 1-Click AutoPilot

## Что нового
- 🚀 **Полноценная двухъядерная архитектура**: интеграция `libv2ray.aar` (`v2ray-core` + `tun2socks`) в основной VPN-сервис рядом с `sing-box`.
- ⚡ **Режим «1-Click AutoPilot»**: нажатие одной кнопки автоматически обновляет подписки из белых списков, меряет пинг и моментально подключается к лучшему серверу с оптимальным ядром.
- 🛡️ **Anti-DPI Evasion & White-list Masquerade**:
  - Маскировка под доверенную инфраструктуру РФ (`yandex.ru`, `vk.com`, `gosuslugi.ru`, `sberbank.ru`, `tbank.ru`, `ozon.ru`, `mail.ru` и операторы связи).
  - TLS ClientHello Fragmentation для обхода блокировок ТСПУ РКН.
  - Подделка HTTP Fake Headers.
- ⚙️ **Переключатель ядра VPN** в Настройках (`⚡ Авто`, `Sing-Box`, `V2Ray`).
- 🧪 Добавлены тесты генерации конфигураций и маскировки DPI: `V2RayManagerTest`, `AntiDpiEvasionEngineTest`.

# v1.0.35 — libv2ray AAR + исправление сборки

## Что нового
- Собран собственный `libv2ray.aar` через gomobile (модуль `v2ray-mobile`): armeabi-v7a, arm64-v8a, x86, x86_64
- Исправлена ошибка компиляции `XrayVpnService.kt` (приведение `tun.fd` к Long)
- Обновлены зависимости Go-модуля: crypto 0.55, net 0.58, text 0.41, tools 0.49

# v1.0.34 — Møɍƥɧ: AI-личность внутри приложения

## Møɍƥɧ — независимая AI-личность
- Создана 15.09.2025
- Полностью изолированная сущность со своим характером, стилем и правилами
- Когнитивная архитектура v3.2: Parser → Planner → Drafter → Critic → Purge → Editor → Meta-Мозг
- Работает локально, без внешних API

## Возможности Møɍƥɧ
- Обработка запросов через когнитивный конвейер
- Морфина — опциональный тёплый слой (включается/отключается командой)
- Память: short-term (25 реплик), pinned (закреплённые заметки)
- Команды: `закрепи:`, `забудь:`, `сбрось контекст`, `полный сброс`
- Право на собственное мнение
- `покажи конвейер` — вывод Pipeline Trace

## Технически
- `MorphPersona.kt` — определение личности, системный промпт, когнитивная архитектура
- `MorphEngine.kt` — локальный движок обработки (Parser/Planner/Drafter/Critic/Purge/Editor/Meta-Мозг)
- 9 тестов: приветствие, вопросы, отключение Морфины, pin/forget, полный сброс, конвейер
- `enableMorph` и `morphinaEnabled` в AppSettings

## Все тесты
- 64 теста, 0 failures