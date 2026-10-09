package com.vlesscardvpn.core

import com.vlesscardvpn.model.Mode
import com.vlesscardvpn.xray.Mask
import com.vlesscardvpn.xray.MaskBrain
import com.vlesscardvpn.xray.Masks
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * «Помощник»: a chat with the app's on-phone mask network ([MaskBrain]) and everything the app has measured.
 * Not a large language model: it understands questions by key words (in Russian, typos tolerated by stems) and
 * answers from real data — what the network learned about this provider, the best masks, servers, speed, YouTube.
 * Buttons under an answer start the matching action; its result comes back into the chat.
 */
object Assistant {
    data class Btn(val label: String, val cmd: String)
    data class Msg(val mine: Boolean, val text: String, val buttons: List<Btn> = emptyList())

    const val HELLO = "Привет! Я помощник VLESS Card и нейросеть масок на этом телефоне. Спросите, что режут в вашей сети, " +
        "какая маска лучше, почему медленно или не открывается сайт — отвечу по реальным замерам и могу сам запустить нужное."
    val START = listOf(Btn("Что режут в моей сети?", "что режут в моей сети"), Btn("Лучшие маски", "какие маски лучше"),
        Btn("Почему медленно?", "почему медленно"), Btn("Что ты умеешь?", "помощь"))

    val messages = MutableStateFlow(listOf(Msg(false, HELLO, START)))

