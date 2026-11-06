package com.vlesscardvpn.core

/**
 * Presentation only: swallows the second half of an accidental double tap on a toggle whose label changes right
 * after the first tap («Подключить» → «Отменить»). A deliberate tap after [windowMs] always passes.
 */
class TapGuard(private val windowMs: Long = WINDOW_MS) {
    private var last = Long.MIN_VALUE
    @Synchronized fun accept(now: Long): Boolean {
        if (last != Long.MIN_VALUE && now >= last && now - last < windowMs) return false
        last = now
        return true
    }
    companion object { const val WINDOW_MS = 700L }
}
