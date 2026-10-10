package com.vlesscardvpn.core

import com.vlesscardvpn.model.Mode
import com.vlesscardvpn.xray.Mask
import com.vlesscardvpn.xray.Masks
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * «Помощник»: a chat about everything the app has measured (mask statistics per network, servers, speed, the phone),
 * and it runs the app for you: masks, Auto, YouTube speed-up, settings.
 * Not a large language model: it understands questions by key words (in Russian, typos tolerated by stems) and
 * answers from real data — what the network learned about this provider, the best masks, servers, speed, YouTube.
 * Buttons under an answer start the matching action; its result comes back into the chat.
 */
object Assistant {
    data class Btn(val label: String, val cmd: String)
    /** [auto]: an order («ускорь YouTube») — the first button runs right away. */
    data class Msg(val mine: Boolean, val text: String, val buttons: List<Btn> = emptyList(), val auto: Boolean = false)

    const val HELLO = "Привет! Я помощник VLESS Card. Спросите, что режут в вашей сети, какая маска лучше, почему медленно, " +
        "что с телефоном — или скажите, что сделать: «ускорь YouTube», «смени маску», «облегчи маску», «включи авто», «включи турбо»."
    val START = listOf(Btn("Что режут в моей сети?", "что режут в моей сети"), Btn("Лучшие маски", "какие маски лучше"),
        Btn("Почему медленно?", "почему медленно"), Btn("Что ты умеешь?", "помощь"))

    val messages = MutableStateFlow(listOf(Msg(false, HELLO, START)))

    /** Facts the answer is built from (filled from the store in the app, by hand in tests). */
    data class Facts(
        val net: String, val stats: Map<String, MaskStat>, val connected: Boolean, val route: String, val check: String,
        val servers: Int, val working: Int, val best: List<Triple<String, Int, Int>>,   // name, ms, kbps
        val mode: Mode, val ytDpi: Boolean?, val dpiKnown: Boolean, val selectedMask: Mask?,
        val phone: PhoneInfo? = null,
    )

    private fun has(q: String, vararg stems: String) = stems.any { q.contains(it) }

