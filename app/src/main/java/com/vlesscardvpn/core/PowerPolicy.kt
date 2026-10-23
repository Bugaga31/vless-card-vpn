package com.vlesscardvpn.core

/** Pure policy: optional work only. It never disconnects the VPN or blocks urgent repairs. */
object PowerPolicy {
    data class Input(val now: Long, val battery: Int = -1, val charging: Boolean = false,
        val saver: Boolean = false, val screen: Boolean = true, val temp: Double = 0.0,
        val thermal: Int = -1, val metered: Boolean = false, val dataSaver: Boolean = false,
        val activeTraffic: Boolean = false, val lowMemory: Boolean = false, val gaming: Boolean = false,
        val recentLag: Boolean = false)
    data class Options(val profile: String = "balanced", val threshold: Int = 20,
        val quietScreen: Boolean = true, val chargeOnly: Boolean = false,
        val wifiOnly: Boolean = true, val protectTraffic: Boolean = true, val autoCool: Boolean = true)
    data class Memory(val low: Boolean = false, val hot: Boolean = false, val coolSince: Long = -1)
    data class Plan(val memory: Memory = Memory(), val allowed: Boolean = true,
        val reason: String = "Готов к фоновым улучшениям", val tcpParallel: Int = 96,
        val realParallel: Int = 12, val loopMs: Long = 3000, val healthyMs: Long = 20000, val allowLight: Boolean = true, val deferTraffic: Boolean = false, val screenInteractive: Boolean = true)
    fun evaluate(i: Input, o: Options, prev: Memory = Memory(), pauseUntil: Long = 0): Plan {
        val threshold = o.threshold.coerceIn(5, 50)
        val low = if (i.charging) false else if (i.battery !in 1..100) prev.low
            else i.battery <= threshold + if (prev.low) 5 else 0
        val critical = i.thermal >= 3 || i.temp >= 45
        val trigger = critical || i.thermal >= 2 || o.autoCool && (i.thermal >= 2 || i.temp >= if (i.charging) 39.0 else 41.0)
        // Unknown sensors do not count as evidence that the phone cooled down.
        val known = i.temp > 0 || i.thermal >= 0
        val cool = known && (i.temp <= 0 || i.temp < 38) && i.thermal < 2
        var hot = trigger || prev.hot
        var since = if (!trigger && prev.hot && cool) prev.coolSince.takeIf { it >= 0 } ?: i.now else -1
        if (hot && since >= 0 && i.now - since >= 120000) { hot = false; since = -1 }
        // On the charger with the screen off is the best heavy-work window: cool, idle, and free.
        val chargeWork = i.charging && !hot && !i.lowMemory && !i.gaming && !i.recentLag && !i.saver && !low && i.now >= pauseUntil && !i.dataSaver && !(o.wifiOnly && i.metered) && !(o.protectTraffic && i.activeTraffic)
        val reason = when {
            hot -> "Тепловая защита: ждём устойчивого остывания"
            i.lowMemory -> "Нехватка памяти: откладываем необязательную работу"
            i.gaming -> "Игровая сессия: фоновые улучшения приостановлены"
            i.saver -> "Системная экономия энергии"
            low -> "Низкий заряд батареи"
            i.now < pauseUntil -> "Пауза улучшений: ${(pauseUntil - i.now + 59999) / 60000} мин"
            i.dataSaver -> "Системная экономия трафика"
            o.wifiOnly && i.metered -> "Ожидание сети без тарификации"
            chargeWork && !i.screen -> "Зарядка и выключенный экран: работаем на максимум"
            !i.screen && o.quietScreen -> "Экран выключен: тихий режим"
            o.chargeOnly && !i.charging -> "Улучшения только на зарядке"
            o.protectTraffic && i.activeTraffic -> "Активный трафик: не мешаем загрузке"
            i.recentLag -> "Телефон тормозил: фоновые улучшения приторможены, чтобы не мешать"
            else -> "Готов к фоновым улучшениям"
        }
        val pressure = hot || i.lowMemory || i.gaming || i.recentLag || i.saver || low || o.profile == "eco" || (!i.screen && !chargeWork) || i.dataSaver || (o.protectTraffic && i.activeTraffic)
        val tcp = if (hot || i.lowMemory || i.gaming) 2 else if (pressure) 16 else if (o.profile == "fast") 128 else 64
        return Plan(Memory(low, hot, since), reason == "Готов к фоновым улучшениям" || (chargeWork && !i.screen), reason,
            tcp, if (pressure) 2 else if (o.profile == "fast") 12 else 6,
            if (!i.screen && !chargeWork) 15000 else if (pressure) 5000 else 3000,
            if (pressure) 45000 else 20000,
            !hot && !i.lowMemory && !i.gaming && !i.recentLag && !i.saver && !low && !(i.now < pauseUntil) && !(!i.screen && o.quietScreen && !chargeWork) && !i.dataSaver, o.protectTraffic && i.activeTraffic, i.screen)
    }
    fun cap(requested: Int, limit: Int) = requested.coerceIn(1, limit.coerceAtLeast(1))
}
