# Поглощение tor-rotator-vless (1.2.40)

tor-rotator-vless — родственный проект: ротация выхода Tor через VLESS-подобную
схему (локальный SOCKS5 + ControlPort). Здесь его логика переосмыслена и встроена
в приложение; shell-скрипты и crontab заменены корутиновым циклом.

## Соответствие

| tor-rotator-vless | VLESS Card VPN |
|---|---|
| tor_rotator.py (rotation policy) | `tor/TorRotation.kt` (+ IMPROVEMENTS.md: adaptive thresholds, floors, hysteresis blacklist, EMA) |
| ControlPort-скрипты | `tor/TorControl.kt` (cookie auth, NEWNYM, ExitNodes, circuit-established) |
| termux torrc-скрипты | `tor/TorCard.torrc()` / `TorCard.localCard()` |
| ping Cloudflare через SOCKS | `tor/TorProbes.exitInfo()` — реальная страна выхода (trace loc=) |
| systemd-режим | `TunnelService.torLoop()` — тик 10 с, gate по `settings.torRotation` |

## Отличия от оригинала

- Всё детерминированно и тестируемо: политика принимает `now: () -> Long`.
- Чёрный список — hysteresis с TTL-декей, а не вечный бан.
- Измерения кормят EMA-историю; медленные транспорты получают увеличенный
  бюджет (Snowflake ×2) вместо отказа.
- Состояние сериализуется в JSON — история скоростей переживает перезапуск.

## Ограничения

- Tor запускается вне приложения (Termux); с неаутентифицированным ControlPort
  не работает (§1 README ротатора — принято как есть).