    /** Facts the answer is built from (filled from the store in the app, by hand in tests). */
    data class Facts(
        val net: String, val brain: MaskBrain, val connected: Boolean, val route: String, val check: String,
        val servers: Int, val working: Int, val best: List<Triple<String, Int, Int>>,   // name, ms, kbps
        val mode: Mode, val ytDpi: Boolean?, val dpiKnown: Boolean, val selectedMask: Mask?,
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
            has(q, "привет", "здравств", "кто ты", "ты кто") -> Msg(false, HELLO, START)
            has(q, "умеешь", "помощь", "help", "команд", "что можешь") -> Msg(false, HELP, START)
            has(q, "сброс", "забудь", "обнули", "переобуч") ->
                Msg(false, "Могу стереть всё, чему нейросеть научилась (${f.brain.samples} проверок). Обычно это не нужно — старые сети она не путает с новыми.",
                    listOf(Btn("Да, стереть обучение", "!reset")))
            has(q, "обуч", "точност", "угадыв", "нейрос", "нейрон", "модел") -> Msg(false, "Нейросеть масок " + MaskBrain.summary() + ". " + brainNote(f), listOf(Btn("Что режут в моей сети?", "что режут")))
            has(q, "режут", "блокир", "провайдер", "оператор", "тспу", "dpi", "что в сети", "моей сети", "сеть") -> Msg(false, insights(f), listOf(Btn("Подобрать маскировку", "!masks"), Btn("Лучшие маски", "лучшие маски")))
            has(q, "подбер", "найди маск", "подобр", "новую маск") -> Msg(false, "Запускаю подбор маскировки для выбранных серверов — нейросеть поставит вероятные маски первыми.", listOf(Btn("Подобрать маскировку", "!masks")))
            has(q, "маск", "маскир") -> Msg(false, topMasks(f), listOf(Btn("Подобрать маскировку", "!masks")))
            has(q, "ютуб", "youtube", "видео") -> Msg(false, youtube(f), listOf(Btn("Измерить скорость", "!speed")))
            has(q, "медлен", "тормоз", "скорост", "лагает", "долго") -> Msg(false, slow(f), listOf(Btn("Измерить скорость", "!speed"), Btn("Проверить серверы", "!test")))
            has(q, "не работ", "не подключ", "нет интернет", "отвал", "сломал", "проблем", "ошибк") -> Msg(false, broken(f), listOf(Btn("Проверить серверы", "!test"), Btn("Подобрать маскировку", "!masks")))
            has(q, "сервер", "лучший", "быстр") -> Msg(false, servers(f), listOf(Btn("Проверить серверы", "!test")))
            has(q, "спасиб", "круто", "класс") -> Msg(false, "Пожалуйста! Чем больше подборов, тем точнее я угадываю маски для ваших сетей.")
            else -> Msg(false, "Не понял вопрос. " + HELP, START)
        }
    }

    const val HELP = "Я понимаю вопросы вроде: «что режут в моей сети», «какие маски лучше», «подбери маску», «почему медленно», " +
        "«как YouTube», «не работает», «лучшие серверы», «проверь rutracker.org», «как ты обучена», «сбрось обучение»."

    private fun pct(p: Double) = "${(p * 100).toInt()}%"
    private fun brainNote(f: Facts) = if (f.brain.trained) "" else "Пока мало данных — после первого «Подобрать маскировку» начну подсказывать."

    /** What each trick changes for this network, from the network's own predictions (vs the plain mask). */
    fun effects(brain: MaskBrain, net: String): List<Pair<String, Double>> {
        val base = Masks.DEFAULT
        val tricks = listOf(
            "дробление TLS hello" to base.copy(packets = "tlshello", length = "50-100", delay = "10-20"),
            "«ступеньки» кусков" to base.copy(packets = "tlshello", lengths = "1-1,2-4,5-10", delays = "1-2,5-10,10-20"),
            "обход DPI под Xray" to base.copy(dpi = Masks.CURRENT_DPI),
            "белый SNI" to base.copy(sni = "vk.com"),
            "узкий канал (MSS)" to base.copy(mss = 300),
            "один поток (mux)" to base.copy(mux = true),
            "отпечаток Firefox" to base.copy(fingerprint = "firefox"),
            "отпечаток Safari" to base.copy(fingerprint = "safari"),
            "ALPN http/1.1" to base.copy(alpn = "http/1.1"),
            "быстрый старт (TFO)" to base.copy(tfo = true),
            "путь через IPv6" to base.copy(ipv6 = true),
        )
        val p0 = brain.predict(base, net, null)
        return tricks.map { (n, m) -> n to brain.predict(m, net, null) - p0 }.sortedByDescending { it.second }
    }

    private fun insights(f: Facts): String {
        if (!f.brain.trained) return "Про сеть «${f.net}» я пока мало знаю (${f.brain.samples} проверок). " + brainNote(f)
        val e = effects(f.brain, f.net)
        val p0 = f.brain.predict(Masks.DEFAULT, f.net, null)
        val up = e.filter { it.second > 0.05 }.take(4); val down = e.filter { it.second < -0.05 }.takeLast(3).reversed()
        return buildString {
            append("Сеть «${f.net}». Обычное соединение без маски, по моей оценке, проходит в ${pct(p0)} случаев")
            append(if (p0 < 0.4) " — здесь явно режут." else if (p0 < 0.75) " — режут частично." else " — почти не режут.")
            if (up.isNotEmpty()) append("\nПомогает: " + up.joinToString(", ") { "${it.first} (+${pct(it.second)})" } + ".")
            if (down.isNotEmpty()) append("\nМешает: " + down.joinToString(", ") { "${it.first} (−${pct(-it.second)})" } + ".")
            if (up.isEmpty() && down.isEmpty()) append("\nЗаметной разницы между приёмами не вижу — выбирайте по скорости.")
            if (f.dpiKnown) append("\nДля этой сети уже подобран обход DPI без сервера.")
            if (f.brain.accuracy >= 0) append("\n(угадываю ${pct(f.brain.accuracy)} новых результатов)")
        }
    }

    private fun topMasks(f: Facts): String {
        val cur = f.selectedMask?.let { "Сейчас у лучшего сервера: ${it.title}" + if (f.brain.trained) " (шанс ${pct(f.brain.predict(it, f.net, null))})." else "." } ?: ""
        if (!f.brain.trained) return listOf(cur, "Рейтинг масок появится после первого подбора. " + brainNote(f)).filter { it.isNotEmpty() }.joinToString("\n")
        val list = (Masks.ALL + Masks.auto).distinctBy { it.id }.filter { !it.hasNoise && it.hop.isEmpty() }
            .map { it to f.brain.predict(it, f.net, null) - Masks.cost(it) * 0.02 }.sortedByDescending { it.second }.take(5)
        return (if (cur.isNotEmpty()) cur + "\n" else "") + "Самые вероятные для «${f.net}»:\n" +
            list.mapIndexed { i, (m, _) -> "${i + 1}. ${m.title} — ${pct(f.brain.predict(m, f.net, null))}" + if (Masks.cost(m) >= 2) " (тяжёлая, медленнее)" else "" }.joinToString("\n")
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
        if (f.brain.trained && f.brain.predict(Masks.DEFAULT, f.net, null) < 0.4) append("\nВ сети «${f.net}» сильно режут — без маскировки почти ничего не пройдёт.")
    }

    private fun servers(f: Facts) = if (f.best.isEmpty()) "Рабочих серверов пока нет — нажмите «Проверить серверы»." else
        "Лучшие сейчас (из ${f.working} рабочих):\n" + f.best.take(5).mapIndexed { i, (n, ms, k) -> "${i + 1}. $n — $ms мс" + if (k > 0) ", ${"%.1f".format(k / 1000.0)} Мбит/с" else "" }.joinToString("\n")

    // ---------- in the app ----------
    fun facts(app: android.content.Context): Facts {
        val st = Store.state.value; val net = Net.key(app)
        val ok = st.servers.filter { st.state(it).works }.sortedBy { st.state(it).score }
        val sel = st.selected.firstOrNull() ?: ok.firstOrNull()
        val s = Tunnel.status.value
        return Facts(net, MaskBrain.shared, s.state == Tunnel.State.CONNECTED, s.route, s.check, st.servers.size, ok.size,
            ok.take(5).map { Triple(it.name, st.state(it).realMs, st.state(it).kbps) }, st.settings.mode, st.settings.ytDpi[net],
            (st.settings.dpiRemembered[net] ?: st.settings.dpiRemembered[Net.family(net)]) != null, sel?.let { Masks.byId(st.state(it).maskFor(net)) })
    }

    private fun post(m: Msg) { messages.value = (messages.value + m).takeLast(80) }

    fun send(app: android.content.Context, text: String) {
        val t = text.trim(); if (t.isEmpty()) return
        if (t.startsWith("!")) { run(app, t); return }
        post(Msg(true, t))
        val a = answer(t, facts(app))
        post(a)
        // a site in the question: check right away (one tap less)
        a.buttons.firstOrNull { it.cmd.startsWith("!site ") }?.let { run(app, it.cmd, quiet = true) }
    }

    /** Commands of the buttons; results come back as messages. */
    fun run(app: android.content.Context, cmd: String, quiet: Boolean = false) {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
        when {
            cmd.startsWith("!site ") -> {
                val host = cmd.removePrefix("!site ")
                if (!quiet) post(Msg(true, "Проверь $host"))
                Actions.checkSite(host)
                scope.launch { delay(300); while (Actions.siteCheck.value.running) delay(300); Actions.siteCheck.value.let { r ->
                    post(Msg(false, r.verdict + if (r.details.isNotEmpty()) "\n" + r.details else "")) } }
            }
            cmd == "!reset" -> { MaskBrain.reset(); post(Msg(false, "Обучение стёрто — начну заново со следующего подбора.")) }
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
}
