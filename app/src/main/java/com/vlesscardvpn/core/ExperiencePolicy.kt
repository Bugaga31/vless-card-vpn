package com.vlesscardvpn.core

/** Small, testable presentation rules. No invented progress percentages or system-wide speed claims. */
object ExperiencePolicy {
    fun progress(done: Int, total: Int): Float? = if (total <= 0) null else
        done.coerceIn(0, total).toFloat() / total
    fun counter(done: Int, total: Int) = if (total > 0) "${done.coerceIn(0, total)} из $total" else "Подготавливаем проверку"
    fun sampleMs(eco: Boolean, allowLight: Boolean) = if (eco || !allowLight) 30000L else 10000L
    fun motion(enabled: Boolean, foreground: Boolean, allowLight: Boolean, scale: Float) =
        enabled && foreground && allowLight && scale.isFinite() && scale > 0f
}