    /** Answer to [question]: text + action buttons. Pure (no side effects) — the commands run in [send]. */
    fun answer(question: String, f: Facts): Msg {
        val q = question.lowercase().replace('ё', 'е').trim()
        val host = Regex("([a-z0-9-]+\\.)+[a-z]{2,}").find(q)?.value
        return when {
            q.isEmpty() -> Msg(false, "Напишите вопрос 🙂", START)
            host != null && has(q, "сайт", "открыва", "провер", "работает", "не грузит", "http") || host != null && q == host ->
                Msg(false, "Проверяю $host тремя путями: напрямую, через VPN и через обход DPI…", listOf(Btn("Проверить $host", "!site $host")))
            // ---- orders: done right away
            has(q, "ускор", "быстрее", "разгон", "оптимиз", "тормоз") && has(q, "ютуб", "youtube", "видео", "ютьюб") || has(q, "ускорь", "оптимизируй", "разгони") ->
                Msg(false, "Ускоряю YouTube: включаю «Умный YouTube» и ускорение телефона, облегчаю тяжёлые маски, меряю реальную скорость лучших серверов и выбираю самые быстрые.", listOf(Btn("Ускорить YouTube", "!yt")), auto = true)
            has(q, "облегч", "легкую маск", "легче маск") -> Msg(false, "Пробую лёгкие маски вместо тяжёлых (мелкие пакеты, один поток) — они быстрее.", listOf(Btn("Облегчить маски", "!lighten")), auto = true)
            has(q, "смени маск", "другую маск", "поменяй маск", "следующую маск", "переключи маск") -> Msg(false, "Переключаю серверы на следующую проверенную маску.", listOf(Btn("Сменить маску", "!nextmask")), auto = true)
            has(q, "включи авто", "режим авто", "на авто", "авто режим", "поставь авто") -> Msg(false, "Включаю режим «Авто».", listOf(Btn("Режим «Авто»", "!auto")), auto = true)
            has(q, "отключи впн", "выключи впн", "отключи vpn", "выключи vpn", "отключись") -> Msg(false, "Отключаю VPN.", listOf(Btn("Отключить", "!disconnect")), auto = true)
            has(q, "подключи", "включи впн", "включи vpn", "подключись", "запусти впн") -> Msg(false, "Подключаю VPN.", listOf(Btn("Подключить", "!connect")), auto = true)
            has(q, "автопилот", "сам улучша", "сам чини", "следи за", "сам следи", "улучшай сам") -> {
                val off = has(q, "выключи", "отключи", "не надо")
                Msg(false, if (off) "Выключаю автопилот." else "Включаю автопилот: пока VPN включён, сам чиню связь и облегчаю маски, а заодно прямо сейчас улучшаю соединение.",
                    listOf(Btn("Применить", "!set autopilot ${if (off) "off" else "on"}")), auto = true)
            }
            has(q, "улучши соедин", "улучши связь", "улучши интернет", "сделай лучше", "улучши всё", "улучши все", "оптимизируй всё", "оптимизируй все") ->
                Msg(false, "Улучшаю всё: проверяю серверы и маски, облегчаю тяжёлые, ускоряю YouTube и выбираю самые быстрые.", listOf(Btn("Улучшить", "!yt")), auto = true)
            has(q, "варп", "warp", "клаудфлер", "cloudflare") -> {
                val st = Store.state.value
                val acc = st.servers.any { Masks.isWarp(it) }
                val off = has(q, "выключи", "отключи", "не надо")
                when {
                    off -> Msg(false, "Выключаю «WARP через сервер».", listOf(Btn("Применить", "!set warpChain off")), auto = true)
                    !acc -> Msg(false, "Аккаунта WARP ещё нет — создам (если Cloudflare закрыт, регистрирую через обход DPI или VPN), потом включу «WARP через сервер»: так WARP работает, даже когда оператор режет его напрямую.", listOf(Btn("Создать WARP", "!warp")), auto = true)
                    else -> Msg(false, "Включаю «WARP через сервер»: трафик идёт на ваш сервер с маскировкой, а оттуда в Cloudflare WARP. Оператор видит только маску, сайты — адрес Cloudflare.", listOf(Btn("Применить", "!set warpChain on")), auto = true)
                }
            }
            has(q, "почему не работ", "почему не подкл", "диагност", "что не так", "в чём причин", "в чем причин", "заморозк") -> Msg(false, "Проверю причину: белые списки, блокировку серверов по IP или по имени (SNI) и «заморозку» загрузки внутри VPN.", listOf(Btn("Проверить", "!diag")), auto = true)
            has(q, "белые списк", "белый спис", "белых спис", "вайтлист", "whitelist") -> Msg(false, "Проверю, включены ли белые списки у оператора: открою напрямую разрешённые, обычные и заблокированные сайты.", listOf(Btn("Проверить", "!wl")), auto = true)
            has(q, "охлад", "греет", "горяч", "нагрел", "нагрев", "температур", "перегр") -> Msg(false, cooling(f), listOf(Btn("Снизить нагрузку", "!cool")), auto = has(q, "охлади", "остуди", "снизь"))
            has(q, "ускорь телефон", "ускорение телефона", "разгони телефон", "оптимизируй телефон", "автоускор") ->
                Msg(false, "Ускоряю телефон в VPN: крупные пакеты (меньше работы процессору), блокировка рекламы (меньше трафика и батареи), лёгкие маски. Память и чужие приложения Android трогать не даёт — это делают только системные «очистки».", listOf(Btn("Ускорить", "!boostphone")), auto = true)
            has(q, "gps", "геолок", "местополож", "локаци", "геопоз", "жпс") -> {
                val off = has(q, "выключи", "отключи", "убери", "верни")
                Msg(false, if (off) "Выключаю подмену GPS — вернётся настоящее местоположение." else "Включаю GPS под страну сервера. Если Android не даст — подскажу, где разрешить.",
                    listOf(Btn("Применить", "!set gps ${if (off) "off" else "on"}")), auto = true)
            }
            has(q, "под приложени", "подстраива", "gemini", "джемини", "гемини", "chatgpt", "чатгпт") -> {
                val off = has(q, "выключи", "отключи", "не надо")
                Msg(false, if (off) "Выключаю подстройку под приложения." else "Включаю подстройку: открыли Gemini/ChatGPT — переключу на серверы страны, где они работают (США, Нидерланды…), YouTube — ускорю.",
                    listOf(Btn("Применить", "!set appAware ${if (off) "off" else "on"}")), auto = true)
            }
            has(q, "турбо", "ускорение телефона", "реклам", "quic") && has(q, "включи", "выключи", "отключи", "убери", "блокир") -> {
                val off = has(q, "выключи", "отключи") && !has(q, "реклам") || has(q, "реклам") && has(q, "не блокир", "покажи рекламу", "выключи блок", "отключи блок")
                val key = when { has(q, "реклам") -> "blockAds"; has(q, "quic") -> "blockQuic"; else -> "turbo" }
                Msg(false, "Меняю настройку.", listOf(Btn("Применить", "!set $key ${if (off) "off" else "on"}")), auto = true)
            }
            has(q, "телефон", "устройств", "батаре", "памят", "андроид", "android", "смартфон", "заряд", "озу") -> Msg(false, phone(f),
                (if (f.phone?.batteryOptimized == true) listOf(Btn("Разрешить работу в фоне", "!battery")) else emptyList()) + Btn("Ускорить YouTube", "!yt"))
            has(q, "привет", "здравств", "кто ты", "ты кто") -> Msg(false, HELLO, START)
            has(q, "умеешь", "помощь", "help", "команд", "что можешь") -> Msg(false, HELP, START)
            has(q, "обуч", "статист", "сколько провер") -> Msg(false, "Знаю результаты ${f.stats.values.sumOf { it.ok + it.fail }} проверок масок в сети «${f.net}» (${f.stats.size} разных масок). Каждый подбор маскировки добавляет новые.", listOf(Btn("Что режут в моей сети?", "что режут")))
            has(q, "режут", "блокир", "провайдер", "оператор", "тспу", "dpi", "что в сети", "моей сети", "сеть") -> Msg(false, insights(f), listOf(Btn("Подобрать маскировку", "!masks"), Btn("Лучшие маски", "лучшие маски")))
            has(q, "подбер", "найди маск", "подобр", "новую маск") -> Msg(false, "Запускаю подбор маскировки для выбранных серверов — сначала пробую те, что уже проходили в этой сети.", listOf(Btn("Подобрать маскировку", "!masks")))
            has(q, "маск", "маскир") -> Msg(false, topMasks(f), listOf(Btn("Подобрать маскировку", "!masks")))
            has(q, "ютуб", "youtube", "видео") -> Msg(false, youtube(f), listOf(Btn("Измерить скорость", "!speed")))
            has(q, "медлен", "тормоз", "скорост", "лагает", "долго") -> Msg(false, slow(f), listOf(Btn("Измерить скорость", "!speed"), Btn("Проверить серверы", "!test")))
            has(q, "не работ", "не подключ", "нет интернет", "отвал", "сломал", "проблем", "ошибк") -> Msg(false, broken(f), listOf(Btn("Проверить серверы", "!test"), Btn("Подобрать маскировку", "!masks")))
            has(q, "сервер", "лучший", "быстр") -> Msg(false, servers(f), listOf(Btn("Проверить серверы", "!test")))
            has(q, "спасиб", "круто", "класс") -> Msg(false, "Пожалуйста! Чем больше подборов, тем точнее я угадываю маски для ваших сетей.")
            else -> Msg(false, "Не понял вопрос. " + HELP, START)
        }
    }

