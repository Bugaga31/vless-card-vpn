## v1.0.33 — Xray JSON Import + Hysteria2 + JA4 Fingerprints

### 🔄 Xray-совместимость
- **XrayConfigImporter**: прямой импорт Xray JSON-конфигов (v2ray/xray формат)
- Поддержка: VLESS, VMess, Trojan, Shadowsocks, Hysteria2 outbounds
- Парсинг `streamSettings` (wsSettings, grpcSettings, realitySettings, tlsSettings)
- Извлечение правил маршрутизации из `routing.rules`

### ⚡ Hysteria2 Outbound
- Полноценная генерация Hysteria2 конфига для sing-box
- QUIC-оптимизации: увеличенные окна приёма (8-33 MB), keep-alive 10s
- Обфускация salamander (по умолчанию)
- Авто-парсинг параметров из закодированного uuid

### 🔐 JA4 Fingerprints
- `enableJa4` — флаг перехода на JA4 (новый стандарт TLS-фингерпринтов)
- JA4 заменяет JA3 в 2026 — DPI уже использует JA4 для детекции

### 📦 Технические детали
- 31 unit-тест в CoreUnitTests (5 новых: Xray импорт ×3, Hysteria2 ×2)
- Всего 55 тестов (0 failures)
- Новые файлы: `XrayConfigImporter.kt` (309 строк), `SingBoxManager.kt` (+65 строк Hysteria2)