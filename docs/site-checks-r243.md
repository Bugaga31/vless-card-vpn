# Выбор проверок Авто — R243 (кандидат, не релиз)

Вместо нижней вкладки «Помощник» — «Что проверять»: 38 предустановленных сайтов, поиск и добавление собственного домена. Помощник остаётся доступен из настроек. Сохранённые домены и выбор переживают перезапуск; выбранный домен можно удалить. Существующие Telegram, устойчивость, UDP, Discord, YouTube и Instagram остаются доступны.

Сайты проверяются отдельным ограниченным HTTPS GET через тот же тестовый SOCKS-кандидат и обычную проверку TLS; прямого запасного пути и переходов по редиректам нет. Только ответ 2xx подтверждает HTTP-доступ, но не авторизацию, просмотр видео или работу приложения. Редирект, отказ, ошибка и тайм-аут не являются подтверждением. Каждый выбранный сайт обязателен для допуска; изменение набора меняет ключ доказательств. Много выбранных сайтов могут не успеть в общий срок и помешать найти сервер; UNKNOWN не превращается в PASS. Пользовательская настройка 1/3/5/10 параллельных серверов не меняется.

Проверено локально: десять JUnit-тестов доменов, допусков, реального сохранения настроек и конструктора HTTPS-запросов (Android-зависимости заменены заглушками); 89 вспомогательных Python тестов. В эмуляторном сценарии добавлена проверка поиска, добавления и сохранения после перезапуска. Нужен полный CI: локально нет Android SDK, реального Chrome или телефона. Успешный предыдущий прогон диагностического коммита не доказал устранения ранее замеченного сброса во время multi-трафика, причины Chrome и Android 29 handoff не установлены. Релиз не объявлять готовым, пока эти ограничения и актуальные проверки не рассмотрены.

## Recovery confirmation — R244

In Android 33 auto-eight CI at `23d2b0d`, the other-UID request reset while two consecutive recovery requests were classified as `STABILITY_SPREAD`: the Telegram series had responses but exceeded its RTT spread limit. The fixture explicitly disables the active-traffic protection setting. This establishes a reproducible test failure, not the cause of every Chrome issue on a physical device.

A selected stability FAIL remains a FAIL for admission and in the live verdict. For a live route only, if the sole definite selected-service failure is a stability series marked `STABILITY_SPREAD` or `STABILITY_LATENCY`, recovery waits for a fresh same-context full check after at least 30 seconds. Quality-triggered recovery requests are spaced by at least 120 seconds, including across soft reconnects; the recheck remains scheduled during calls and is canceled when its context is torn down. A response failure or another definite selected-service failure retains the existing immediate recovery path. This reduces rapid disruptive switching, but does not promise a switch can never interrupt traffic; Android/device testing remains necessary.

Local policy tests cover strict admission, genuine response failures, mixed failures, same-context confirmation, reset, and cooldown. The candidate still requires all full CI gates and real-device verification.