    private fun cooling(f: Facts): String {
        val ctx = appRef ?: return "Не удалось прочитать температуру."
        val t = Thermal.tempC(ctx); val s = Thermal.status(ctx)
        return "Телефон: ${Thermal.label(t, s).ifEmpty { "температура неизвестна" }}." +
            (if (Thermal.cooling) " Сейчас я уже снижаю нагрузку." else "") +
            "\nЧто могу: на время нагрева остановить фоновые проверки и эволюцию масок, включить крупные пакеты и лёгкие маски — VPN будет греть меньше. " +
            "Охладить телефон сильнее помогут: снять чехол, не заряжать во время игры/видео, уменьшить яркость."
    }

    private fun phone(f: Facts): String {
        val p = f.phone ?: return "Не удалось прочитать данные телефона."
        val pr = p.problems()
        return p.text() + "\n" + if (pr.isEmpty()) "Ничего не мешает VPN ✓" else "Что мешает VPN:\n" + pr.joinToString("\n") { "• $it" }
    }

    const val HELP = "Я понимаю и выполняю: «ускорь YouTube», «облегчи маску», «смени маску», «включи авто», «подключи / отключи VPN», " +
        "«включи турбо», «блокируй рекламу», «что с телефоном». Отвечаю на вопросы вроде: «что режут в моей сети», «какие маски лучше», «подбери маску», «почему медленно», " +
        "«как YouTube», «не работает», «лучшие серверы», «проверь rutracker.org», «как ты обучена», «сбрось обучение»."

