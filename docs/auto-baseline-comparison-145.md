# Auto baseline comparison

Sources compared: `v1.0.100` (dfae92b), `v1.1.0` (03658c8), and `v1.2.33` (bdeac329). No phone/carrier benchmark is claimed.

Both old tags use the same broad `quickTest`: TCP over the supplied list with a 1500ms timeout, nearest 30 then full route/download tests. 1.1.0 additionally learns from route probes; this comparison does not delete or reset that learning. A useful reachable route beyond the tiny first group could be found, whereas the newer bounded shortlist could miss it repeatedly.

1.2.33 also retained two wrong behaviors:
- `AutoSelection.compare` could return a service/HTTPS candidate after the throughput coroutine timed out without a sample. The existing timeout regression explicitly expected this fallback. Its assertion is corrected to require no automatic winner, preserving cleanup and manual usability checks; it is not disabled.
- The final actual-chain failure threw `ServiceProofException` and stopped the service, but left the per-route cached positive proof intact. A repeat button press could reuse it. Now only old proof for the same attempted configurations is revoked, without training a per-server failure from an unattributable chain/resource outcome.

The old full-catalog scan is not copied wholesale: a rotating window of 128 cheap candidates, at most 24 HTTPS finalists, existing traffic/power/cancellation/deadline bounds and strict service admission remain. This is coverage of deterministic selection cases, not measured lower connection time or universal access.

CI additionally stopped publication on the existing whole-probe socket-cancellation regression. Inspection found a last-hook-only cancellation registry despite concurrent HTTP calls. All registered hooks now drain atomically; late registrations close immediately and one throwing hook cannot prevent the others. New deterministic tests cover this independently of TCP timing. The loopback closure fixture recognizes both EOF/FIN and connection-reset/RST (not timeout), avoiding a false leak result when cancellation races the peer's SOCKS reply.


## Уточнение: независимая HTTPS-передача вместо блокировки на одном ролике

После уточнения владельца строгий сервисный proof отделён от допуска к подключению. Два независимых HTTPS-хоста с полными 64-КиБ ответами допускают маршрут с UNKNOWN Telegram/YouTube, но не с подтверждённым FAIL. Полностью сервисно-проверенный пул предпочтителен; обычный HTTPS не подменяет сервисные флаги. Старые строгие regression-тесты остаются для API servicePool/servicesWork; отдельные тесты покрывают новый connectionPool/connectionWorks и границы проверки. Это намеренное изменение политики, не доказательство работы сервисов у оператора.
