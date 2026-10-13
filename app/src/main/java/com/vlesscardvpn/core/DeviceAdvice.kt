package com.vlesscardvpn.core

/** System-wide signals, deliberately independent of VPN state and VPN power policy. */
data class DeviceSignals(val now: Long, val temp: Double = 0.0, val thermal: Int = -1,
    val charging: Boolean = false, val memoryUsed: Double = -1.0, val lowMemory: Boolean = false,
    val activeTraffic: Boolean = false)

object DeviceAdvice {
    data class Snapshot(val signals: DeviceSignals, val ramAvailable: Long, val ramTotal: Long,
        val storageAvailable: Long, val storageTotal: Long, val battery: Int, val saver: Boolean)
    data class Tip(val text: String, val action: String)
    fun tips(s: Snapshot, predicted: Boolean) = buildList {
        if (s.signals.thermal >= 2 || s.signals.temp >= 41 || s.signals.charging && s.signals.temp >= 39)
            add(Tip("Телефон нагрет. Уменьшите яркость и приостановите тяжёлые игры/загрузки. Не охлаждайте льдом или водой.", "display"))
        else if (predicted) add(Tip("Модель ожидает нагрев или нехватку памяти. Это прогноз, не измерение. Проверьте активные приложения.", "apps"))
        if (s.signals.lowMemory) add(Tip("Android сообщил о нехватке памяти. Сохраните работу в тяжёлых приложениях и закройте ненужные вручную. Обычный кэш RAM очищать не нужно.", "apps"))
        if (s.storageAvailable > 0 && s.storageTotal > 0 && (s.storageAvailable < 1_000_000_000L || s.storageAvailable.toDouble() / s.storageTotal < .10))
            add(Tip("Мало места во внутреннем хранилище. Проверьте загрузки и ненужные файлы в системных настройках — ничего не удаляется автоматически.", "storage"))
        if (s.battery in 1..20 && !s.signals.charging)
            add(Tip("Низкий заряд. Энергосбережение продлит работу, но может снизить производительность.", "battery"))
        else if (s.saver) add(Tip("Включена системная экономия: она может снижать производительность. Выбирайте баланс скорости и времени работы в настройках батареи.", "battery"))
        if (isEmpty()) add(Tip("Явных проблем по доступным датчикам нет. Это не замер скорости процессора; ускорение не обещается.", "apps"))
    }
}