    private fun pct(p: Double) = "${(p * 100).toInt()}%"
    private fun total(f: Facts) = f.stats.values.sumOf { it.ok + it.fail }
    private fun know(f: Facts) = total(f) >= 20
    private fun brainNote(f: Facts) = if (know(f)) "" else "Пока мало данных — после первого «Подобрать маскировку» начну подсказывать."

    /** Tricks: how to recognize each in a mask. */
    private val TRICKS: List<Pair<String, (Mask) -> Boolean>> = listOf(
        "дробление TLS hello" to { m -> m.packets.isNotEmpty() },
        "«ступеньки» кусков" to { m -> m.lengths.isNotEmpty() },
        "обход DPI под Xray" to { m -> m.dpi.isNotEmpty() },
        "белый SNI" to { m -> m.sni.isNotEmpty() },
        "узкий канал (MSS)" to { m -> m.mss > 0 },
        "один поток (mux)" to { m -> m.mux },
        "отпечаток Firefox" to { m -> m.fingerprint == "firefox" },
        "отпечаток Safari" to { m -> m.fingerprint == "safari" },
        "ALPN http/1.1" to { m -> m.alpn == "http/1.1" },
        "быстрый старт (TFO)" to { m -> m.tfo },
        "путь через IPv6" to { m -> m.ipv6 },
    )

    /** Per trick: success share of masks with it minus without it, on this network (only tricks tried ≥ 3 times both ways). */
    fun effects(stats: Map<String, MaskStat>): List<Pair<String, Double>> {
        val rows = stats.mapNotNull { (id, st) -> Masks.byId(id)?.let { it to st } }
        fun rate(l: List<Pair<Mask, MaskStat>>): Double? { val ok = l.sumOf { it.second.ok }; val n = l.sumOf { it.second.ok + it.second.fail }; return if (n < 3) null else ok.toDouble() / n }
        return TRICKS.mapNotNull { (name, has) ->
            val (y, n) = rows.partition { has(it.first) }
            val a = rate(y); val b = rate(n)
            if (a == null || b == null) null else name to a - b
        }.sortedByDescending { it.second }
    }

    /** Share of plain connections (no tricks at all) that passed here; null = not tried enough. */
    private fun plainRate(f: Facts): Double? = f.stats.mapNotNull { (id, st) -> Masks.byId(id)?.takeIf { m -> TRICKS.none { it.second(m) } }?.let { st } }
        .let { l -> val n = l.sumOf { it.ok + it.fail }; if (n < 3) null else l.sumOf { it.ok }.toDouble() / n }

