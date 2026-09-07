## v1.0.31 — WS/gRPC/H2 Transport + DNS AdBlock + Auto-Update

### 🚀 Новые возможности
- **WebSocket, gRPC, HTTP/2 транспорт**: поддержка `ws`, `grpc`, `h2` транспорта для VLESS и VMess с полной конфигурацией (path, host, serviceName)
- **DNS-блокировка рекламы**: встроенный блоклист +250 рекламных/трекерных доменов (Google Ads, Meta, Yandex Ads, VK, Xiaomi, Samsung, Huawei, Oppo/Vivo и др.)
- **Авто-проверка обновлений**: фоновая проверка GitHub Releases на новые версии APK с уведомлением

### 🛠 Улучшения
- Парсер VMess теперь извлекает `net` (ws/grpc/h2), `path` и `host` из JSON-конфигов
- `PingTester` различает Reality-серверы (TCP-достижимость) и TLS-серверы (полный handshake)
- Добавлены новые источники бесплатных конфигов в `autoFetchSources`

### 📦 Технические детали
- 6 новых тестов для транспортных генераций и парсинга
- Всего 43 unit-теста (0 failures)
- APK: 215 MB (debug)