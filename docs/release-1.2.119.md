# 1.2.119 — кандидат обычного релиза (R235)

Кандидат, не опубликован. Пакет com.vlesscardvpn.beta2, versionCode 230; обновление сохраняет постоянную подпись Beta.

## ВАТСОН автоматически, без вмешательства в VPN
- Локальный аудит разрешений видимых Android launcher-приложений и самого VLESS Card: запуск, изменения пакетов, не чаще 15 минут без изменений. До 300 пакетов; снимок только в RAM.
- Отдельный переключатель автоаудита по умолчанию включён, выключение очищает автоматический снимок и отменяет расписание. Ручной аудит остаётся доступен.
- Android JobScheduler объединяет фоновую работу с периодом 30 минут; выполнение не гарантировано в Doze/после force-stop.
- Поиск VPN, игры и запрет необязательной работы PhonePower приостанавливают аудит. Проверка между пакетами, отмена и mutex не допускают параллельных сканирований.
- Разрешения и объявления служб — факты манифеста, не факт слежки/использования и не антивирусный вердикт. Нет чтения сообщений, автоматического удаления приложений или изменения их разрешений.
- ВАТСОН уже отслеживает нагрузку и ограничивает только собственную необязательную работу. Не ускоряет зарядку физически, не управляет охлаждением Android и не обещает измеренной экономии батареи.

## Сохранены инварианты
ROKI/эволюция масок R234 и быстрое восстановление R233 сохранены. Политика сервера/SNI/ALPN/REALITY, строгие выбранные сервисы, пользовательские слоты и допуск по умолчанию 100–3000 мс не ослаблены.

## Регрессии и критерии
Устаревшие тесты 1000 мс, пятисекундных пауз, постоянного исключения по истории отказов, искусственной задержки воркеров и старого количества tpws-профилей синхронизированы с действующими R211/R226/R227. Старые 1000 мс сценарии теперь задают предел явно. Привязки Python E2E-регрессий обновлены к текущим AutoChecks, cohort и ProbeConcurrencyPolicy без отключения самих регрессий. Добавлены 16 новых тестов автоаудита и точной границы 3000 мс.
Обычный релиз допустим только после полного JVM debug+release, lint, масочной матрицы, E2E API29/33, установки/обновления и storage API30/34. Новый замер физического телефона, оператора, батареи и реального звонка не выполнялся.

## Follow-up full Android gates
The first full Android API29/33 run exposed premature cleanup in AutoChainRunner startup: CancellableProbe.run closes registered resources on success, stopping the core before measurement. Startup now transfers successful ownership to Session.close, with cancellation/failure cleanup retained and three JVM regressions. End-to-end still requires a measured DPI_SERVER winner and other-UID transfer.
Legacy E2E assertions now follow single active primary with authenticated eight-candidate traffic, current mask-summary wording/counts, and the actual ВАТСОН navigation label. No required service, body proof or admission predicate is removed. Ordinary release remains blocked until the new exact-SHA full CI passes.

A second gate exposed TCP-only chain test inbounds rejecting the selected voice UDP test. For VLESS/VMess/Trojan server chains, inner UDP now routes only through the authenticated server stream; it never routes directly or to the local TCP DPI proxy. Tor chains remain UDP-blocked and excluded when voice is required. Strict voice/STUN and all other selected service predicates are unchanged. New transport-config regression covers server, DPI-server and Tor prohibition; Android verification still pending.