    private fun insights(f: Facts): String {
        if (!know(f)) return "Про сеть «${f.net}» я пока мало знаю (${total(f)} проверок). " + brainNote(f)
        val e = effects(f.stats)
        val p0 = plainRate(f)
        val up = e.filter { it.second > 0.05 }.take(4); val down = e.filter { it.second < -0.05 }.takeLast(3).reversed()
        return buildString {
            append("Сеть «${f.net}», по ${total(f)} проверкам масок.")
            if (p0 != null) append(" Обычное соединение без хитростей прошло в ${pct(p0)} случаев" + if (p0 < 0.4) " — здесь явно режут." else if (p0 < 0.75) " — режут частично." else " — почти не режут.")
            if (up.isNotEmpty()) append("\nПомогает: " + up.joinToString(", ") { "${it.first} (+${pct(it.second)})" } + ".")
            if (down.isNotEmpty()) append("\nМешает: " + down.joinToString(", ") { "${it.first} (−${pct(-it.second)})" } + ".")
            if (up.isEmpty() && down.isEmpty()) append("\nЗаметной разницы между приёмами нет — выбирайте по скорости.")
            if (f.dpiKnown) append("\nДля этой сети уже подобран обход DPI без сервера.")
        }
    }

    private fun topMasks(f: Facts): String {
        val cur = f.selectedMask?.let { m -> "Сейчас у лучшего сервера: ${m.title}" + (f.stats[m.id]?.let { " (прошла ${it.ok} из ${it.ok + it.fail})" } ?: "") + "." } ?: ""
        val list = f.stats.entries.filter { it.value.ok > 0 }.mapNotNull { (id, st) -> Masks.byId(id)?.let { it to st } }
            .sortedWith(compareByDescending<Pair<Mask, MaskStat>> { it.second.score - Masks.cost(it.first) * 0.03 }).take(5)
        if (list.isEmpty()) return listOf(cur, "Рейтинг масок появится после первого подбора маскировки.").filter { it.isNotEmpty() }.joinToString("\n")
        return (if (cur.isNotEmpty()) cur + "\n" else "") + "Лучшие в «${f.net}» по проверкам:\n" +
            list.mapIndexed { i, (m, st) -> "${i + 1}. ${m.title} — прошла ${st.ok} из ${st.ok + st.fail}" + if (Masks.cost(m) >= 2) " (тяжёлая, медленнее)" else "" }.joinToString("\n")
    }

    private fun youtube(f: Facts) = when {
        f.ytDpi == true -> "В этой сети YouTube идёт напрямую через обход DPI — так оказалось быстрее, чем через серверы. Если начнёт тормозить, я сам верну серверы."
        f.ytDpi == false -> "Здесь YouTube быстрее через серверы (или обход не открыл его), поэтому он идёт через VPN."
        f.mode != Mode.AUTO -> "«Умный YouTube» работает только в режиме «Авто»."
        else -> "Скорость YouTube через серверы и через обход DPI я сравню в фоне после подключения в этой сети."
    } + (f.best.firstOrNull()?.let { (n, _, k) -> if (k > 0) "\nЛучший сервер ($n) качает ${"%.1f".format(k / 1000.0)} Мбит/с" + if (k < 3000) " — для HD мало." else "." else "" } ?: "")

    private fun slow(f: Facts) = buildString {
        if (!f.connected) { append("VPN сейчас выключен. Подключитесь — тогда смогу измерить."); return@buildString }
        append("Маршрут: ${f.route}.")
        f.selectedMask?.let { if (Masks.cost(it) >= 2) append("\nМаска «${it.title}» тяжёлая (мелкие пакеты/один поток) — это режет скорость. Я уже ищу лёгкую в фоне; «Подобрать маскировку» ускорит.") }
        f.best.firstOrNull()?.let { (n, ms, k) -> append("\nЛучший сервер: $n, $ms мс" + if (k > 0) ", ${"%.1f".format(k / 1000.0)} Мбит/с." else ".") }
        if (f.working < 3) append("\nРабочих серверов мало (${f.working}) — обновите подписки или добавьте ещё.")
        append("\nНажмите «Измерить скорость» — скажу точную цифру.")
    }

