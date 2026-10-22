# 1.2.40 — поглощение tor-rotator-vless

Второй проект Jack'а — [tor-rotator-vless](https://github.com/Bugaga31/tor-rotator-vless) —
поглощён: «мозг» ротатора переехал в VLESS Card VPN как опциональный режим
«Tor · ротатор» (Tor Card).

## Что добавлено

- `tor/` — порт логики ротации на Kotlin: адаптивные пороги с абсолютным floor,
  hysteresis-чёрный список с TTL, EMA-история скорости по странам выхода,
  JSON-персистентность (наследие IMPROVEMENTS.md ротатора).
- `tor/TorControl.kt` — клиент Control-протокола Tor (AUTHENTICATE cookie,
  SIGNAL NEWNYM, SETCONF ExitNodes, GETINFO status/circuit-established).
- `tor/TorCard.kt` — Tor как обычная карточка в колоде: синтетический SOCKS5-сервер
  на локальный Termux-Tor + генератор torrc (SocksPort/ControlPort/CookieAuthentication).
- `tor/TorProbes.kt` — измерение реальной страны выхода и латентности через
  Cloudflare-trace по SOCKS5, с транспорт-осведомлённым бюджетом (Snowflake ×2).
- `vpn/TunnelService.kt` — фоновый `torLoop` (10 с тик), включается настройкой
  `torRotation` (по умолчанию выкл.), работает пока основное VPN-подключение живо.
- Тесты: TorControlTest (фейковый ControlPort-сервер), TorRotationTest (детерминированная
  политика), TorRotatorTest (тик, ручной чёрный список, ошибка подключения).

## Как включить

1. Termux: `pkg install tor`; положить torrc из TorCard.torrc() и запустить tor.
2. В приложении включить «Tor · ротатор» — карточка Tor появится в колоде,
   ротация выхода идёт в фоне поверх основного подключения.

Подробности: [tor-rotator-absorption.md](tor-rotator-absorption.md).
