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