    private fun broken(f: Facts) = buildString {
        append(if (f.connected) "VPN подключён: ${f.check.ifEmpty { "проверяю…" }}" else "VPN выключен.")
        append("\nРаботают ${f.working} из ${f.servers} серверов.")
        if (f.working == 0) append(if (f.dpiKnown) " Пока можно без сервера — через обход DPI (Авто включит сам)." else " Нужна проверка и подбор маскировки.")
        if ((plainRate(f) ?: 1.0) < 0.4) append("\nВ сети «${f.net}» сильно режут — без маскировки почти ничего не пройдёт.")
    }

    private fun servers(f: Facts) = if (f.best.isEmpty()) "Рабочих серверов пока нет — нажмите «Проверить серверы»." else
        "Лучшие сейчас (из ${f.working} рабочих):\n" + f.best.take(5).mapIndexed { i, (n, ms, k) -> "${i + 1}. $n — $ms мс" + if (k > 0) ", ${"%.1f".format(k / 1000.0)} Мбит/с" else "" }.joinToString("\n")

    // ---------- in the app ----------
    fun facts(app: android.content.Context): Facts {
        val st = Store.state.value; val net = Net.key(app)
        val ok = st.servers.filter { st.state(it).works }.sortedBy { st.state(it).score }
        val sel = st.selected.firstOrNull() ?: ok.firstOrNull()
        val s = Tunnel.status.value
        val stats = (st.maskStats[net] ?: st.maskStats[Net.family(net)]).orEmpty()
        return Facts(net, stats, s.state == Tunnel.State.CONNECTED, s.route, s.check, st.servers.size, ok.size,
            ok.take(5).map { Triple(it.name, st.state(it).realMs, st.state(it).kbps) }, st.settings.mode, st.settings.ytDpi[net],
            (st.settings.dpiRemembered[net] ?: st.settings.dpiRemembered[Net.family(net)]) != null, sel?.let { Masks.byId(st.state(it).maskFor(net)) },
            runCatching { PhoneInfo.collect(app) }.getOrNull())
    }

    /** Everything measured, as text for the language model. */
    fun factsText(f: Facts): String = buildString {
        append("Сеть: ${f.net}. Режим: ${f.mode.name}. VPN: ").append(if (f.connected) "подключён, маршрут «${f.route}», проверка: ${f.check}" else "выключен").append('\n')
        append(servers(f)).append('\n')
        append(insights(f)).append('\n')
        append("YouTube: ").append(youtube(f)).append('\n')
        f.selectedMask?.let { append("Маска лучшего сервера: ${it.title}" + if (Masks.cost(it) >= 2) " (тяжёлая)\n" else "\n") }
        f.phone?.let { append('\n').append(it.text()); it.problems().takeIf { p -> p.isNotEmpty() }?.let { p -> append(" Мешает: ").append(p.joinToString(" ")) } }
    }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    /** Messages from the app itself (AppWatch). */
    fun note(t: String) { scope.launch { post(Msg(false, t)) } }

    private fun post(m: Msg) { messages.value = (messages.value + m).takeLast(80) }

    @Volatile private var appRef: android.content.Context? = null

    fun send(app: android.content.Context, text: String) {
        appRef = app.applicationContext
        val t = text.trim(); if (t.isEmpty()) return
        if (t.startsWith("!")) { run(app, t); return }
        post(Msg(true, t))
        val a = answer(t, facts(app))
        post(a)
        if (a.auto) { run(app, a.buttons.first().cmd, quiet = true); return }
        // a site in the question: check right away (one tap less)
        a.buttons.firstOrNull { it.cmd.startsWith("!site ") }?.let { run(app, it.cmd, quiet = true) }
    }

