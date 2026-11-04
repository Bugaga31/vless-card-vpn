package com.vlesscardvpn.core

/** Probe response spread, NOT RTP jitter or a guarantee about an authenticated call. */
object LinkStabilityPolicy {
    data class Sample(val ok: Boolean?, val ms: Int)
    data class Summary(val attempted: Int, val answered: Int, val unknown: Int, val medianMs: Int?, val spreadMs: Int?, val stable: Boolean?)
    fun summarize(samples: List<Sample>, maxMs: Int): Summary {
        val bounded = samples.takeLast(5)
        val good = bounded.filter { it.ok == true && it.ms > 0 }.map { it.ms }.sorted()
        val unknown = bounded.count { it.ok == null || it.ok == true && it.ms <= 0 }
        val spread = good.takeIf { it.isNotEmpty() }?.let { it.last() - it.first() }
        // Разброс — не приговор: допускаем до половины медианы (минимум 150 мс) и одну «неизвестную» пробу.
        val spreadAllowance = maxOf(150, (good.lastOrNull() ?: 0) / 2)
        val stable = when {
            bounded.any { it.ok == false } -> false
            good.size < 3 || unknown > 1 -> null
            else -> good.last() <= maxMs && (spread ?: Int.MAX_VALUE) <= spreadAllowance
        }
        return Summary(bounded.size, good.size, unknown, good.takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }, spread, stable)
    }
    fun label(s: Summary): String = when(s.stable) {
        true -> "Стабильно в серии локальных проб"
        false -> "Есть сбои, превышение допуска или разброс ответов"
        null -> "Стабильность пока не подтверждена"
    } + " · ответы ${s.answered}/${s.attempted}" + (s.medianMs?.let { " · медиана $it мс" } ?: "") +
        (s.spreadMs?.let { " · разброс $it мс" } ?: "")
}