    /** Commands of the buttons; results come back as messages. */
    fun run(app: android.content.Context, cmd: String, quiet: Boolean = false) {
        when {
            cmd.startsWith("!site ") -> {
                val host = cmd.removePrefix("!site ")
                if (!quiet) post(Msg(true, "Проверь $host"))
                Actions.checkSite(host)
                scope.launch { delay(300); while (Actions.siteCheck.value.running) delay(300); Actions.siteCheck.value.let { r ->
                    post(Msg(false, r.verdict + if (r.details.isNotEmpty()) "\n" + r.details else "")) } }
            }
            cmd == "!yt" -> job(app, if (quiet) null else "Ускорь YouTube") { Actions.boostYoutube() }
            cmd == "!lighten" -> job(app, if (quiet) null else "Облегчи маски") { Actions.lighten() }
            cmd == "!nextmask" -> {
                if (!quiet) post(Msg(true, "Смени маску"))
                val sel = Store.state.value.selected
                if (sel.isNotEmpty() && Actions.nextMasks(sel)) { reconnect(app); post(Msg(false, "Маска сменена" + if (Tunnel.socks != null) " — переподключаюсь." else ".")) }
                else post(Msg(false, "Запасных масок нет — подберу новые.", listOf(Btn("Подобрать маскировку", "!masks"))))
            }
            cmd == "!auto" -> { Store.update { it.copy(settings = it.settings.copy(mode = Mode.AUTO)) }; reconnect(app); post(Msg(false, "Режим «Авто» включён" + if (Tunnel.socks != null) " — переподключаюсь." else ". Нажмите «Подключить».")) }
            cmd == "!connect" -> {
                if (Store.state.value.settings.proxyOnly || android.net.VpnService.prepare(app) == null) { com.vlesscardvpn.vpn.TunnelService.start(app); post(Msg(false, "Подключаюсь…")) }
                else post(Msg(false, "Нажмите «Подключить» на главной один раз — Android спросит разрешение на VPN, дальше смогу сам."))
            }
            cmd == "!disconnect" -> { com.vlesscardvpn.vpn.TunnelService.stop(app); post(Msg(false, "VPN отключён.")) }
            cmd.startsWith("!set ") -> {
                val (key, v) = cmd.removePrefix("!set ").split(" ").let { it[0] to (it.getOrNull(1) == "on") }
                Store.update { s -> s.copy(settings = when (key) { "blockAds" -> s.settings.copy(blockAds = v); "blockQuic" -> s.settings.copy(blockQuic = v); "gps" -> s.settings.copy(gpsSpoof = v); "warpChain" -> s.settings.copy(warpChain = v); "autopilot" -> s.settings.copy(autopilot = v); "appAware" -> s.settings.copy(appAware = v); else -> s.settings.copy(turbo = v) }) }
                reconnect(app)
                post(Msg(false, (when (key) { "blockAds" -> "Блокировка рекламы"; "blockQuic" -> "Блокировка QUIC"; "gps" -> "Подмена GPS"; "warpChain" -> "WARP через сервер"; "autopilot" -> "Автопилот"; "appAware" -> "Подстройка под приложения"; else -> "Ускорение телефона" }) + if (v) " включена." else " выключена."))
                if (v && key == "autopilot") { Actions.boostYoutube(); post(Msg(false, "Начал улучшать соединение — результат напишу здесь.")) }
                if (v && key == "gps") post(Msg(false, "Подмена GPS работает, пока включён VPN: телефон «окажется» в столице страны сервера. Нужно один раз разрешить: Параметры разработчика → «Приложение для фиктивных местоположений» → VLESS Card." +
                    GpsMock.state.value.let { if (it.isNotEmpty()) "\nСейчас: $it" else "" }, listOf(Btn("Открыть параметры разработчика", "!devsettings"))))
                if (v && key == "appAware" && !AppWatch.hasAccess(app)) post(Msg(false, "Чтобы видеть, какое приложение открыто, нужен «Доступ к истории использования» для VLESS Card.", listOf(Btn("Разрешить доступ", "!usage"))))
            }
            cmd == "!warp" -> { Store.update { it.copy(settings = it.settings.copy(warpChain = true)) }; job(app, null) { Actions.setupWarp() } }
            cmd == "!diag" -> scope.launch {
                post(Msg(false, "Диагностирую… (~15 с)"))
                val r = Diagnose.report(Store.state.value.selected, Tunnel.socks?.port)
                post(Msg(false, r, if (r.contains("SNI") || r.contains("белые списки")) listOf(Btn("Подобрать маскировку", "!masks")) else emptyList()))
            }
            cmd == "!wl" -> scope.launch {
                val r = Whitelist.check()
                post(Msg(false, Whitelist.report(r), if (r.verdict == Whitelist.Verdict.WHITELIST) listOf(Btn("Подобрать маскировку", "!masks")) else emptyList()))
            }
            cmd == "!cool" -> {
                Store.update { it.copy(settings = it.settings.copy(autoCool = true, turbo = true)) }; Thermal.cooling = true; reconnect(app)
                if (Store.summary.value.heavyMask) Actions.lighten()
                post(Msg(false, "Снизил нагрузку: фоновые проверки на паузе, крупные пакеты включены" + (if (Store.summary.value.heavyMask) ", ищу лёгкую маску" else "") + ". Когда телефон остынет, сам всё верну."))
            }
            cmd == "!boostphone" -> {
                Store.update { it.copy(settings = it.settings.copy(turbo = true, blockAds = true, autoCool = true)) }; reconnect(app)
                if (Store.summary.value.heavyMask) Actions.lighten()
                post(Msg(false, "Готово: ускорение и блокировка рекламы включены, охлаждение следит за температурой." +
                    (if (PhoneInfo.collect(app).batteryOptimized) " Ещё: Android ограничивает VPN в фоне — разрешите работу без ограничений." else ""),
                    if (PhoneInfo.collect(app).batteryOptimized) listOf(Btn("Разрешить работу в фоне", "!battery")) else emptyList()))
            }
            cmd == "!devsettings" -> { GpsMock.openDevSettings(app); post(Msg(false, "Если пункта нет — включите режим разработчика: О телефоне → 7 раз нажать «Номер сборки».")) }
            cmd == "!usage" -> { AppWatch.openAccess(app); post(Msg(false, "Найдите VLESS Card и включите доступ.")) }
            cmd == "!battery" -> runCatching {
                app.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                post(Msg(false, "Открыл настройки: найдите VLESS Card → «Не оптимизировать» / «Без ограничений»."))
            }
            cmd == "!speed" -> {
                post(Msg(true, "Измерь скорость")); Actions.speedTest()
                scope.launch { delay(300); while (Actions.speed.value.running) delay(300); post(Msg(false, Actions.speed.value.text)) }
            }
            cmd == "!masks" || cmd == "!test" -> {
                if (Actions.progress.value.running) { post(Msg(false, "Сейчас уже идёт «${Actions.progress.value.title}» — дождитесь, и я покажу результат.")); return }
                val st = Store.state.value
                if (cmd == "!masks") {
                    val list = st.selected.ifEmpty { st.servers.filter { st.state(it).tcpMs > 0 }.sortedBy { st.state(it).tcpMs }.take(8) }
                    if (list.isEmpty()) { post(Msg(false, "Нет доступных серверов — сначала «Проверить серверы».", listOf(Btn("Проверить серверы", "!test")))); return }
                    post(Msg(true, "Подбери маскировку")); Actions.findMasks(list)
                } else { post(Msg(true, "Проверь серверы")); Actions.testAll() }
                post(Msg(false, "Запустил. Результат появится здесь (и на главном экране)."))
                scope.launch { delay(500); while (Actions.progress.value.running) delay(500); post(Msg(false, Actions.progress.value.message.ifEmpty { "Готово." }, listOf(Btn("Что режут в моей сети?", "что режут")))) }
            }
            else -> send(app, cmd.removePrefix("!"))
        }
    }

    private fun reconnect(app: android.content.Context) { if (Tunnel.socks != null) com.vlesscardvpn.vpn.TunnelService.start(app) }

    /** Starts a long action and posts its result into the chat when it ends. */
    private fun job(app: android.content.Context, userText: String?, start: () -> Unit) {
        if (Actions.progress.value.running) { post(Msg(false, "Сейчас уже идёт «${Actions.progress.value.title}» — дождитесь, и я покажу результат.")); return }
        if (userText != null) post(Msg(true, userText))
        start()
        scope.launch { delay(500); while (Actions.progress.value.running) delay(500); post(Msg(false, Actions.progress.value.message.ifEmpty { "Готово." })) }
    }

}
